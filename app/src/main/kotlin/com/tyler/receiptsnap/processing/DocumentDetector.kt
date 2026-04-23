package com.tyler.receiptsnap.processing

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Finds one or more document-like quadrilaterals in a photo. Detection and
 * warping are separate so the review UI can edit the quad set before commit.
 *
 * Coordinates in returned Quads are in the *original* bitmap pixel space.
 *
 * Two filters rule out obvious non-receipts:
 *   - Frame-hugging quads (the entire photo)
 *   - Dark-interior quads (receipts are printed on white/near-white stock)
 */
object DocumentDetector {

    private const val TAG = "DocumentDetector"

    private const val WORK_MAX_SIDE = 1600
    private const val MIN_AREA_FRACTION = 0.01
    private const val MAX_AREA_FRACTION = 0.55
    private const val FRAME_MARGIN_PX = 40

    /** Minimum mean luma (0–255) for the interior of a detection to count as
     *  receipt-like. Tuned so cream/pink thermal paper still passes. */
    private const val MIN_WHITE_LUMA = 150.0

    /** Maximum chroma (max-min channel spread) for the interior to still
     *  count as near-neutral. A bright-red folder, for instance, would score
     *  high luma but huge chroma and get rejected here. */
    private const val MAX_WHITE_CHROMA = 70.0

    data class Quad(
        val id: Long,
        /** TL, TR, BR, BL in original-image pixel space. */
        val corners: List<Point>,
    ) {
        fun boundsWidth(): Double = corners.maxOf { it.x } - corners.minOf { it.x }
        fun boundsHeight(): Double = corners.maxOf { it.y } - corners.minOf { it.y }
    }

    fun detect(source: Bitmap): List<Quad> {
        val full = Mat().also { Utils.bitmapToMat(source, it) }
        try {
            return findDocuments(full, source.width, source.height)
        } finally {
            full.release()
        }
    }

    fun warp(source: Bitmap, quad: Quad): Bitmap {
        val full = Mat().also { Utils.bitmapToMat(source, it) }
        try {
            return warpDocument(full, quad.corners)
        } finally {
            full.release()
        }
    }

    /**
     * Tap-to-add: grow a document-shaped region from a user-tapped seed pixel
     * by flood-filling in pixels whose color is close to the seed's. Returns
     * null when nothing receipt-like can be grown; the caller should then
     * fall back to a placeholder rectangle.
     */
    fun growFromSeed(source: Bitmap, seedX: Double, seedY: Double, nextId: Long): Quad? {
        val full = Mat().also { Utils.bitmapToMat(source, it) }
        val work = Mat()
        val scale = scaleToWorking(full, work)
        try {
            val workW = work.cols(); val workH = work.rows()
            val workX = (seedX * scale).toInt().coerceIn(0, workW - 1)
            val workY = (seedY * scale).toInt().coerceIn(0, workH - 1)

            val bgr = Mat()
            Imgproc.cvtColor(work, bgr, Imgproc.COLOR_RGBA2BGR)

            // floodFill with FLOODFILL_MASK_ONLY writes the connected region
            // into `mask` without modifying `bgr`. `mask` must be H+2 × W+2.
            val mask = Mat.zeros(workH + 2, workW + 2, CvType.CV_8UC1)
            val loDiff = Scalar(12.0, 12.0, 12.0)
            val upDiff = Scalar(12.0, 12.0, 12.0)
            val flags = 4 or (255 shl 8) or
                Imgproc.FLOODFILL_FIXED_RANGE or Imgproc.FLOODFILL_MASK_ONLY

            Imgproc.floodFill(
                bgr, mask, Point(workX.toDouble(), workY.toDouble()),
                Scalar(255.0, 255.0, 255.0), Rect(), loDiff, upDiff, flags,
            )

            // Clean the mask a bit so small gaps inside the region (printed
            // text) don't poke holes through the final contour.
            val maskROI = mask.submat(Rect(1, 1, workW, workH)).clone()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(maskROI, maskROI, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                maskROI, contours, Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
            )

            mask.release(); maskROI.release(); kernel.release(); bgr.release()

            val minArea = workW * workH * 0.005
            val seedPointWork = Point(workX.toDouble(), workY.toDouble())

            val containing = contours.filter { c ->
                val poly = MatOfPoint2f(*c.toArray())
                Imgproc.contourArea(c) > minArea &&
                    Imgproc.pointPolygonTest(poly, seedPointWork, false) >= 0
            }.maxByOrNull { Imgproc.contourArea(it) } ?: return null

            val hull = MatOfPoint2f(*containing.toArray())
            val peri = Imgproc.arcLength(hull, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull, approx, 0.02 * peri, true)

            val cornersUsed: Array<Point> = if (approx.toArray().size == 4) {
                approx.toArray()
            } else {
                val rect = Imgproc.minAreaRect(hull)
                Array(4) { Point() }.also { rect.points(it) }
            }
            if (!isPlausibleQuad(cornersUsed)) return null

            val ordered = orderCorners(cornersUsed)
            val scaledBack = ordered.map { Point(it.x / scale, it.y / scale) }
            return Quad(id = nextId, corners = scaledBack)
        } finally {
            full.release(); work.release()
        }
    }

    private fun findDocuments(full: Mat, imgW: Int, imgH: Int): List<Quad> {
        val work = Mat()
        val scale = scaleToWorking(full, work)

        val gray = Mat()
        Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)

        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(blurred, edges, 40.0, 120.0)

        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val totalArea = work.size().width * work.size().height
        val minArea = totalArea * MIN_AREA_FRACTION
        val maxArea = totalArea * MAX_AREA_FRACTION

        val candidates = mutableListOf<Quad>()
        var nextId = 1L

        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < minArea || area > maxArea) continue

            val hull = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(hull, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull, approx, 0.02 * peri, true)

            val corners = approx.toArray()
            val cornersUsed: Array<Point> = if (corners.size == 4) corners else {
                val rect = Imgproc.minAreaRect(hull)
                if (rect.size.width * rect.size.height < minArea) continue
                Array(4) { Point() }.also { rect.points(it) }
            }

            if (!isPlausibleQuad(cornersUsed)) continue

            val ordered = orderCorners(cornersUsed)
            val scaledBack = ordered.map { Point(it.x / scale, it.y / scale) }

            if (isFullFrame(scaledBack, imgW, imgH)) continue
            if (!hasLightInterior(full, scaledBack)) continue

            candidates += Quad(id = nextId++, corners = scaledBack)
        }

        gray.release(); blurred.release(); edges.release(); kernel.release(); work.release()

        return deduplicate(candidates).sortedWith(readingOrder())
    }

    /**
     * Samples a 3×3 grid of patches inside the quad's bounding box and requires
     * the median patch to be bright and near-neutral. This rejects dark
     * surfaces (table, book cover) while tolerating cream/pink receipt stock
     * and the ink used for print.
     */
    private fun hasLightInterior(full: Mat, corners: List<Point>): Boolean {
        val minX = corners.minOf { it.x }; val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }; val maxY = corners.maxOf { it.y }
        val w = (maxX - minX); val h = (maxY - minY)
        if (w < 20 || h < 20) return false

        // Shrink the sampling window a touch so edges (which may include the
        // table color just outside a rotated receipt) don't pollute the mean.
        val insetX = w * 0.1; val insetY = h * 0.1
        val roiX = (minX + insetX).toInt().coerceIn(0, full.cols() - 1)
        val roiY = (minY + insetY).toInt().coerceIn(0, full.rows() - 1)
        val roiW = (w - 2 * insetX).toInt().coerceAtLeast(30)
            .coerceAtMost(full.cols() - roiX)
        val roiH = (h - 2 * insetY).toInt().coerceAtLeast(30)
            .coerceAtMost(full.rows() - roiY)

        if (roiW < 30 || roiH < 30) return false

        val lumas = mutableListOf<Double>()
        val chromas = mutableListOf<Double>()
        val patch = 20
        for (row in 0..2) for (col in 0..2) {
            val px = roiX + (col + 1) * roiW / 4 - patch / 2
            val py = roiY + (row + 1) * roiH / 4 - patch / 2
            val pxC = px.coerceIn(0, full.cols() - patch - 1)
            val pyC = py.coerceIn(0, full.rows() - patch - 1)
            val sample = full.submat(Rect(pxC, pyC, patch, patch))
            val mean = Core.mean(sample)
            val r = mean.`val`[0]; val g = mean.`val`[1]; val b = mean.`val`[2]
            lumas += 0.299 * r + 0.587 * g + 0.114 * b
            chromas += maxOf(r, g, b) - minOf(r, g, b)
            sample.release()
        }
        lumas.sort(); chromas.sort()
        val medianLuma = lumas[4]
        val medianChroma = chromas[4]
        val ok = medianLuma >= MIN_WHITE_LUMA && medianChroma <= MAX_WHITE_CHROMA
        if (!ok) Log.v(TAG, "Rejected quad: luma=$medianLuma chroma=$medianChroma")
        return ok
    }

    private fun isFullFrame(corners: List<Point>, imgW: Int, imgH: Int): Boolean {
        val minX = corners.minOf { it.x }
        val maxX = corners.maxOf { it.x }
        val minY = corners.minOf { it.y }
        val maxY = corners.maxOf { it.y }
        return minX < FRAME_MARGIN_PX &&
            minY < FRAME_MARGIN_PX &&
            maxX > imgW - FRAME_MARGIN_PX &&
            maxY > imgH - FRAME_MARGIN_PX
    }

    private fun readingOrder(): Comparator<Quad> = Comparator { a, b ->
        val ay = a.corners.minOf { it.y }
        val by = b.corners.minOf { it.y }
        val rowTol = 200.0
        if (kotlin.math.abs(ay - by) > rowTol) ay.compareTo(by)
        else a.corners.minOf { it.x }.compareTo(b.corners.minOf { it.x })
    }

    private fun warpDocument(full: Mat, corners: List<Point>): Bitmap {
        val src = MatOfPoint2f(*corners.toTypedArray())

        val widthTop = distance(corners[0], corners[1])
        val widthBottom = distance(corners[3], corners[2])
        val heightLeft = distance(corners[0], corners[3])
        val heightRight = distance(corners[1], corners[2])

        val outW = maxOf(widthTop, widthBottom).toInt().coerceAtLeast(64)
        val outH = maxOf(heightLeft, heightRight).toInt().coerceAtLeast(64)

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point((outW - 1).toDouble(), 0.0),
            Point((outW - 1).toDouble(), (outH - 1).toDouble()),
            Point(0.0, (outH - 1).toDouble()),
        )

        val transform = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat(outH, outW, full.type())
        Imgproc.warpPerspective(
            full, warped, transform, Size(outW.toDouble(), outH.toDouble()),
            Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(0.0),
        )

        val sharpened = unsharpMask(warped)
        warped.release(); transform.release(); src.release(); dst.release()

        val rgb = Mat()
        Imgproc.cvtColor(sharpened, rgb, Imgproc.COLOR_RGBA2RGB)
        sharpened.release()

        val bitmap = Bitmap.createBitmap(rgb.cols(), rgb.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgb, bitmap)
        rgb.release()
        return bitmap
    }

    private fun unsharpMask(src: Mat): Mat {
        val blur = Mat()
        Imgproc.GaussianBlur(src, blur, Size(0.0, 0.0), 3.0)
        val out = Mat()
        Core.addWeighted(src, 1.5, blur, -0.5, 0.0, out)
        blur.release()
        return out
    }

    private fun scaleToWorking(full: Mat, outWork: Mat): Double {
        val maxSide = maxOf(full.cols(), full.rows())
        if (maxSide <= WORK_MAX_SIDE) {
            full.copyTo(outWork)
            return 1.0
        }
        val scale = WORK_MAX_SIDE.toDouble() / maxSide
        Imgproc.resize(full, outWork, Size(), scale, scale, Imgproc.INTER_AREA)
        return scale
    }

    private fun distance(a: Point, b: Point): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun orderCorners(pts: Array<Point>): List<Point> {
        val bySum = pts.sortedBy { it.x + it.y }
        val byDiag = pts.sortedBy { it.x - it.y }
        return listOf(bySum.first(), byDiag.last(), bySum.last(), byDiag.first())
    }

    private fun isPlausibleQuad(pts: Array<Point>): Boolean {
        if (pts.size != 4) return false
        val ordered = orderCorners(pts)
        val w = (distance(ordered[0], ordered[1]) + distance(ordered[3], ordered[2])) / 2.0
        val h = (distance(ordered[0], ordered[3]) + distance(ordered[1], ordered[2])) / 2.0
        if (w < 40 || h < 40) return false
        val aspect = maxOf(w, h) / minOf(w, h)
        return aspect in 1.0..12.0
    }

    private fun deduplicate(input: List<Quad>): List<Quad> {
        val sorted = input.sortedByDescending {
            val w = it.boundsWidth(); val h = it.boundsHeight(); w * h
        }
        val kept = mutableListOf<Quad>()
        for (d in sorted) {
            val r = boundingRect(d.corners)
            val overlaps = kept.any { iou(r, boundingRect(it.corners)) > 0.3 }
            if (!overlaps) kept += d
        }
        return kept
    }

    private fun boundingRect(pts: List<Point>): Rect {
        val xs = pts.map { it.x }; val ys = pts.map { it.y }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        return Rect(minX.toInt(), minY.toInt(), (maxX - minX).toInt(), (maxY - minY).toInt())
    }

    private fun iou(a: Rect, b: Rect): Double {
        val xA = maxOf(a.x, b.x)
        val yA = maxOf(a.y, b.y)
        val xB = minOf(a.x + a.width, b.x + b.width)
        val yB = minOf(a.y + a.height, b.y + b.height)
        val inter = maxOf(0, xB - xA).toLong() * maxOf(0, yB - yA).toLong()
        val union = a.width.toLong() * a.height + b.width.toLong() * b.height - inter
        return if (union <= 0) 0.0 else inter.toDouble() / union
    }
}
