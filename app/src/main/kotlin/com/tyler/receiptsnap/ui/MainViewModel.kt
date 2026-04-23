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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {

    data class SavedReceipt(val uri: Uri, val displayName: String)

    data class UiState(
        val busy: Boolean = false,
        val status: String? = null,
        val saved: List<SavedReceipt> = emptyList(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun captureAndProcess(controller: CameraController) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, status = "Capturing…", error = null)
            val captured: Bitmap = try {
                controller.capture()
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Capture failed", t)
                _state.value = _state.value.copy(busy = false, status = null, error = t.message)
                return@launch
            }

            _state.value = _state.value.copy(status = "Detecting documents…")
            val docs = withContext(Dispatchers.Default) {
                runCatching { DocumentDetector.detectAndExtract(captured) }
                    .onFailure { Log.e("MainViewModel", "Detection failed", it) }
                    .getOrElse { emptyList() }
            }
            captured.recycle()

            if (docs.isEmpty()) {
                _state.value = _state.value.copy(
                    busy = false,
                    status = null,
                    error = "No receipts detected — try better lighting and more space between them.",
                )
                return@launch
            }

            val saved = mutableListOf<SavedReceipt>()
            docs.forEachIndexed { index, bmp ->
                _state.value = _state.value.copy(status = "Reading receipt ${index + 1} of ${docs.size}…")
                val info = runCatching { ReceiptParser.parse(bmp) }
                    .getOrElse { ReceiptParser.ReceiptInfo(null, null) }

                val result = runCatching {
                    ReceiptStorage.save(
                        context = getApplication(),
                        bitmap = bmp,
                        merchant = info.merchant,
                        date = info.date,
                    )
                }.onFailure { Log.e("MainViewModel", "Save failed", it) }.getOrNull()

                bmp.recycle()
                if (result != null) saved += SavedReceipt(result.uri, result.displayName)
            }

            _state.value = UiState(
                busy = false,
                status = "Saved ${saved.size} of ${docs.size}",
                saved = saved,
            )
        }
    }

    fun clearStatus() {
        _state.value = _state.value.copy(status = null, error = null)
    }
}
