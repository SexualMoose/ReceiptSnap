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
import androidx.camera.core.ImageAnalysis
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
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(
        com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS
    )
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var provider: ProcessCameraProvider? = null
    private var boundPhysicalId: String? = null

    /** Live preview-frame quality signal: text-line count + frame luma.
     *  CameraScreen renders a small badge from this so the user can tell
     *  whether the camera is seeing enough of the receipt before they
     *  pull the trigger on the (slow) multi-camera capture. */
    enum class QualityLevel { Poor, Fair, Good }

    data class PreviewQuality(
        val textLineCount: Int = 0,
        val luma: Int = 0,         // 0..255
        val level: QualityLevel = QualityLevel.Poor,
    )

    private val _previewQuality = kotlinx.coroutines.flow.MutableStateFlow(PreviewQuality())
    val previewQuality: kotlinx.coroutines.flow.StateFlow<PreviewQuality> = _previewQuality

    /**
     * Best-yet metadata observed across preview frames during this session.
     * Updated by the same analyzer that powers [previewQuality] — every
     * preview frame with an OCR result feeds its detected date / total /
     * location into this cache, keeping the most recent non-null value.
     *
     * The send-time fallback (in MainViewModel.commitReview) consults this
     * when a per-receipt warp+OCR misses a field. Only applied when the
     * captured frame contains a single receipt — multi-receipt frames
     * could mix metadata across receipts otherwise.
     */
    data class FrameMetadata(
        val date: java.time.LocalDate? = null,
        val total: com.tyler.receiptsnap.processing.ReceiptParser.Total? = null,
        val location: String? = null,
        val updatedMs: Long = 0L,
    )

    private val _bestMetadata = kotlinx.coroutines.flow.MutableStateFlow(FrameMetadata())
    val bestMetadata: kotlinx.coroutines.flow.StateFlow<FrameMetadata> = _bestMetadata

    /** Reset the metadata cache. Call after a capture is fully committed
     *  so the next session starts from scratch. */
    fun resetMetadataCache() {
        _bestMetadata.value = FrameMetadata()
    }

    /** Throttle: ML Kit calls are ~150-300 ms each. Skip frames in between
     *  so the analyzer doesn't pile up. */
    @Volatile private var lastAnalysisAtMs = 0L
    private val ANALYSIS_INTERVAL_MS = 750L

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

        // Primary — burst-of-3 with sharpest pick. Preview is already
        // bound to this lens. Burst on secondary lenses isn't worth the
        // ~2× rebind overhead per lens; the primary is what matters most.
        onProgress?.invoke(0, total, "${primary.kind.name} (burst)")
        runCatching { captureBurst(PRIMARY_BURST_COUNT) }
            .onSuccess { frames += CapturedFrame(it, primary) }
            .onFailure { Log.e(TAG, "Primary burst capture failed on ${primary.kind}", it) }

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

    /**
     * Burst-of-N capture: fire [count] takePicture calls in quick
     * succession, score each by Laplacian-variance sharpness, return the
     * sharpest frame. Other frames are recycled. Substantially better
     * than a single shot for receipts because hand jitter and auto-focus
     * walk produce one or two frames noticeably crisper than the rest.
     */
    suspend fun captureBurst(count: Int): Bitmap {
        if (count <= 1) return captureOnce()
        val frames = mutableListOf<Bitmap>()
        repeat(count) {
            try {
                frames += captureOnce()
            } catch (t: Throwable) {
                Log.w(TAG, "burst frame failed", t)
            }
        }
        if (frames.isEmpty()) error("All burst frames failed")
        if (frames.size == 1) return frames.single()

        val scored = frames.map { it to sharpnessVariance(it) }
        val best = scored.maxBy { it.second }
        Log.i(
            TAG,
            "Burst of ${frames.size}: scores=${scored.map { "%.0f".format(it.second) }} → " +
                "winner ${best.first.width}×${best.first.height}",
        )
        for ((frame, _) in scored) if (frame !== best.first) frame.recycle()
        return best.first
    }

    /** Variance of the Laplacian on a downscaled grayscale copy — the
     *  standard "no-reference" sharpness metric. Higher = sharper. We
     *  downscale to ~800 px max side first because the metric works fine
     *  at that resolution and a 12.5 MP Laplacian costs ~1 second. */
    private fun sharpnessVariance(bitmap: Bitmap): Double {
        val maxSide = 800
        val maxDim = maxOf(bitmap.width, bitmap.height)
        val scale = if (maxDim > maxSide) maxSide.toDouble() / maxDim else 1.0
        val small = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt(),
                (bitmap.height * scale).toInt(),
                true,
            )
        } else bitmap
        return try {
            val mat = org.opencv.core.Mat()
            org.opencv.android.Utils.bitmapToMat(small, mat)
            val gray = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.cvtColor(mat, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
            val lap = org.opencv.core.Mat()
            org.opencv.imgproc.Imgproc.Laplacian(gray, lap, org.opencv.core.CvType.CV_64F)
            val mean = org.opencv.core.MatOfDouble()
            val stddev = org.opencv.core.MatOfDouble()
            org.opencv.core.Core.meanStdDev(lap, mean, stddev)
            val sigma = stddev.toArray().firstOrNull() ?: 0.0
            mat.release(); gray.release(); lap.release()
            mean.release(); stddev.release()
            sigma * sigma
        } catch (t: Throwable) {
            Log.w(TAG, "sharpness scoring failed; treating as 0", t)
            0.0
        } finally {
            if (small !== bitmap) small.recycle()
        }
    }

    fun release() {
        executor.shutdown()
        analysisExecutor.shutdown()
        camera = null
        imageCapture = null
        provider = null
        boundPhysicalId = null
    }

    /** Analyzer hook: every ANALYSIS_INTERVAL_MS we run ML Kit on the
     *  latest preview frame and update [previewQuality]. Frames in between
     *  are released immediately to keep latency low. The analyzer MUST
     *  close the proxy exactly once per call or CameraX will stop feeding
     *  frames; every code path in here handles that. */
    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisAtMs < ANALYSIS_INTERVAL_MS) {
            proxy.close(); return
        }
        lastAnalysisAtMs = now

        val mediaImage = proxy.image
        if (mediaImage == null) { proxy.close(); return }

        try {
            // Mean luma from the Y plane — single-byte stride, 4096 sample
            // points across the buffer is enough to detect a pitch-black
            // or blown-out frame without scanning everything.
            val yPlane = mediaImage.planes[0]
            val buffer = yPlane.buffer
            val sampleCount = 4096
            val step = (buffer.remaining() / sampleCount).coerceAtLeast(1)
            var lumaSum = 0L
            var taken = 0
            var i = 0
            while (i < buffer.remaining()) {
                lumaSum += (buffer.get(i).toInt() and 0xFF); taken++; i += step
            }
            val luma = if (taken > 0) (lumaSum / taken).toInt() else 0

            val mlImage = com.google.mlkit.vision.common.InputImage.fromMediaImage(
                mediaImage, proxy.imageInfo.rotationDegrees,
            )
            recognizer.process(mlImage)
                .addOnSuccessListener { result ->
                    val lineCount = result.textBlocks.sumOf { it.lines.size }
                    val level = when {
                        lineCount >= 10 && luma in 80..230 -> QualityLevel.Good
                        lineCount >= 3 -> QualityLevel.Fair
                        else -> QualityLevel.Poor
                    }
                    _previewQuality.value = PreviewQuality(lineCount, luma, level)

                    // Feed the metadata cache so capture-time fallback has
                    // a chance even when the captured frame's per-receipt
                    // OCR misses a field. Only update fields with new
                    // non-null observations — never overwrite a real value
                    // with null.
                    if (lineCount > 0) {
                        val text = result.text
                        val date = com.tyler.receiptsnap.processing.ReceiptParser.detectDateInText(text)
                        val total = com.tyler.receiptsnap.processing.ReceiptParser.detectTotal(text)
                        val location = com.tyler.receiptsnap.processing.AddressParser.extractLocation(text)
                        val current = _bestMetadata.value
                        if (date != null || total != null || location != null) {
                            _bestMetadata.value = FrameMetadata(
                                date = date ?: current.date,
                                total = total ?: current.total,
                                location = location ?: current.location,
                                updatedMs = System.currentTimeMillis(),
                            )
                        }
                    }
                }
                .addOnFailureListener { /* keep prior reading on transient errors */ }
                .addOnCompleteListener { proxy.close() }
        } catch (t: Throwable) {
            Log.w(TAG, "preview analyze frame failed", t)
            proxy.close()
        }
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

        // ImageAnalysis: low-res preview-frame stream we run ML Kit and a
        // luma sample on. KEEP_ONLY_LATEST means the analyzer never falls
        // behind — if we're slow on a frame, the next one in flight is
        // dropped. Output 1280×720-ish is plenty for a "is there text?"
        // check and keeps OCR fast.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                        )
                    )
                    .build()
            )
            .build()
            .apply { setAnalyzer(analysisExecutor, ::analyzeFrame) }

        camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, capture, analysis)
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

        /** Burst size for the primary lens. 3 hits the sweet spot — most
         *  hand-jitter variance is captured, and the extra ~1 s of capture
         *  time is a fair trade for noticeably sharper frames. Going to 5
         *  rarely produces a winner the top-3 didn't already include. */
        const val PRIMARY_BURST_COUNT = 3
    }
}
