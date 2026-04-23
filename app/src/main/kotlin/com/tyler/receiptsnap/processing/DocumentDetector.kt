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
 * Detects one or more document-like quadrilaterals in a photo and returns each
 * perspective-corrected as a separate Bitmap. Designed for receipts laid out
 * on a (roughly) uniform surface.
 */
object DocumentDetector {

    private const val TAG = "DocumentDetector"

    /** Max side length for the working/analysis image. Detection runs here, but the
     *  final perspective warp uses the original full-resolution Mat to keep quality. */
    private const val WORK_MAX_SIDE = 1600

    /** Reject contours with area below this fraction of the full frame. Filters UI
     *  clutter, stray specks, and tiny printed artifacts. */
    private const val MIN_AREA_FRACTION = 0.01

    /** Reject contours that cover most of the frame — those are almost certainly
     *  the whole surface, not an individual document. */
    private const val MAX_AREA_FRACTION = 0.90

    data class Detection(
        /** Corners in the original (full-resolution) image coordinate space. */
        val corners: List<Point>,
        val areaPx: Double,
    )

    /** Full pipeline: find docs, warp each, return bitmaps in reading order. */
    fun detectAndExtract(source: Bitmap): List<Bitmap> {
        val full = Mat().also { Utils.bitmapToMat(source, it) }
        try {
            val detections = findDocuments(full)
            Log.i(TAG, "Detected ${detections.size} document(s)")
            return detections.map { warpDocument(full, it.corners) }
        } finally {
            full.release()
        }
    }

    private fun findDocuments(full: Mat): List<Detection> {
        val work = Mat()
        val scale = scaleToWorking(full, work)

        val gray = Mat()
        Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)

        // Slight blur to kill print noise without blowing out paper edges.
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)

        // Canny params tuned for white paper against a varied surface. Tight
        // enough to reject shadows, loose enough to keep the full receipt border.
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 40.0, 120.0)

        // Close small gaps in the edge map so a slightly broken receipt border
        // still yields a closed contour.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel, Point(-1.0, -1.0), 2)

        val contours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE,
        )

        val totalArea = work.size().area()
        val minArea = totalArea * MIN_AREA_FRACTION
        val maxArea = totalArea * MAX_AREA_FRACTION

        val candidates = mutableListOf<Detection>()

        for (c in contours) {
            val area = Imgproc.contourArea(c)
            if (area < minArea || area > maxArea) continue

            val hull = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(hull, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull, approx, 0.02 * peri, true)

            val corners = approx.toArray()
            val cornersUsed: Array<Point> = if (corners.size == 4) {
                corners
            } else {
                // Fallback: use min-area rect. Useful for receipts whose long
                // edge is slightly curved and fails the 4-point simplification.
                val rect = Imgproc.minAreaRect(hull)
                val pts = Array(4) { Point() }
                rect.points(pts)
                if (rect.size.area() < minArea) continue
                pts
            }

            if (!isPlausibleQuad(cornersUsed)) continue

            val ordered = orderCorners(cornersUsed)
            val scaledBack = ordered.map { Point(it.x / scale, it.y / scale) }
            candidates += Detection(scaledBack, area / (scale * scale))
        }

        gray.release(); blurred.release(); edges.release(); kernel.release(); work.release()

        return deduplicate(candidates).sortedWith(readingOrder())
    }

    /** Reading order: top-to-bottom, then left-to-right, using quadrant bands
     *  so a slightly-higher receipt doesn't jump the row. */
    private fun readingOrder(): Comparator<Detection> = Comparator { a, b ->
        val ay = a.corners.minOf { it.y }
        val by = b.corners.minOf { it.y }
        val rowTol = 200.0 // pixels — receipts more than this apart are different rows
        if (kotlin.math.abs(ay - by) > rowTol) ay.compareTo(by)
        else a.corners.minOf { it.x }.compareTo(b.corners.minOf { it.x })
    }

    /** Warp a quadrilateral region to an upright rectangle at native resolution. */
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

        // Mild unsharp mask improves OCR on receipts without going nuclear.
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

    /** Order 4 points as: top-left, top-right, bottom-right, bottom-left. */
    private fun orderCorners(pts: Array<Point>): List<Point> {
        val sorted = pts.sortedBy { it.x + it.y }
        val tl = sorted.first()
        val br = sorted.last()
        val diagSorted = pts.sortedBy { it.x - it.y }
        val bl = diagSorted.first()
        val tr = diagSorted.last()
        return listOf(tl, tr, br, bl)
    }

    /** Reject shapes that couldn't be a receipt: too thin, non-convex, bad
     *  aspect. Receipts are typically 1:2 — 1:6 but we allow 1:10 for safety. */
    private fun isPlausibleQuad(pts: Array<Point>): Boolean {
        if (pts.size != 4) return false
        val ordered = orderCorners(pts)
        val w = (distance(ordered[0], ordered[1]) + distance(ordered[3], ordered[2])) / 2.0
        val h = (distance(ordered[0], ordered[3]) + distance(ordered[1], ordered[2])) / 2.0
        if (w < 40 || h < 40) return false
        val aspect = maxOf(w, h) / minOf(w, h)
        return aspect in 1.0..12.0
    }

    /** Remove detections whose bounding boxes overlap heavily. Keeps the larger. */
    private fun deduplicate(input: List<Detection>): List<Detection> {
        val sorted = input.sortedByDescending { it.areaPx }
        val kept = mutableListOf<Detection>()
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

private fun Size.area(): Double = width * height
