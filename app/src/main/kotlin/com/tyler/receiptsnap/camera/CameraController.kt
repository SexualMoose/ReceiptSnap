package com.tyler.receiptsnap.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
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
 * CameraX wrapper that:
 *   - Identifies the device's back physical cameras by focal length
 *     (ultrawide / main / telephoto / periscope).
 *   - Binds to the telephoto by default — it gives the best pixel-per-glyph
 *     density for receipts photographed at a typical hand-held distance.
 *   - Captures one primary bitmap (the telephoto) and, in a rapid follow-up
 *     sequence, a secondary bitmap from the main wide and a tertiary from
 *     the ultrawide, so the review UI can fall back to a wider-field image
 *     when the user taps to add a receipt the telephoto missed.
 */
class CameraController(private val context: Context) {

    enum class Lens { UltraWide, Main, Telephoto, Periscope }

    data class CameraLens(
        val physicalId: String,
        val kind: Lens,
        val focalLengthMm: Float,
        /** Rough focal-length ratio to the MAIN lens, used to map tap
         *  coordinates between captures when parallax is negligible. */
        val mainRatio: Float,
    )

    data class CapturedFrame(
        val bitmap: Bitmap,
        val lens: CameraLens,
    )

    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var boundPhysicalId: String? = null

    /** Populated once on first bind. Ordered by focal length ascending. */
    private var knownLenses: List<CameraLens> = emptyList()
    private var primaryLens: CameraLens? = null

    // --- public API ---------------------------------------------------------

    /** Binds the preview to the best telephoto lens the device exposes, or
     *  falls back to the default back camera when no telephoto is found. */
    suspend fun bind(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).awaitInstance()
        this.provider = cameraProvider
        if (knownLenses.isEmpty()) {
            knownLenses = enumerateBackLenses()
            Log.i(TAG, "Back lenses: ${knownLenses.joinToString { "${it.kind}(id=${it.physicalId} ${it.focalLengthMm}mm)" }}")
        }
        primaryLens = choosePrimaryLens(knownLenses)
        Log.i(TAG, "Primary lens: ${primaryLens?.kind} (id=${primaryLens?.physicalId})")

        bindLens(cameraProvider, lifecycleOwner, previewView, primaryLens)
    }

    /**
     * Capture a full set of frames. The primary (telephoto if available) is
     * captured first with the bound preview; then the preview is re-bound to
     * each other lens in turn and a JPEG is captured so the user can review
     * multiple viewpoints. Returns the list in primary-first order. The
     * preview is re-bound to the primary lens on completion.
     *
     * [onProgress] fires before each individual capture with (completed,
     * totalPlanned, lensKindLabel) so the UI can show a progress bar
     * during the 4–5 second sequence.
     */
    suspend fun captureAll(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onProgress: ((completed: Int, total: Int, label: String) -> Unit)? = null,
    ): List<CapturedFrame> {
        val cameraProvider = provider ?: error("Camera not bound")
        val lenses = knownLenses
        if (lenses.isEmpty()) return emptyList()

        val primary = primaryLens ?: lenses.first()
        // Dedupe by lens kind — devices often expose several physical "main"
        // cameras (different binning modes, OIS variants) that behave
        // identically for our purposes. Capturing all of them just burns
        // 2–3 s per redundant rebind. Skip the periscope because its
        // minimum focus distance (~1 m) excludes tabletop receipt shots.
        val secondaryOrder = lenses
            .filter { it != primary && it.kind != Lens.Periscope }
            .distinctBy { it.kind }
            .sortedBy {
                when (it.kind) {
                    Lens.Main -> 0
                    Lens.UltraWide -> 1
                    Lens.Telephoto -> 2
                    Lens.Periscope -> 3
                }
            }

        val total = 1 + secondaryOrder.size
        val frames = mutableListOf<CapturedFrame>()

        // Primary — preview is already bound to this lens.
        onProgress?.invoke(0, total, primary.kind.name)
        runCatching { captureOnce() }
            .onSuccess { frames += CapturedFrame(it, primary) }
            .onFailure { Log.e(TAG, "Primary capture failed on ${primary.kind}", it) }

        // Secondary / tertiary — rebind briefly, capture, rebind back.
        for ((i, lens) in secondaryOrder.withIndex()) {
            onProgress?.invoke(1 + i, total, lens.kind.name)
            try {
                bindLens(cameraProvider, lifecycleOwner, previewView, lens)
                frames += CapturedFrame(captureOnce(), lens)
            } catch (t: Throwable) {
                Log.w(TAG, "Secondary capture failed on ${lens.kind}", t)
            }
        }
        onProgress?.invoke(total, total, "Done")

        // Leave preview bound to the primary lens.
        runCatching { bindLens(cameraProvider, lifecycleOwner, previewView, primary) }

        return frames
    }

    /** Backwards-compatible single-shot capture for the primary lens. */
    suspend fun capture(): Bitmap = captureOnce()

    fun release() {
        executor.shutdown()
        camera = null
        imageCapture = null
        provider = null
        boundPhysicalId = null
    }

    // --- internals ----------------------------------------------------------

    @OptIn(ExperimentalCamera2Interop::class)
    private fun enumerateBackLenses(): List<CameraLens> {
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val listed = runCatching { mgr.cameraIdList }.getOrDefault(emptyArray()).toSet()
        val hidden = listed.flatMap { id ->
            runCatching { mgr.getCameraCharacteristics(id).physicalCameraIds }
                .getOrDefault(emptySet())
        }.toSet()
        val all = (listed + hidden).toSortedSet()

        val candidates = mutableListOf<Pair<String, Float>>()
        for (id in all) {
            val chars = runCatching { mgr.getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK)
                continue
            val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: continue
            if (focals.isEmpty()) continue
            candidates += id to focals.max()
        }
        if (candidates.isEmpty()) return emptyList()

        val mainFocal = candidates.map { it.second }
            .filter { it in 3.0f..10.0f }  // typical 24-28mm-equivalent main lens
            .minOrNull() ?: candidates.map { it.second }.min()

        return candidates
            .sortedBy { it.second }
            .map { (id, focal) ->
                val kind = classifyLens(focal)
                CameraLens(
                    physicalId = id,
                    kind = kind,
                    focalLengthMm = focal,
                    mainRatio = focal / mainFocal.coerceAtLeast(0.01f),
                )
            }
    }

    private fun classifyLens(focalMm: Float): Lens = when {
        focalMm < 3.0f -> Lens.UltraWide
        focalMm < 10.0f -> Lens.Main
        focalMm < 25.0f -> Lens.Telephoto
        else -> Lens.Periscope
    }

    /** Prefer the regular telephoto (3× range). Fall back to main wide if the
     *  device doesn't have one; the periscope (10×) is rarely a good default
     *  because its minimum focus distance is ~1 m which excludes most
     *  table-top receipt shots. */
    private fun choosePrimaryLens(lenses: List<CameraLens>): CameraLens? {
        return lenses.firstOrNull { it.kind == Lens.Telephoto }
            ?: lenses.firstOrNull { it.kind == Lens.Main }
            ?: lenses.firstOrNull()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private suspend fun bindLens(
        cameraProvider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        lens: CameraLens?,
    ) {
        if (lens != null && lens.physicalId == boundPhysicalId && imageCapture != null) return
        cameraProvider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(Int.MAX_VALUE, Int.MAX_VALUE),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                )
            )
            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
            .build()

        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(resolutionSelector)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)

        val selectorBuilder = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        if (lens != null) {
            // Physical-id binding is supported from CameraX 1.4+. Requires the
            // physical camera to be a child of an accessible logical camera,
            // which on S26 Ultra covers all 4 back lenses.
            selectorBuilder.setPhysicalCameraId(lens.physicalId)
        }
        val selector = selectorBuilder.build()

        val capture = captureBuilder.build()
        camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
        imageCapture = capture
        boundPhysicalId = lens?.physicalId

        val selectedSize = capture.resolutionInfo?.resolution
        val mp = selectedSize?.let { (it.width.toLong() * it.height) / 1_000_000.0 }
        Log.i(
            TAG,
            "Bound ${lens?.kind ?: "default"} lens (id=${lens?.physicalId}): " +
                "$selectedSize (~${mp?.let { "%.1f".format(it) } ?: "?"} MP)",
        )
    }

    private suspend fun captureOnce(): Bitmap = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resumeWithException(IllegalStateException("Camera not bound"))
            return@suspendCancellableCoroutine
        }
        capture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    cont.resume(image.toOrientedBitmap())
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

    // Keep for compatibility with anyone referencing CameraInfo — unused here.
    @Suppress("unused")
    private fun cameraInfoOrNull(): CameraInfo? = camera?.cameraInfo

    private companion object {
        const val TAG = "CameraController"
    }
}
