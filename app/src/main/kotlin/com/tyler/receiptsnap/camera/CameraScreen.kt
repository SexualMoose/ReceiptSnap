package com.tyler.receiptsnap.camera

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun CameraScreen(
    busy: Boolean,
    statusText: String?,
    captureProgress: Int,
    captureTotal: Int,
    modifier: Modifier = Modifier,
    onCapture: (controller: CameraController, lifecycleOwner: androidx.lifecycle.LifecycleOwner, previewView: PreviewView) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember { CameraController(context) }
    val previewViewState = remember { mutableStateOf<PreviewView?>(null) }
    val quality by controller.previewQuality.collectAsState()

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                }.also { previewViewState.value = it }
            },
        )

        // Live capture-quality badge. Tells the user whether the camera
        // is currently seeing enough text to be worth taking a 5-second
        // multi-camera capture. Updates ~once per second from a CameraX
        // ImageAnalysis stream running ML Kit on a 720p preview frame.
        QualityBadge(
            quality = quality,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 16.dp),
        )

        LaunchedEffect(previewViewState.value) {
            previewViewState.value?.let { pv ->
                runCatching { controller.bind(lifecycleOwner, pv) }
            }
        }
        DisposableEffect(controller) {
            onDispose { controller.release() }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp, top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (statusText != null) {
                Text(
                    text = statusText,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Capture progress bar. Visible only during multi-camera
            // capture so the user knows the shutter click isn't frozen —
            // each secondary lens takes ~2 s to rebind and fire.
            if (captureTotal > 0) {
                LinearProgressIndicator(
                    progress = {
                        if (captureTotal == 0) 0f
                        else (captureProgress.toFloat() / captureTotal).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        if (!busy) {
                            previewViewState.value?.let { pv ->
                                onCapture(controller, lifecycleOwner, pv)
                            }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            color = Color.Black,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(36.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = "Capture",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Lay receipts flat with space between them",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun QualityBadge(
    quality: CameraController.PreviewQuality,
    modifier: Modifier = Modifier,
) {
    val (label, dotColor) = when (quality.level) {
        CameraController.QualityLevel.Good -> "Receipt looks good" to Color(0xFF00E5A0)
        CameraController.QualityLevel.Fair -> "Get closer · steadier" to Color(0xFFFFC857)
        CameraController.QualityLevel.Poor -> "Frame the receipt" to Color(0xFFFF5C5C)
    }
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, shape = RoundedCornerShape(50))
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
        if (quality.textLineCount > 0) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = "${quality.textLineCount} lines",
                color = Color.White.copy(alpha = 0.65f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
