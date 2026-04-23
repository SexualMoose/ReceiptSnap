package com.tyler.receiptsnap.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
 * Wraps CameraX ImageCapture targeting the largest output the sensor can
 * produce. On Samsung S26 Ultra (and any device exposing a 200 MP main
 * sensor) the biggest output lives in the sensor's MAXIMUM_RESOLUTION
 * pixel mode, which CameraX will NOT automatically pick via just
 * PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE — the config-map and the
 * per-request SENSOR_PIXEL_MODE have to be set explicitly. This class
 * does that.
 *
 * Fallback: when the sensor doesn't advertise a max-res configuration
 * (older devices or a lens with no remosaic mode), we fall back to the
 * default highest-resolution strategy.
 */
class CameraController(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    @OptIn(ExperimentalCamera2Interop::class)
    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        // Diagnostic: walk every physical camera the device exposes to the
        // public Camera2 API so we can see what's actually available on this
        // firmware. Samsung's 200 MP sensor often sits on a physical sub-
        // camera of a logical multi-camera; CameraX's default back camera
        // may not surface that sub-camera's MAXIMUM_RESOLUTION map.
        logAllPhysicalCameras()

        val provider = ProcessCameraProvider.getInstance(context).awaitInstance()
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        // Inspect the back camera's MAXIMUM_RESOLUTION stream config map
        // (Android 12+, API 31). If present, we lock the capture resolution
        // to the largest advertised JPEG size and set SENSOR_PIXEL_MODE on
        // every capture request so the sensor actually operates in its
        // high-res mode.
        val backCameraInfo = pickBackCameraInfo(provider)
        val maxResJpeg = backCameraInfo?.let { queryMaxResolutionJpegSize(it) }

        val resolutionSelector = if (maxResJpeg != null) {
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(maxResJpeg, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
        } else {
            ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(Int.MAX_VALUE, Int.MAX_VALUE),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                    )
                )
                .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                .build()
        }

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)

        // Per-capture-request: opt into SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION
        // (API 31+) so the sensor produces the high-res (e.g. 200 MP) frame.
        // Without this, the CaptureRequest defaults to the binned pixel mode
        // even when the resolution map says a 200 MP output exists.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && maxResJpeg != null) {
            Camera2Interop.Extender(captureBuilder).setCaptureRequestOption(
                CaptureRequest.SENSOR_PIXEL_MODE,
                CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION,
            )
            Log.i(TAG, "SENSOR_PIXEL_MODE set to MAXIMUM_RESOLUTION for ${maxResJpeg.width}×${maxResJpeg.height}")
        } else {
            Log.i(TAG, "Max-resolution sensor mode unavailable — using default sensor mode")
        }

        val capture = captureBuilder.build()

        camera = provider.bindToLifecycle(
            lifecycleOwner,
            CameraSelector.DEFAULT_BACK_CAMERA,
            preview,
            capture,
        )
        imageCapture = capture

        val selected = capture.resolutionInfo?.resolution
        val mp = selected?.let { (it.width.toLong() * it.height) / 1_000_000.0 }
        Log.i(
            TAG,
            "Bound capture: $selected (~${mp?.let { "%.1f".format(it) } ?: "?"} MP) " +
                "maxResConfigured=${maxResJpeg != null}",
        )
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

    // ------------------------------------------------------------------

    /** Dump every camera ID's key specs so we can see, in logcat, exactly
     *  what the device advertises. Useful for understanding why the S26
     *  Ultra might be pegged to 12.5 MP. */
    private fun logAllPhysicalCameras() {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val listed = runCatching { mgr.cameraIdList }.getOrDefault(emptyArray()).toSet()
        // Pick up hidden physical sub-IDs from each logical camera so we can
        // also probe the 200 MP-class lenses that Samsung leaves out of
        // cameraIdList.
        val hidden = listed.flatMap { id ->
            runCatching { mgr.getCameraCharacteristics(id).physicalCameraIds }
                .getOrDefault(emptySet())
        }.toSet()
        val allIds = (listed + hidden).sorted()
        Log.i(TAG, "Camera IDs (listed+physical): ${allIds.joinToString()} (listed=$listed, hidden=$hidden)")
        for (id in allIds) {
            val chars = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val facingName = when (facing) {
                CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
                CameraCharacteristics.LENS_FACING_BACK -> "BACK"
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            val pixelArray = chars.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val defaultMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val defaultMax = defaultMap?.getOutputSizes(ImageFormat.JPEG)
                ?.maxByOrNull { it.width.toLong() * it.height }
            val defaultMp = defaultMax?.let { (it.width.toLong() * it.height) / 1_000_000.0 }
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.joinToString()
            var maxResInfo = "n/a"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val maxResMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
                val maxResMax = maxResMap?.getOutputSizes(ImageFormat.JPEG)
                    ?.maxByOrNull { it.width.toLong() * it.height }
                val maxResMp = maxResMax?.let { (it.width.toLong() * it.height) / 1_000_000.0 }
                maxResInfo = if (maxResMax != null)
                    "$maxResMax (${"%.1f".format(maxResMp)} MP)"
                else "none"
            }
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                runCatching { chars.physicalCameraIds }.getOrDefault(emptySet()).joinToString()
            else ""
            Log.i(
                TAG,
                "cam[$id] facing=$facingName sensor=$pixelArray " +
                    "defaultMaxJpeg=$defaultMax${defaultMp?.let { " (${"%.1f".format(it)} MP)" } ?: ""} " +
                    "maxResJpeg=$maxResInfo " +
                    "physicalIds=[$physicalIds] caps=[$capabilities]",
            )
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickBackCameraInfo(provider: ProcessCameraProvider): CameraInfo? {
        return provider.availableCameraInfos.firstOrNull { info ->
            val c2 = Camera2CameraInfo.from(info)
            c2.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /**
     * Returns the largest JPEG output size in the MAXIMUM_RESOLUTION stream
     * configuration map for the given camera, or null if the sensor doesn't
     * expose that mode. The MAXIMUM_RESOLUTION map is the one that contains
     * full-resolution (non-binned) outputs on modern sensors.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun queryMaxResolutionJpegSize(cameraInfo: CameraInfo): Size? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val chars = Camera2CameraInfo.from(cameraInfo)
        val maxResMap: StreamConfigurationMap? = chars.getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION
        )
        if (maxResMap == null) {
            Log.i(TAG, "No SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION on this camera")
            return null
        }
        val jpegSizes = maxResMap.getOutputSizes(ImageFormat.JPEG)
        if (jpegSizes.isNullOrEmpty()) {
            Log.i(TAG, "Max-res config map has no JPEG output sizes")
            return null
        }
        val largest = jpegSizes.maxByOrNull { it.width.toLong() * it.height } ?: return null
        val mp = (largest.width.toLong() * largest.height) / 1_000_000.0
        Log.i(TAG, "Max-res JPEG sizes: ${jpegSizes.joinToString()} → picking $largest (${"%.1f".format(mp)} MP)")

        // Also log the default (non-max-res) map so we can tell in logs
        // whether the high-res selection actually mattered.
        val defaultMap = chars.getCameraCharacteristic(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        )
        val defaultLargest = defaultMap?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height }
        Log.i(TAG, "Default-mode max JPEG size: $defaultLargest")

        return largest
    }

    private fun ImageProxy.toOrientedBitmap(): Bitmap {
        val buffer: ByteBuffer = planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

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
        Log.i(
            TAG,
            "Decoded bitmap: ${decoded.width}×${decoded.height} (~" +
                "%.1f".format(decoded.width.toLong() * decoded.height / 1_000_000.0) + " MP)",
        )

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

    private companion object {
        const val TAG = "CameraController"
    }
}
