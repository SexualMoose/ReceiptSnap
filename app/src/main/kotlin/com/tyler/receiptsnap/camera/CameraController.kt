package com.tyler.receiptsnap.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps CameraX ImageCapture, binding to the largest sensor resolution the device exposes.
 * On Samsung S26 Ultra this selects the 200MP back sensor when available.
 */
class CameraController(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val provider = ProcessCameraProvider.getInstance(context).awaitInstance()
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // Ask CameraX for the highest resolution the sensor advertises, including
        // high-res sensor modes (enabled via allowedResolutionMode).
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(Int.MAX_VALUE, Int.MAX_VALUE),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                )
            )
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
        )
        imageCapture = capture

        val selectedSize = capture.resolutionInfo?.resolution
        Log.i("CameraController", "Selected capture resolution: $selectedSize")
    }

    suspend fun capture(): Bitmap = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not bound"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val bitmap = image.toOrientedBitmap()
                    cont.resume(bitmap)
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    fun release() {
        executor.shutdown()
        camera = null
        imageCapture = null
    }

    private fun ImageProxy.toOrientedBitmap(): Bitmap {
        val buffer: ByteBuffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        // Honor EXIF rotation rather than the ImageProxy rotation because a
        // physical rotation encoded in JPEG metadata can confuse downstream
        // perspective math if ignored.
        val exifRotation = runCatching {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (exifRotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> imageInfo.rotationDegrees.toFloat()
        }

        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: error("Failed to decode JPEG (${bytes.size} bytes)")

        if (degrees == 0f) return decoded
        val m = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    private suspend fun com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider>.awaitInstance(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            addListener(
                { runCatching { cont.resume(get()) }.onFailure(cont::resumeWithException) },
                ContextCompat.getMainExecutor(context),
            )
        }
}
