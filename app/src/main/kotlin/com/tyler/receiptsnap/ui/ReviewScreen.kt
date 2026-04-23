package com.tyler.receiptsnap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyler.receiptsnap.processing.DocumentDetector
import org.opencv.core.Point as CvPoint

/**
 * Review lets the user:
 *   - tap inside a detected quad to remove it outright
 *   - tap empty space to ask the VM to grow a new quad there
 *   - drag any of the four corner handles to reshape a quad
 *
 * Quad state lives in the VM; this composable just renders and forwards
 * gestures. Convexity is enforced at drag-end so the user gets free movement
 * during the gesture but can't commit a twisted quad.
 */
@Composable
fun ReviewScreen(
    captured: Bitmap,
    quads: List<DocumentDetector.Quad>,
    statusText: String?,
    busy: Boolean,
    onCancel: () -> Unit,
    onRemoveQuad: (id: Long) -> Unit,
    onTapEmpty: (seedX: Double, seedY: Double) -> Unit,
    onDragCorner: (quadId: Long, cornerIdx: Int, newPoint: CvPoint) -> Unit,
    onConfirm: () -> Unit,
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    fun canvasToImage(offset: Offset): CvPoint? {
        val m = fitMapping(canvasSize, captured.width, captured.height) ?: return null
        val x = (offset.x - m.offsetX) / m.scale
        val y = (offset.y - m.offsetY) / m.scale
        if (x < 0 || y < 0 || x > captured.width || y > captured.height) return null
        return CvPoint(x.toDouble(), y.toDouble())
    }

    fun imageToCanvas(p: CvPoint): Offset {
        val m = fitMapping(canvasSize, captured.width, captured.height) ?: return Offset.Zero
        return Offset(
            (p.x.toFloat() * m.scale) + m.offsetX,
            (p.y.toFloat() * m.scale) + m.offsetY,
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel, enabled = !busy) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }
            Spacer(Modifier.size(1.dp).weight(1f))
            Text(
                text = "${quads.size} receipt${if (quads.size == 1) "" else "s"}",
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(1.dp).weight(1f))
            Button(
                onClick = onConfirm,
                enabled = !busy && quads.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = Color.Black,
                ),
            ) {
                Icon(Icons.Default.Done, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Save")
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(quads, captured) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            val downPos = down.position
                            val hit = findCornerHit(downPos, quads, canvasSize, captured)
                            if (hit != null) {
                                // Drag this specific corner. Track original
                                // position so we can roll back if the drag
                                // ends in a non-convex quad.
                                val original = hit.originalCorner
                                down.consume()
                                var latest: CvPoint = original
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (change.positionChange() != Offset.Zero) {
                                        val img = canvasToImage(change.position) ?: continue
                                        val clamped = CvPoint(
                                            img.x.coerceIn(0.0, captured.width.toDouble() - 1),
                                            img.y.coerceIn(0.0, captured.height.toDouble() - 1),
                                        )
                                        onDragCorner(hit.quadId, hit.cornerIdx, clamped)
                                        latest = clamped
                                        change.consume()
                                    }
                                }
                                // Convexity / min-size check on drag end.
                                val q = quads.firstOrNull { it.id == hit.quadId }
                                if (q != null) {
                                    val proposed = q.corners.toMutableList().also {
                                        it[hit.cornerIdx] = latest
                                    }
                                    if (!isConvexQuad(proposed) || !hasMinArea(proposed, captured)) {
                                        onDragCorner(hit.quadId, hit.cornerIdx, original)
                                    }
                                }
                            } else {
                                // Not on a handle — wait for up. If no significant
                                // drag, it's a tap: inside a quad removes it;
                                // in empty space asks the VM to grow a region.
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    if (change.positionChange().getDistance() > 12f) moved = true
                                }
                                if (!moved) {
                                    val hitImg = canvasToImage(downPos) ?: return@awaitEachGesture
                                    val hitQuad = quads.firstOrNull { pointInQuad(hitImg, it) }
                                    if (hitQuad != null) onRemoveQuad(hitQuad.id)
                                    else onTapEmpty(hitImg.x, hitImg.y)
                                }
                            }
                        }
                    },
            ) {
                canvasSize = size
                val m = fitMapping(size, captured.width, captured.height) ?: return@Canvas

                drawIntoCanvas { c ->
                    val dstL = m.offsetX
                    val dstT = m.offsetY
                    val dstR = dstL + captured.width * m.scale
                    val dstB = dstT + captured.height * m.scale
                    c.nativeCanvas.drawBitmap(
                        captured,
                        null,
                        android.graphics.RectF(dstL, dstT, dstR, dstB),
                        null,
                    )
                }

                quads.forEachIndexed { index, quad ->
                    val path = Path().apply {
                        val p0 = imageToCanvas(quad.corners[0])
                        moveTo(p0.x, p0.y)
                        for (i in 1..3) {
                            val p = imageToCanvas(quad.corners[i])
                            lineTo(p.x, p.y)
                        }
                        close()
                    }
                    val accent = Color(0xFF00E5A0)
                    drawPath(path, accent.copy(alpha = 0.15f))
                    drawPath(path, accent, style = Stroke(width = 6f, join = StrokeJoin.Round))

                    // Corner handles — big enough to hit reliably with a
                    // fingertip (we expect them around 40dp in canvas space).
                    quad.corners.forEach { p ->
                        val c = imageToCanvas(p)
                        drawCircle(Color.Black, radius = 22f, center = c)
                        drawCircle(accent, radius = 18f, center = c)
                        drawCircle(Color.White, radius = 8f, center = c)
                    }

                    val tl = imageToCanvas(quad.corners[0])
                    drawIntoCanvas { c ->
                        val paint = android.graphics.Paint().apply {
                            this.color = android.graphics.Color.WHITE
                            textSize = 48f
                            isFakeBoldText = true
                            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
                        }
                        c.nativeCanvas.drawText((index + 1).toString(), tl.x + 12f, tl.y + 48f, paint)
                    }
                }
            }
        }

        if (statusText != null) {
            Text(
                text = statusText,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Text(
            text = "Tap a receipt to remove · Drag corner handles to resize · Tap empty area to add",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// --- geometry helpers -------------------------------------------------------

private data class FitMapping(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun fitMapping(canvas: Size, imgW: Int, imgH: Int): FitMapping? {
    if (canvas.width <= 0f || canvas.height <= 0f || imgW == 0 || imgH == 0) return null
    val scale = kotlin.math.min(canvas.width / imgW, canvas.height / imgH)
    val drawnW = imgW * scale
    val drawnH = imgH * scale
    return FitMapping(
        scale = scale,
        offsetX = (canvas.width - drawnW) / 2f,
        offsetY = (canvas.height - drawnH) / 2f,
    )
}

private data class CornerHit(
    val quadId: Long,
    val cornerIdx: Int,
    val originalCorner: CvPoint,
)

/** Return which corner (if any) was tapped, or null for a non-handle hit. */
private fun findCornerHit(
    canvasPos: Offset, quads: List<DocumentDetector.Quad>,
    canvasSize: Size, captured: Bitmap,
): CornerHit? {
    val m = fitMapping(canvasSize, captured.width, captured.height) ?: return null
    // Hit radius in canvas pixels. 48px roughly matches a fingertip target.
    val hitRadius = 48f
    var best: CornerHit? = null
    var bestDist = Float.MAX_VALUE
    for (q in quads) {
        for ((idx, p) in q.corners.withIndex()) {
            val cx = (p.x.toFloat() * m.scale) + m.offsetX
            val cy = (p.y.toFloat() * m.scale) + m.offsetY
            val dx = cx - canvasPos.x; val dy = cy - canvasPos.y
            val d2 = dx * dx + dy * dy
            if (d2 <= hitRadius * hitRadius && d2 < bestDist) {
                bestDist = d2
                best = CornerHit(q.id, idx, p)
            }
        }
    }
    return best
}

private fun pointInQuad(p: CvPoint, quad: DocumentDetector.Quad): Boolean {
    val pts = quad.corners
    var inside = false
    var j = pts.size - 1
    for (i in pts.indices) {
        val xi = pts[i].x; val yi = pts[i].y
        val xj = pts[j].x; val yj = pts[j].y
        if ((yi > p.y) != (yj > p.y) &&
            p.x < (xj - xi) * (p.y - yi) / ((yj - yi) + 1e-9) + xi
        ) inside = !inside
        j = i
    }
    return inside
}

/** Convexity via cross-product sign — all four edges must turn the same way. */
private fun isConvexQuad(pts: List<CvPoint>): Boolean {
    if (pts.size != 4) return false
    var sign = 0
    for (i in 0..3) {
        val a = pts[i]; val b = pts[(i + 1) % 4]; val c = pts[(i + 2) % 4]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
        if (s == 0) continue
        if (sign == 0) sign = s
        else if (sign != s) return false
    }
    return true
}

/** Floor on the quad's pixel area — at least 1% of the image. Prevents a
 *  user from dragging all four corners into a single point. */
private fun hasMinArea(pts: List<CvPoint>, captured: Bitmap): Boolean {
    val area = polygonArea(pts)
    val imgArea = captured.width.toDouble() * captured.height
    return area >= imgArea * 0.005
}

private fun polygonArea(pts: List<CvPoint>): Double {
    var sum = 0.0
    for (i in pts.indices) {
        val a = pts[i]; val b = pts[(i + 1) % pts.size]
        sum += a.x * b.y - b.x * a.y
    }
    return kotlin.math.abs(sum) / 2.0
}
