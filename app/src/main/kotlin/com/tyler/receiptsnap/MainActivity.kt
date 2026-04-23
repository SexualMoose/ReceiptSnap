package com.tyler.receiptsnap

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.tyler.receiptsnap.camera.CameraScreen
import com.tyler.receiptsnap.ui.MainViewModel
import com.tyler.receiptsnap.ui.ResultsPanel
import com.tyler.receiptsnap.ui.theme.ReceiptSnapTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReceiptSnapTheme {
                Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
                    Root(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun Root(viewModel: MainViewModel) {
    val cameraPerm = rememberPermissionState(Manifest.permission.CAMERA)
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (!cameraPerm.status.isGranted) {
        PermissionGate(onRequest = { cameraPerm.launchPermissionRequest() })
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        CameraScreen(
            busy = state.busy,
            statusText = state.error ?: state.status,
            onCapture = { controller -> viewModel.captureAndProcess(controller) },
        )
        ResultsPanel(
            saved = state.saved,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp),
        )
    }
}

@Composable
private fun PermissionGate(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Camera permission is required",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onRequest) { Text("Grant permission") }
        }
    }
}
