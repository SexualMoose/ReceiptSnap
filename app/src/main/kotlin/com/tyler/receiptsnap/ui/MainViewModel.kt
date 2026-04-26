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
            /** Frames from secondary lenses (main, ultrawide) with their
             *  focal ratios to the primary — used to map a tap point from
             *  the primary preview into each secondary image when the user
             *  taps a receipt the primary missed. */
            val secondaries: List<SecondaryFrame>,
            val quads: List<DocumentDetector.Quad>,
            val nextQuadId: Long,
            /** Best-yet metadata observed during preview leading up to
             *  capture — date / total / location seen across the live
             *  analyzer's frames. Used as fallback when per-receipt OCR
             *  on a warped crop misses a field. Only safe to apply when
             *  the captured frame yields exactly one receipt; with
             *  multiple receipts the cache could mix metadata. */
            val previewMetadata: CameraController.FrameMetadata =
                CameraController.FrameMetadata(),
        ) : Phase

        data class SecondaryFrame(
            val bitmap: Bitmap,
            /** focalLength(primary) / focalLength(this) — telephoto-to-main
             *  is ~3.0, so tap displacements shrink by this factor. */
            val focalRatioToPrimary: Float,
        )
    }

    data class UiState(
        val phase: Phase = Phase.Camera,
        val busy: Boolean = false,
        val status: String? = null,
        /** When > 0, a capture sequence is running: [captureProgress] is the
         *  number of cameras captured so far and [captureTotal] is how many
         *  we plan to take. UI uses this to draw a progress bar. */
        val captureProgress: Int = 0,
        val captureTotal: Int = 0,
        val lastSaved: List<SavedReceipt> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun capture(controller: CameraController, lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: androidx.camera.view.PreviewView) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                busy = true,
                status = "Capturing…",
                error = null,
                captureProgress = 0,
                captureTotal = 3,  // rough estimate; corrected by first progress callback
            )

            val frames = try {
                controller.captureAll(lifecycleOwner, previewView) { done, total, label ->
                    _state.value = _state.value.copy(
                        captureProgress = done,
                        captureTotal = total,
                        status = "Capturing $label · ${done + 1} of $total",
                    )
                }
            } catch (t: Throwable) {
                Log.e(TAG, "captureAll failed", t)
                emptyList()
            }
            if (frames.isEmpty()) {
                _state.value = _state.value.copy(
                    busy = false, status = null, error = "Capture failed.",
                    captureProgress = 0, captureTotal = 0,
                )
                return@launch
            }
            _state.value = _state.value.copy(captureProgress = 0, captureTotal = 0)

            val primary = frames.first()
            val secondaries = frames.drop(1).map { f ->
                Phase.SecondaryFrame(
                    bitmap = f.bitmap,
                    focalRatioToPrimary = primary.lens.focalLengthMm / f.lens.focalLengthMm.coerceAtLeast(0.01f),
                )
            }

            _state.value = _state.value.copy(status = "Detecting documents…")
            val quads = withContext(Dispatchers.Default) {
                try {
                    DocumentDetector.detect(primary.bitmap)
                } catch (t: Throwable) {
                    Log.e(TAG, "Detection failed", t)
                    emptyList()
                }
            }

            val stats = DocumentDetector.lastStats
            val diag = "OCR: ${stats.textLines} lines · ${stats.clusters} clusters " +
                "· ${stats.fromText} from text · ${stats.fromEdges} from edges · " +
                "lens=${primary.lens.kind} +${secondaries.size} secondary"
            Log.i(TAG, diag)

            _state.value = _state.value.copy(
                phase = Phase.Review(
                    bitmap = primary.bitmap,
                    secondaries = secondaries,
                    quads = quads,
                    nextQuadId = (quads.maxOfOrNull { it.id } ?: 0L) + 1,
                    // Snapshot the analyzer cache at capture time so a
                    // later tap-to-add (which keeps preview running) can't
                    // poison the metadata used for the receipts we already
                    // detected.
                    previewMetadata = controller.bestMetadata.value,
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
            // Try the primary capture first. If the primary missed a
            // receipt but the user saw it, try each secondary frame too —
            // the main/ultrawide may have caught it when the telephoto
            // didn't (e.g. the receipt was outside the telephoto FOV).
            val grown = withContext(Dispatchers.Default) {
                val primaryHit = try {
                    DocumentDetector.growFromSeed(phase.bitmap, seedX, seedY, phase.nextQuadId)
                } catch (t: Throwable) {
                    Log.w(TAG, "growFromSeed on primary failed", t); null
                }
                if (primaryHit != null) return@withContext primaryHit

                // Fall back through secondaries. We map the tap point from
                // the primary's pixel space into each secondary assuming
                // the same world scene is centred in both frames and FOV
                // scales inversely with focal length.
                for (sec in phase.secondaries) {
                    val mapped = mapCoords(phase.bitmap, sec, seedX, seedY)
                        ?: continue
                    val hit = try {
                        DocumentDetector.growFromSeed(sec.bitmap, mapped.first, mapped.second, phase.nextQuadId)
                    } catch (t: Throwable) {
                        Log.w(TAG, "growFromSeed on secondary failed", t); null
                    }
                    if (hit != null) {
                        val primaryCorners = hit.corners.map { p ->
                            val back = mapCoordsBack(phase.bitmap, sec, p.x, p.y) ?: return@map p
                            org.opencv.core.Point(back.first, back.second)
                        }
                        return@withContext DocumentDetector.Quad(
                            id = hit.id,
                            corners = primaryCorners,
                        )
                    }
                }

                // Last resort: 3× upscaled text-based grow. Much more
                // expensive than flood-fill but it handles the case where
                // the receipt is low-contrast against the surface yet
                // still OCR-able when given more pixel budget. Try each
                // frame in turn (primary first).
                val upscaledOnPrimary = try {
                    DocumentDetector.growFromSeedWithText(phase.bitmap, seedX, seedY, phase.nextQuadId)
                } catch (t: Throwable) {
                    Log.w(TAG, "growFromSeedWithText on primary failed", t); null
                }
                if (upscaledOnPrimary != null) return@withContext upscaledOnPrimary

                for (sec in phase.secondaries) {
                    val mapped = mapCoords(phase.bitmap, sec, seedX, seedY) ?: continue
                    val hit = try {
                        DocumentDetector.growFromSeedWithText(sec.bitmap, mapped.first, mapped.second, phase.nextQuadId)
                    } catch (t: Throwable) {
                        Log.w(TAG, "growFromSeedWithText on secondary failed", t); null
                    }
                    if (hit != null) {
                        val primaryCorners = hit.corners.map { p ->
                            val back = mapCoordsBack(phase.bitmap, sec, p.x, p.y) ?: return@map p
                            org.opencv.core.Point(back.first, back.second)
                        }
                        return@withContext DocumentDetector.Quad(
                            id = hit.id,
                            corners = primaryCorners,
                        )
                    }
                }
                null
            }

            val quad = grown ?: defaultQuadAt(phase.bitmap, seedX, seedY, phase.nextQuadId)

            _state.update { current ->
                val p = current.phase as? Phase.Review ?: return@update current
                current.copy(
                    phase = p.copy(
                        quads = p.quads + quad,
                        nextQuadId = p.nextQuadId + 1,
                    ),
                    status = when {
                        grown != null -> "Added region (found via secondary camera if needed)"
                        else -> "Couldn't find a similar region — placeholder added"
                    },
                )
            }
        }
    }

    /** Map a point in the primary bitmap to the corresponding point in
     *  a secondary camera's bitmap, assuming both captures are centred on
     *  the same world scene. Returns null when the point falls outside the
     *  secondary's frame. */
    private fun mapCoords(
        primary: Bitmap,
        sec: Phase.SecondaryFrame,
        primaryX: Double,
        primaryY: Double,
    ): Pair<Double, Double>? {
        val ratio = sec.focalRatioToPrimary.toDouble()
        val dx = (primaryX - primary.width / 2.0) / ratio
        val dy = (primaryY - primary.height / 2.0) / ratio
        val sx = sec.bitmap.width / 2.0 + dx
        val sy = sec.bitmap.height / 2.0 + dy
        if (sx < 0 || sy < 0 || sx >= sec.bitmap.width || sy >= sec.bitmap.height) return null
        return sx to sy
    }

    private fun mapCoordsBack(
        primary: Bitmap,
        sec: Phase.SecondaryFrame,
        secX: Double,
        secY: Double,
    ): Pair<Double, Double>? {
        val ratio = sec.focalRatioToPrimary.toDouble()
        val dx = (secX - sec.bitmap.width / 2.0) * ratio
        val dy = (secY - sec.bitmap.height / 2.0) * ratio
        val px = primary.width / 2.0 + dx
        val py = primary.height / 2.0 + dy
        if (px < 0 || py < 0 || px >= primary.width || py >= primary.height) return null
        return px to py
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
        if (current is Phase.Review) {
            current.bitmap.recycle()
            current.secondaries.forEach { it.bitmap.recycle() }
        }
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
                    .getOrElse { ReceiptParser.Info(null, null, false, null, "") }

                // Single-receipt frames get the preview-time metadata
                // cache as a fallback for any field per-crop OCR missed.
                // For multi-receipt frames we skip the fallback because
                // the cache is whole-frame and could mix metadata across
                // adjacent receipts.
                val effective = if (toSave.size == 1) {
                    val cache = phase.previewMetadata
                    info.copy(
                        date = info.date ?: cache.date,
                        location = info.location ?: cache.location,
                        total = info.total ?: cache.total,
                    )
                } else info

                val result = runCatching {
                    ReceiptStorage.save(
                        context = getApplication(),
                        bitmap = cropped,
                        date = effective.date,
                        location = effective.location,
                        isMeal = effective.isMeal,
                        total = effective.total,
                    )
                }.onFailure { Log.e(TAG, "Save failed", it) }.getOrNull()

                cropped.recycle()
                if (result != null) saved += SavedReceipt(result.uri, result.displayName)
            }

            phase.bitmap.recycle()
            phase.secondaries.forEach { it.bitmap.recycle() }

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
