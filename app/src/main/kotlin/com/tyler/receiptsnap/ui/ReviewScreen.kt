package com.tyler.receiptsnap.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tyler.receiptsnap.processing.DocumentDetector
import org.opencv.core.Point as CvPoint

/**
 * Post-capture review. Quads come from the VM and can change (tap-to-add
 * asks the VM to grow a region from the seed color, which appends a Quad).
 * Exclusion state is local so toggling doesn't need a round-trip.
 */
@Composable
fun ReviewScreen(
    captured: Bitmap,
    quads: List<DocumentDetector.Quad>,
    statusText: String?,
    busy: Boolean,
    onCancel: () -> Unit,
    onTapEmpty: (seedX: Double, seedY: Double) -> Unit,
    onConfirm: (selected: List<DocumentDetector.Quad>) -> Unit,
) {
    val excludedIds = remember { mutableStateOf(setOf<Long>()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // If the VM appends a new quad after a grow, show it included by default.
    // We only remember exclusions — anything not in the set is "keep".
    fun isKept(id: Long) = id !in excludedIds.value

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

    fun pointInQuad(hit: CvPoint, quad: DocumentDetector.Quad): Boolean {
        val pts = quad.corners
        var inside = false
        var j = pts.size - 1
        for (i in pts.indices) {
            val xi = pts[i].x; val yi = pts[i].y
            val xj = pts[j].x; val yj = pts[j].y
            if ((yi > hit.y) != (yj > hit.y) &&
                hit.x < (xj - xi) * (hit.y - yi) / ((yj - yi) + 1e-9) + xi
            ) inside = !inside
            j = i
        }
        return inside
    }

    fun handleTap(offset: Offset) {
        val hit = canvasToImage(offset) ?: return
        val hitQuad = quads.firstOrNull { pointInQuad(hit, it) }
        if (hitQuad != null) {
            excludedIds.value = excludedIds.value.toMutableSet().also {
                if (hitQuad.id in it) it.remove(hitQuad.id) else it.add(hitQuad.id)
            }
        } else {
            onTapEmpty(hit.x, hit.y)
        }
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
            val kept = quads.count { isKept(it.id) }
            Text(
                text = "$kept selected",
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.size(1.dp).weight(1f))
            Button(
                onClick = { onConfirm(quads.filter { isKept(it.id) }) },
                enabled = !busy && kept > 0,
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
                    .pointerInput(captured) {
                        detectTapGestures(onTap = { handleTap(it) })
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
                    val keep = isKept(quad.id)
                    val path = Path().apply {
                        val p0 = imageToCanvas(quad.corners[0])
                        moveTo(p0.x, p0.y)
                        for (i in 1..3) {
                            val p = imageToCanvas(quad.corners[i])
                            lineTo(p.x, p.y)
                        }
                        close()
                    }
                    val color = if (keep) Color(0xFF00E5A0) else Color(0xFFFF5C5C)
                    val stroke = if (keep) Stroke(width = 6f, join = StrokeJoin.Round)
                    else Stroke(
                        width = 6f,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 14f)),
                    )
                    drawPath(path, color.copy(alpha = 0.15f))
                    drawPath(path, color, style = stroke)

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
            text = "Tap an outline to include/exclude · Tap empty area to search there",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 13.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

private data class FitMapping(val scale: Float, val offsetX: Float, val offsetY: Float)

private fun fitMapping(canvas: Size, imgW: Int, imgH: Int): FitMapping? {
    if (canvas.width <= 0f || canvas.height <= 0f || imgW == 0 || imgH == 0) return null
    val scale = minOf(canvas.width / imgW, canvas.height / imgH)
    val drawnW = imgW * scale
    val drawnH = imgH * scale
    return FitMapping(
        scale = scale,
        offsetX = (canvas.width - drawnW) / 2f,
        offsetY = (canvas.height - drawnH) / 2f,
    )
}
