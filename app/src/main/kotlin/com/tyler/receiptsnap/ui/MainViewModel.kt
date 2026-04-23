package com.tyler.receiptsnap.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tyler.receiptsnap.camera.CameraController
import com.tyler.receiptsnap.processing.DocumentDetector
import com.tyler.receiptsnap.processing.ReceiptParser
import com.tyler.receiptsnap.storage.ReceiptStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Point as CvPoint

/**
 * State machine: Camera → (capture) Review → (commit/cancel) Camera/Library.
 * The review-phase quad list is mutable VM state so background flood-fill
 * growth can append to it without racing the composition.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class SavedReceipt(val uri: Uri, val displayName: String)

    sealed interface Phase {
        data object Camera : Phase
        data class Review(
            val bitmap: Bitmap,
            val quads: List<DocumentDetector.Quad>,
            val nextQuadId: Long,
        ) : Phase
    }

    data class UiState(
        val phase: Phase = Phase.Camera,
        val busy: Boolean = false,
        val status: String? = null,
        val lastSaved: List<SavedReceipt> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun capture(controller: CameraController) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, status = "Capturing…", error = null)
            val photo = try {
                controller.capture()
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
                _state.value = _state.value.copy(busy = false, status = null, error = t.message)
                return@launch
            }

            _state.value = _state.value.copy(status = "Detecting documents…")
            val quads = withContext(Dispatchers.Default) {
                try {
                    DocumentDetector.detect(photo)
                } catch (t: Throwable) {
                    Log.e(TAG, "Detection failed", t)
                    emptyList()
                }
            }

            val stats = DocumentDetector.lastStats
            val diag = "OCR: ${stats.textLines} lines · ${stats.clusters} clusters " +
                "· ${stats.fromText} from text · ${stats.fromEdges} from edges"
            Log.i(TAG, diag)

            _state.value = _state.value.copy(
                phase = Phase.Review(
                    bitmap = photo,
                    quads = quads,
                    nextQuadId = (quads.maxOfOrNull { it.id } ?: 0L) + 1,
                ),
                busy = false,
                status = if (quads.isEmpty())
                    "No receipts found. $diag. Tap to add one."
                else
                    "Found ${quads.size}. $diag",
            )
        }
    }

    /**
     * User tapped an empty area in the review view. First try to grow a region
     * from the tapped pixel's color (the user is saying "here's a receipt
     * the detector missed"). If growth fails, drop a default placeholder
     * rectangle so they still get something to adjust.
     */
    fun addQuadFromSeed(seedX: Double, seedY: Double) {
        val phase = _state.value.phase as? Phase.Review ?: return
        viewModelScope.launch {
            val grown = withContext(Dispatchers.Default) {
                try {
                    DocumentDetector.growFromSeed(phase.bitmap, seedX, seedY, phase.nextQuadId)
                } catch (t: Throwable) {
                    Log.w(TAG, "growFromSeed failed", t)
                    null
                }
            }
            val quad = grown ?: defaultQuadAt(phase.bitmap, seedX, seedY, phase.nextQuadId)

            _state.update { current ->
                val p = current.phase as? Phase.Review ?: return@update current
                current.copy(
                    phase = p.copy(
                        quads = p.quads + quad,
                        nextQuadId = p.nextQuadId + 1,
                    ),
                    status = if (grown != null)
                        "Added region from similar background"
                    else
                        "Couldn't find a similar region — added placeholder",
                )
            }
        }
    }

    private fun defaultQuadAt(
        bitmap: Bitmap, seedX: Double, seedY: Double, id: Long,
    ): DocumentDetector.Quad {
        val w = bitmap.width * 0.25
        val h = bitmap.height * 0.40
        return DocumentDetector.Quad(
            id = id,
            corners = listOf(
                CvPoint(seedX - w / 2, seedY - h / 2),
                CvPoint(seedX + w / 2, seedY - h / 2),
                CvPoint(seedX + w / 2, seedY + h / 2),
                CvPoint(seedX - w / 2, seedY + h / 2),
            ),
        )
    }

    /** Remove a quad the user decided wasn't a receipt. */
    fun removeQuad(id: Long) {
        _state.update { current ->
            val p = current.phase as? Phase.Review ?: return@update current
            current.copy(phase = p.copy(quads = p.quads.filterNot { it.id == id }))
        }
    }

    /**
     * Update one corner of a quad during manual resize. Accepts the new
     * position; the caller (ReviewScreen) is responsible for clamping it
     * to image bounds and checking convexity. If the resulting quad would
     * be non-convex, the drag-end handler in the UI rolls it back.
     */
    fun updateCorner(id: Long, cornerIdx: Int, newPoint: CvPoint) {
        _state.update { current ->
            val p = current.phase as? Phase.Review ?: return@update current
            val updated = p.quads.map { q ->
                if (q.id != id) q
                else q.copy(corners = q.corners.toMutableList().also { it[cornerIdx] = newPoint })
            }
            current.copy(phase = p.copy(quads = updated))
        }
    }

    fun cancelReview() {
        val current = _state.value.phase
        if (current is Phase.Review) current.bitmap.recycle()
        _state.value = UiState(phase = Phase.Camera)
    }

    /** Commit every quad currently in the review list. Removed quads are
     *  already gone by the time this runs. */
    fun commitReview() {
        val phase = _state.value.phase
        if (phase !is Phase.Review || _state.value.busy) return
        val toSave = phase.quads
        if (toSave.isEmpty()) {
            _state.value = _state.value.copy(error = "Add or keep at least one region.")
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, error = null)

            val saved = mutableListOf<SavedReceipt>()
            toSave.forEachIndexed { index, quad ->
                _state.value = _state.value.copy(
                    status = "Processing ${index + 1} of ${toSave.size}…",
                )
                val cropped = withContext(Dispatchers.Default) {
                    runCatching { DocumentDetector.warp(phase.bitmap, quad) }
                        .onFailure { Log.e(TAG, "Warp failed", it) }
                        .getOrNull()
                } ?: return@forEachIndexed

                val info = runCatching { ReceiptParser.parse(cropped) }
                    .getOrElse { ReceiptParser.Info(null, null, false, "") }

                val result = runCatching {
                    ReceiptStorage.save(
                        context = getApplication(),
                        bitmap = cropped,
                        date = info.date,
                        location = info.location,
                        isMeal = info.isMeal,
                    )
                }.onFailure { Log.e(TAG, "Save failed", it) }.getOrNull()

                cropped.recycle()
                if (result != null) saved += SavedReceipt(result.uri, result.displayName)
            }

            phase.bitmap.recycle()

            _state.value = UiState(
                phase = Phase.Camera,
                status = "Saved ${saved.size} of ${toSave.size}",
                lastSaved = saved,
            )
        }
    }

    fun dismissStatus() {
        _state.value = _state.value.copy(status = null, error = null)
    }

    companion object {
        private const val TAG = "MainViewModel"
    }
}
