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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.tyler.receiptsnap.camera.CameraScreen
import com.tyler.receiptsnap.ui.LibraryScreen
import com.tyler.receiptsnap.ui.MainViewModel
import com.tyler.receiptsnap.ui.ReviewScreen
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

private enum class Tab(val label: String) { Capture("Capture"), Library("Library") }

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun Root(viewModel: MainViewModel) {
    val cameraPerm = rememberPermissionState(Manifest.permission.CAMERA)
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(Tab.Capture) }

    if (!cameraPerm.status.isGranted) {
        PermissionGate(
            message = "Camera permission is required",
            buttonLabel = "Grant camera access",
            onRequest = { cameraPerm.launchPermissionRequest() },
        )
        return
    }

    // Review takes over the whole screen — bottom nav hides during review to
    // avoid accidental taps while the user is editing quads.
    val inReview = state.phase is MainViewModel.Phase.Review

    Scaffold(
        containerColor = Color.Black,
        bottomBar = {
            if (!inReview) BottomNav(tab) { tab = it }
        },
    ) { insets ->
        Box(modifier = Modifier.fillMaxSize().padding(insets)) {
            if (inReview) {
                val review = state.phase as MainViewModel.Phase.Review
                ReviewScreen(
                    captured = review.bitmap,
                    quads = review.quads,
                    statusText = state.error ?: state.status,
                    busy = state.busy,
                    onCancel = viewModel::cancelReview,
                    onTapEmpty = viewModel::addQuadFromSeed,
                    onConfirm = viewModel::commitReview,
                )
            } else when (tab) {
                Tab.Capture -> CameraScreen(
                    busy = state.busy,
                    statusText = state.error ?: state.status,
                    onCapture = viewModel::capture,
                )
                Tab.Library -> LibraryScreen()
            }
        }
    }
}

@Composable
private fun BottomNav(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = Color.Black) {
        Tab.entries.forEach { t ->
            NavigationBarItem(
                selected = current == t,
                onClick = { onSelect(t) },
                icon = {
                    Icon(
                        imageVector = when (t) {
                            Tab.Capture -> Icons.Default.CameraAlt
                            Tab.Library -> Icons.Default.PhotoLibrary
                        },
                        contentDescription = t.label,
                    )
                },
                label = { Text(t.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black,
                    unselectedIconColor = Color.White.copy(alpha = 0.6f),
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedTextColor = Color.White.copy(alpha = 0.6f),
                    indicatorColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun PermissionGate(
    message: String,
    buttonLabel: String,
    onRequest: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Button(onClick = onRequest) { Text(buttonLabel) }
        }
    }
}
