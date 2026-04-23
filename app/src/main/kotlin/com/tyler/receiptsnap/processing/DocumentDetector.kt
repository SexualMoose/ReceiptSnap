package com.tyler.receiptsnap.processing

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import org.opencv.core.Point as CvPoint

/**
 * Multi-receipt detector. Two pipelines run in sequence:
 *
 *   Primary — text-driven: OCR the photo; single-link-cluster the
 *   recognized lines by orientation, spatial proximity, and line height;
 *   return a padded oriented bounding box per cluster.
 *
 *   Fallback — classical: when OCR yields nothing usable, run an adaptive
 *   threshold + morphology + connected-components pass that finds
 *   light-colored rectangular regions against a darker surface.
 *
 * The review UI lets the user remove false positives and add anything the
 * detector missed, so detection errs toward *recall* — we don't gate on
 * strict keyword/date patterns here (receipts OCR poorly in ways that would
 * create too many false negatives). Soft scoring is used only to order.
 */
object DocumentDetector {

    private const val TAG = "DocumentDetector"

    /**
     * Max long-side for OCR. ML Kit wants ≥16 px per character. A 200 MP
     * capture whose receipt fills 15% of the frame has text around 40–60 px
     * native; at 4000 px work-size that stays well above ML Kit's floor.
     * 200 MP is ~16 k × 12 k, so 4000 is a 0.25× scale — memory cost
     * ~64 MB for the scaled ARGB bitmap, tolerable on S26 Ultra.
     */
    private const val WORK_MAX_SIDE_OCR = 4000
    private const val WORK_MAX_SIDE_EDGES = 1600

    // Linking tolerances for single-link clustering. Loose enough that a
    // receipt with blank sections and slight baseline curl stays one cluster.
    private const val ANGLE_TOLERANCE_RAD = 10.0 * PI / 180.0
    private const val LINE_HEIGHT_RATIO_LIMIT = 3.0
    private const val PERP_GAP_FACTOR = 6.0   // across-line spacing
    private const val ALONG_GAP_FACTOR = 6.0  // along-line spacing
    private const val MIN_LINES_PER_CLUSTER = 2
    private const val PAD_FACTOR = 1.5

    // Post-merge tolerances for combining adjacent clusters into one receipt.
    private const val MERGE_ANGLE_TOLERANCE_RAD = 15.0 * PI / 180.0
    private const val MERGE_GAP_FACTOR = 4.0  // permitted gap in units of median line height

    private const val MIN_WHITE_LUMA = 115.0
    private const val MAX_WHITE_CHROMA = 95.0

    /** Keywords whose presence on a receipt is required for it to be treated
     *  as a receipt (alongside at least one date). Expanded to cover common
     *  card-payment vocabulary so receipts without an explicit MERCHANT or
     *  AUTH line still qualify. */
    private val RECEIPT_KEYWORDS = listOf(
        "MERCHANT", "CARDHOLDER", "AUTH", "RESTAURANT",
        "RECEIPT", "PAYMENT", "VISA", "MASTERCARD", "CHARGE",
    )

    /** Phone numbers count as a "phone" marker whether the literal word is
     *  present or not — most receipts just print the number. */
    private val PHONE_NUMBER = Regex("\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}")

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Quad(
        val id: Long,
        /** TL, TR, BR, BL in original-image pixel space, oriented to the
         *  cluster's dominant text angle. */
        val corners: List<CvPoint>,
    )

    /** Diagnostic counters from the last detection pass. Picked up by the
     *  review UI so the user can see what the detector saw. */
    data class DetectionStats(
        val textLines: Int,
        val clusters: Int,
        val fromText: Int,
        val fromEdges: Int,
    )

    @Volatile var lastStats: DetectionStats = DetectionStats(0, 0, 0, 0)
        private set

    suspend fun detect(source: Bitmap): List<Quad> {
        val lines = recognizeLines(source)
        Log.i(TAG, "OCR returned ${lines.size} text lines")

        val initialClusters = if (lines.size >= MIN_LINES_PER_CLUSTER) clusterLines(lines) else emptyList()
        val mergedClusters = mergeAdjacentClusters(initialClusters)
        Log.i(TAG, "Clusters: ${initialClusters.size} → ${mergedClusters.size} after merge")

        val receiptClusters = mergedClusters.filter(::isReceiptCluster)
        Log.i(TAG, "${receiptClusters.size} cluster(s) passed date+keyword gate")

        val textQuads = if (receiptClusters.isNotEmpty()) {
            val full = Mat().also { Utils.bitmapToMat(source, it) }
            try {
                var id = 1L
                receiptClusters
                    .map { clusterToQuad(it, id++) }
                    .filter { hasLightInterior(full, it.corners) }
            } finally {
                full.release()
            }
        } else emptyList()

        val fromEdgesQuads = if (textQuads.isEmpty()) {
            Log.i(TAG, "Text-driven detection empty — trying classical fallback")
            detectFromEdges(source, startId = 1L)
        } else emptyList()

        val result = (textQuads + fromEdgesQuads).distinct()
        lastStats = DetectionStats(
            textLines = lines.size,
            clusters = mergedClusters.size,
            fromText = textQuads.size,
            fromEdges = fromEdgesQuads.size,
        )
        Log.i(TAG, "Final stats: $lastStats")
        return result
    }

    suspend fun growFromSeed(source: Bitmap, seedX: Double, seedY: Double, nextId: Long): Quad? {
        val lines = recognizeLines(source)
        if (lines.isEmpty()) return null

        val diag = sqrt(source.width.toDouble() * source.width + source.height.toDouble() * source.height)
        val seedPoint = CvPoint(seedX, seedY)
        val nearest = lines.minByOrNull { distance(it.center, seedPoint) } ?: return null
        if (distance(nearest.center, seedPoint) > diag * 0.18) return null

        // BFS grow: start with `nearest`; pull in any line compatible with the
        // growing frontier's median angle/height. Lets the user add a missed
        // receipt by tapping anywhere inside its text block.
        val members = mutableListOf(nearest)
        val remaining = lines.toMutableSet().also { it.remove(nearest) }
        var changed = true
        while (changed) {
            changed = false
            val mAngle = median(members.map { it.angleRad })
            val mHeight = median(members.map { it.height })
            val iter = remaining.iterator()
            while (iter.hasNext()) {
                val candidate = iter.next()
                val attachesTo = members.any { other ->
                    compatible(candidate, other, mAngle, mHeight)
                }
                if (attachesTo) {
                    members += candidate
                    iter.remove()
                    changed = true
                }
            }
        }
        if (members.size < MIN_LINES_PER_CLUSTER) {
            // Return a minimal starter box around the single line rather than
            // nothing — the user has handles to adjust.
            val cluster = Cluster(listOf(nearest), nearest.angleRad, nearest.height)
            return clusterToQuad(cluster, nextId)
        }
        return clusterToQuad(buildCluster(members), nextId)
    }

    fun warp(source: Bitmap, quad: Quad): Bitmap {
        val full = Mat().also { Utils.bitmapToMat(source, it) }
        try {
            return warpDocument(full, quad.corners)
        } finally {
            full.release()
        }
    }

    // --- text-driven pipeline ----------------------------------------------

    private data class LineInfo(
        val text: String,
        val corners: List<CvPoint>, // TL, TR, BR, BL in source image coords
        val center: CvPoint,
        val angleRad: Double,
        val height: Double,
        val width: Double,
    )

    private data class Cluster(
        val lines: List<LineInfo>,
        val medianAngleRad: Double,
        val medianHeight: Double,
    )

    private suspend fun recognizeLines(source: Bitmap): List<LineInfo> {
        val maxSide = max(source.width, source.height)
        val scale: Double = if (maxSide > WORK_MAX_SIDE_OCR) WORK_MAX_SIDE_OCR.toDouble() / maxSide else 1.0
        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                true,
            )
        } else source
        Log.i(TAG, "OCR input ${scaled.width}×${scaled.height} (scale=${"%.3f".format(scale)})")

        val text = runOcr(scaled)
        if (scaled !== source) scaled.recycle()

        val invScale = 1.0 / scale
        val out = mutableListOf<LineInfo>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val cp = line.cornerPoints ?: continue
                if (cp.size != 4) continue
                val tl = CvPoint(cp[0].x * invScale, cp[0].y * invScale)
                val tr = CvPoint(cp[1].x * invScale, cp[1].y * invScale)
                val br = CvPoint(cp[2].x * invScale, cp[2].y * invScale)
                val bl = CvPoint(cp[3].x * invScale, cp[3].y * invScale)
                val angle = atan2(tr.y - tl.y, tr.x - tl.x)
                val height = (distance(tl, bl) + distance(tr, br)) / 2.0
                val width = (distance(tl, tr) + distance(bl, br)) / 2.0
                if (height < 6.0) continue
                val center = CvPoint(
                    (tl.x + tr.x + br.x + bl.x) / 4.0,
                    (tl.y + tr.y + br.y + bl.y) / 4.0,
                )
                out += LineInfo(line.text, listOf(tl, tr, br, bl), center, angle, height, width)
            }
        }
        return out
    }

    private suspend fun runOcr(bitmap: Bitmap): Text = suspendCancellableCoroutine { cont ->
        val img = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(img)
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /**
     * Single-link clustering over all lines — no angle buckets. Two lines are
     * linked when they share orientation (within ANGLE_TOLERANCE), similar
     * line height (within LINE_HEIGHT_RATIO_LIMIT), and are spatially close
     * in the rotated local frame (PERP_GAP_FACTOR across text, ALONG_GAP_FACTOR
     * along text). Connected components become clusters.
     */
    private fun clusterLines(lines: List<LineInfo>): List<Cluster> {
        val n = lines.size
        if (n < MIN_LINES_PER_CLUSTER) return emptyList()

        val adj = Array(n) { mutableListOf<Int>() }
        for (i in 0 until n) for (j in i + 1 until n) {
            val mAngle = averageAngle(lines[i].angleRad, lines[j].angleRad)
            val mHeight = (lines[i].height + lines[j].height) / 2.0
            if (compatible(lines[i], lines[j], mAngle, mHeight)) {
                adj[i] += j
                adj[j] += i
            }
        }

        val visited = BooleanArray(n)
        val components = mutableListOf<MutableList<Int>>()
        for (start in 0 until n) {
            if (visited[start]) continue
            val comp = mutableListOf<Int>()
            val queue = ArrayDeque<Int>().apply { add(start) }
            visited[start] = true
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst()
                comp += v
                for (u in adj[v]) if (!visited[u]) {
                    visited[u] = true
                    queue += u
                }
            }
            components += comp
        }

        return components
            .filter { it.size >= MIN_LINES_PER_CLUSTER }
            .map { idx -> buildCluster(idx.map { lines[it] }) }
    }

    private fun compatible(
        a: LineInfo, b: LineInfo, medianAngle: Double, medianHeight: Double,
    ): Boolean {
        if (angleDistance(a.angleRad, b.angleRad) > ANGLE_TOLERANCE_RAD) return false
        val hRatio = max(a.height, b.height) / min(a.height, b.height)
        if (hRatio > LINE_HEIGHT_RATIO_LIMIT) return false

        val perp = perpDistance(a.center, b.center, medianAngle)
        val along = alongDistance(a.center, b.center, medianAngle)

        // Across-lines: typical receipt leading is ~1.2× height; allow 2.5×
        // to tolerate blank lines between sections.
        if (perp > PERP_GAP_FACTOR * medianHeight) return false
        // Along-line: two pieces of text on the same physical line sometimes
        // get split by OCR. Allow their centers to be far apart but not
        // arbitrarily so — bounded by mean-width + ALONG_GAP_FACTOR·height.
        val meanWidth = (a.width + b.width) / 2.0
        if (along > meanWidth + ALONG_GAP_FACTOR * medianHeight) return false
        return true
    }

    private fun buildCluster(members: List<LineInfo>): Cluster =
        Cluster(members, median(members.map { it.angleRad }), median(members.map { it.height }))

    /**
     * Agglomerative merge over the micro-clusters produced by single-link.
     * Small clusters that belong to the same receipt (but were split by a
     * blank section or a slightly rotated mid-receipt paragraph) get
     * re-joined by their oriented bounding boxes: if the local-frame
     * rectangles overlap or are within MERGE_GAP_FACTOR × median line
     * height of each other and the cluster angles are close, combine.
     */
    private fun mergeAdjacentClusters(clusters: List<Cluster>): List<Cluster> {
        if (clusters.size <= 1) return clusters
        val pool = clusters.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in pool.indices) {
                for (j in i + 1 until pool.size) {
                    if (canMerge(pool[i], pool[j])) {
                        val merged = buildCluster(pool[i].lines + pool[j].lines)
                        // Remove higher index first so the lower one is still valid.
                        pool.removeAt(j)
                        pool.removeAt(i)
                        pool += merged
                        changed = true
                        break@outer
                    }
                }
            }
        }
        return pool
    }

    private fun canMerge(a: Cluster, b: Cluster): Boolean {
        // Guardrail: if both clusters *independently* look like receipts
        // (each has a date AND a keyword hit), they're almost certainly
        // two separate receipts that happen to sit near each other. Merging
        // would silently collapse them. Leave them apart.
        if (isReceiptCluster(a) && isReceiptCluster(b)) return false

        if (angleDistance(a.medianAngleRad, b.medianAngleRad) > MERGE_ANGLE_TOLERANCE_RAD) return false
        val mergeAngle = averageAngle(a.medianAngleRad, b.medianAngleRad)
        val aLoc = localExtents(a.lines.flatMap { it.corners }, mergeAngle)
        val bLoc = localExtents(b.lines.flatMap { it.corners }, mergeAngle)
        val gap = maxOf(a.medianHeight, b.medianHeight) * MERGE_GAP_FACTOR

        val uOverlap = intervalsTouch(aLoc.uMin, aLoc.uMax, bLoc.uMin, bLoc.uMax, gap)
        val vOverlap = intervalsTouch(aLoc.vMin, aLoc.vMax, bLoc.vMin, bLoc.vMax, gap)
        return uOverlap && vOverlap
    }

    private data class LocalExtents(val uMin: Double, val uMax: Double, val vMin: Double, val vMax: Double)

    private fun localExtents(points: List<CvPoint>, angle: Double): LocalExtents {
        val c = cos(-angle); val s = sin(-angle)
        val us = points.map { it.x * c - it.y * s }
        val vs = points.map { it.x * s + it.y * c }
        return LocalExtents(us.min(), us.max(), vs.min(), vs.max())
    }

    private fun intervalsTouch(a0: Double, a1: Double, b0: Double, b1: Double, gap: Double): Boolean {
        // Overlap or within gap.
        return !(a1 + gap < b0 || b1 + gap < a0)
    }

    /** Receipt validity: at least one date AND at least one required keyword
     *  (receipt/merchant/cardholder/auth/restaurant/payment/visa/mastercard/
     *  charge) OR a phone number. Fuzzy keyword matching tolerates common
     *  OCR errors — printed receipts frequently have one-character
     *  substitutions (e.g., "MERCHAN1" for "MERCHANT"). */
    private fun isReceiptCluster(cluster: Cluster): Boolean {
        val joined = cluster.lines.joinToString("\n") { it.text }
        return countReceiptSignals(joined) >= 1 &&
            ReceiptParser.allDatesInText(joined).isNotEmpty()
    }

    /** Counts distinct receipt-signal hits in `text`. Used both by the gate
     *  and by the merge-guard: if two clusters each hit ≥ 1 signal, they
     *  probably represent two separate receipts and should NOT merge. */
    private fun countReceiptSignals(text: String): Int {
        val words = Regex("[A-Za-z]+").findAll(text.uppercase()).map { it.value }.toList()
        var hits = 0
        val found = mutableSetOf<String>()
        for (w in words) {
            for (kw in RECEIPT_KEYWORDS) {
                if (kw in found) continue
                if (fuzzyMatch(w, kw)) {
                    found += kw
                    hits++
                    break
                }
            }
        }
        if (PHONE_NUMBER.containsMatchIn(text)) hits++
        return hits
    }

    /** Tokens match when they're equal OR differ by at most one edit
     *  (insertion / deletion / substitution), provided the reference
     *  keyword is at least 5 chars long (shorter keywords can't safely
     *  absorb an edit without producing false positives). */
    private fun fuzzyMatch(token: String, keyword: String): Boolean {
        if (token == keyword) return true
        if (keyword.length < 5) return false
        val diff = kotlin.math.abs(token.length - keyword.length)
        if (diff > 1) return false
        return editDistance(token, keyword, maxEdits = 1) <= 1
    }

    private fun editDistance(a: String, b: String, maxEdits: Int): Int {
        if (a == b) return 0
        val la = a.length; val lb = b.length
        if (kotlin.math.abs(la - lb) > maxEdits) return maxEdits + 1

        // Classic DP but with early-exit once the running min exceeds maxEdits.
        var prev = IntArray(lb + 1) { it }
        var curr = IntArray(lb + 1)
        for (i in 1..la) {
            curr[0] = i
            var rowMin = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + cost,
                )
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin > maxEdits) return maxEdits + 1
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[lb]
    }

    private fun clusterToQuad(cluster: Cluster, id: Long): Quad {
        val angle = cluster.medianAngleRad
        val cos = cos(-angle); val sin = sin(-angle)
        val allCorners = cluster.lines.flatMap { it.corners }
        val localsU = allCorners.map { it.x * cos - it.y * sin }
        val localsV = allCorners.map { it.x * sin + it.y * cos }

        val pad = cluster.medianHeight * PAD_FACTOR
        val u0 = localsU.min() - pad; val u1 = localsU.max() + pad
        val v0 = localsV.min() - pad; val v1 = localsV.max() + pad

        val cosI = kotlin.math.cos(angle); val sinI = kotlin.math.sin(angle)
        fun unrot(u: Double, v: Double) = CvPoint(u * cosI - v * sinI, u * sinI + v * cosI)

        return Quad(
            id = id,
            corners = listOf(unrot(u0, v0), unrot(u1, v0), unrot(u1, v1), unrot(u0, v1)),
        )
    }

    // --- classical fallback -------------------------------------------------

    private fun detectFromEdges(source: Bitmap, startId: Long): List<Quad> {
        val full = Mat().also { Utils.bitmapToMat(source, it) }
        try {
            val work = Mat()
            val scale = scaleToWorking(full, work, WORK_MAX_SIDE_EDGES)
            val workW = work.cols(); val workH = work.rows()

            val gray = Mat()
            Imgproc.cvtColor(work, gray, Imgproc.COLOR_RGBA2GRAY)

            val bin = Mat()
            // Otsu picks the threshold between the dark surface and white
            // paper adaptively per image. Works across typical lighting.
            Imgproc.threshold(gray, bin, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            Imgproc.morphologyEx(bin, bin, Imgproc.MORPH_CLOSE, kernel)

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(bin, contours, Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            gray.release(); bin.release(); kernel.release(); work.release()

            val minArea = workW * workH * 0.01
            val maxArea = workW * workH * 0.6

            var id = startId
            val quads = mutableListOf<Quad>()
            for (c in contours) {
                val area = Imgproc.contourArea(c)
                if (area < minArea || area > maxArea) continue

                val hull = MatOfPoint2f(*c.toArray())
                val rect = Imgproc.minAreaRect(hull)
                val w = rect.size.width; val h = rect.size.height
                if (w < 40 || h < 40) continue
                val aspect = max(w, h) / min(w, h)
                if (aspect > 12.0) continue

                val corners = Array(4) { CvPoint() }.also { rect.points(it) }
                val ordered = orderByAngle(corners, rect.angle * PI / 180.0)
                val scaledBack = ordered.map { CvPoint(it.x / scale, it.y / scale) }
                if (hasLightInterior(full, scaledBack)) {
                    quads += Quad(id = id++, corners = scaledBack)
                }
            }
            return quads.take(8)  // cap — classical fallback can get excited
        } finally {
            full.release()
        }
    }

    private fun orderByAngle(corners: Array<CvPoint>, angleRad: Double): List<CvPoint> {
        val cos = cos(-angleRad); val sin = sin(-angleRad)
        // Sort in rotated local frame: row-major TL, TR, BR, BL.
        val locals = corners.map { p ->
            p to (p.x * cos - p.y * sin to p.x * sin + p.y * cos)
        }
        val sortedByV = locals.sortedBy { it.second.second }
        val top = sortedByV.take(2).sortedBy { it.second.first }
        val bot = sortedByV.takeLast(2).sortedBy { it.second.first }
        return listOf(top[0].first, top[1].first, bot[1].first, bot[0].first)
    }

    private fun scaleToWorking(full: Mat, outWork: Mat, maxSide: Int): Double {
        val current = max(full.cols(), full.rows())
        if (current <= maxSide) {
            full.copyTo(outWork)
            return 1.0
        }
        val s = maxSide.toDouble() / current
        Imgproc.resize(full, outWork, Size(), s, s, Imgproc.INTER_AREA)
        return s
    }

    // --- color sanity -------------------------------------------------------

    private fun hasLightInterior(full: Mat, corners: List<CvPoint>): Boolean {
        val minX = corners.minOf { it.x }.coerceAtLeast(0.0)
        val maxX = corners.maxOf { it.x }.coerceAtMost(full.cols().toDouble() - 1)
        val minY = corners.minOf { it.y }.coerceAtLeast(0.0)
        val maxY = corners.maxOf { it.y }.coerceAtMost(full.rows().toDouble() - 1)
        val w = (maxX - minX); val h = (maxY - minY)
        if (w < 20 || h < 20) return false

        val insetX = w * 0.1; val insetY = h * 0.1
        val roiX = (minX + insetX).toInt()
        val roiY = (minY + insetY).toInt()
        val roiW = (w - 2 * insetX).toInt().coerceAtLeast(20)
            .coerceAtMost(full.cols() - roiX - 1)
        val roiH = (h - 2 * insetY).toInt().coerceAtLeast(20)
            .coerceAtMost(full.rows() - roiY - 1)
        if (roiW < 20 || roiH < 20) return false

        val lumas = mutableListOf<Double>()
        val chromas = mutableListOf<Double>()
        val patch = 24
        for (row in 0..2) for (col in 0..2) {
            val px = (roiX + (col + 1) * roiW / 4 - patch / 2).coerceIn(0, full.cols() - patch - 1)
            val py = (roiY + (row + 1) * roiH / 4 - patch / 2).coerceIn(0, full.rows() - patch - 1)
            val sample = full.submat(Rect(px, py, patch, patch))
            val mean = Core.mean(sample)
            val r = mean.`val`[0]; val g = mean.`val`[1]; val b = mean.`val`[2]
            lumas += 0.299 * r + 0.587 * g + 0.114 * b
            chromas += max(r, max(g, b)) - min(r, min(g, b))
            sample.release()
        }
        lumas.sort(); chromas.sort()
        val ok = lumas[4] >= MIN_WHITE_LUMA && chromas[4] <= MAX_WHITE_CHROMA
        if (!ok) Log.v(TAG, "Rejected quad interior: luma=${lumas[4]} chroma=${chromas[4]}")
        return ok
    }

    // --- warp ---------------------------------------------------------------

    private fun warpDocument(full: Mat, corners: List<CvPoint>): Bitmap {
        val src = MatOfPoint2f(*corners.toTypedArray())

        val widthTop = distance(corners[0], corners[1])
        val widthBottom = distance(corners[3], corners[2])
        val heightLeft = distance(corners[0], corners[3])
        val heightRight = distance(corners[1], corners[2])

        val outW = max(widthTop, widthBottom).toInt().coerceAtLeast(64)
        val outH = max(heightLeft, heightRight).toInt().coerceAtLeast(64)

        val dst = MatOfPoint2f(
            CvPoint(0.0, 0.0),
            CvPoint((outW - 1).toDouble(), 0.0),
            CvPoint((outW - 1).toDouble(), (outH - 1).toDouble()),
            CvPoint(0.0, (outH - 1).toDouble()),
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

    // --- geometry helpers ---------------------------------------------------

    private fun distance(a: CvPoint, b: CvPoint): Double {
        val dx = a.x - b.x; val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }

    /** Unsigned angular distance on a line (so 179° and 1° are 2° apart,
     *  not 178° — text reads the same way upside down). */
    private fun angleDistance(a: Double, b: Double): Double {
        var d = abs(a - b)
        while (d > PI) d -= PI
        return min(d, PI - d)
    }

    private fun averageAngle(a: Double, b: Double): Double {
        // Small-diff mean; handles wrap by using sin/cos averaging on 2θ,
        // which canonicalizes ±π to the same orientation.
        val sinSum = sin(2 * a) + sin(2 * b)
        val cosSum = cos(2 * a) + cos(2 * b)
        return atan2(sinSum, cosSum) / 2.0
    }

    private fun perpDistance(p1: CvPoint, p2: CvPoint, angleRad: Double): Double {
        val dx = p1.x - p2.x; val dy = p1.y - p2.y
        return abs(dx * -sin(angleRad) + dy * cos(angleRad))
    }

    private fun alongDistance(p1: CvPoint, p2: CvPoint, angleRad: Double): Double {
        val dx = p1.x - p2.x; val dy = p1.y - p2.y
        return abs(dx * cos(angleRad) + dy * sin(angleRad))
    }

    private fun median(values: List<Double>): Double {
        val s = values.sorted()
        return if (s.isEmpty()) 0.0
        else if (s.size % 2 == 1) s[s.size / 2]
        else (s[s.size / 2 - 1] + s[s.size / 2]) / 2.0
    }
}
