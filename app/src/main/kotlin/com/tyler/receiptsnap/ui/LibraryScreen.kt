package com.tyler.receiptsnap.ui

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tyler.receiptsnap.ReceiptSnapApp
import com.tyler.receiptsnap.processing.PdfMaker
import com.tyler.receiptsnap.processing.SmtpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryItem(
    val uri: Uri,
    val name: String,
    val dateAddedSec: Long,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = (context.applicationContext as ReceiptSnapApp).settings

    var refreshTick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    var viewing by remember { mutableStateOf<LibraryItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    var sendQueue by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var sentCount by remember { mutableIntStateOf(0) }
    var sendInFlight by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    // Android 11+ returns a pending intent for scoped-storage deletes that
    // need user confirmation (anything written by another app). Since we
    // wrote these files, the first tap usually succeeds, but we still
    // plumb the pending-delete path for completeness.
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            refreshTick += 1
            selected = emptySet()
        }
    }

    LaunchedEffect(refreshTick) {
        items = withContext(Dispatchers.IO) { loadItems(context) }
    }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { refreshTick += 1 }
        }
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    /**
     * Process the send queue automatically: build a PDF for each selected
     * receipt and push it to Coupa via SMTP, with no email-client detour.
     * Runs until the queue is empty or an SMTP error occurs.
     */
    fun runSendLoop() {
        if (sendInFlight) return
        scope.launch {
            sendInFlight = true
            try {
                while (sendQueue.isNotEmpty()) {
                    val next = sendQueue.first()
                    val recipient = settings.currentWalletEmail()
                    if (recipient.isBlank()) {
                        sendError = "Set Coupa host and email in Settings first."
                        sendQueue = emptyList()
                        return@launch
                    }
                    val config = SmtpSender.Config(
                        host = settings.smtpHost.value,
                        port = settings.smtpPort.value,
                        fromEmail = settings.userEmail.value,
                        password = settings.smtpPassword.value,
                    )
                    val result = withContext(Dispatchers.IO) {
                        val pdf = PdfMaker.makePdf(
                            context = context,
                            imageUri = next.uri,
                            outputDir = PdfMaker.outputDir(context),
                            baseName = next.name.removeSuffix(".jpg"),
                        )
                        SmtpSender.send(
                            config = config,
                            toEmail = recipient,
                            subject = next.name.removeSuffix(".jpg"),
                            bodyText = "Receipt attached (sent from ReceiptSnap).",
                            attachment = pdf,
                        )
                    }
                    when (result) {
                        is SmtpSender.SendResult.Success -> {
                            sendQueue = sendQueue.drop(1)
                            sentCount += 1
                        }
                        is SmtpSender.SendResult.Failure -> {
                            sendError = result.message
                            // Stop the queue — the same error will almost
                            // certainly recur on the next item.
                            return@launch
                        }
                    }
                }
                if (sentCount > 0) {
                    Toast.makeText(
                        context,
                        "Sent $sentCount receipt${if (sentCount == 1) "" else "s"} to Coupa.",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                sendInFlight = false
            }
        }
    }

    fun beginSend() {
        if (selected.isEmpty()) return
        val queue = items.filter { it.uri in selected }
        if (queue.isEmpty()) return
        sendQueue = queue
        sentCount = 0
        sendError = null
        selected = emptySet()
        runSendLoop()
    }

    fun performDelete() {
        val uris = items.filter { it.uri in selected }.map { it.uri }
        if (uris.isEmpty()) return
        scope.launch {
            val needsConsent = withContext(Dispatchers.IO) {
                tryDeleteDirectly(context, uris)
            }
            if (needsConsent != null) {
                deleteLauncher.launch(IntentSenderRequest.Builder(needsConsent).build())
            } else {
                refreshTick += 1
                selected = emptySet()
            }
        }
    }

    if (viewing != null) {
        SingleItemViewer(
            item = viewing!!,
            onBack = { viewing = null },
        )
        return
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} receipt${if (selected.size == 1) "" else "s"}?") },
            text = { Text("This permanently removes them from device storage.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    performDelete()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF141414),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
        )
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Header(
            totalCount = items.size,
            selectedCount = selected.size,
            queueSize = sendQueue.size,
            sentCount = sentCount,
            sendInFlight = sendInFlight,
            sendError = sendError,
            onCancelSelect = { selected = emptySet() },
            onStartSend = ::beginSend,
            onClearQueue = {
                sendQueue = emptyList()
                sentCount = 0
                sendError = null
            },
            onDismissError = { sendError = null },
            onRequestDelete = { confirmDelete = true },
        )

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No receipts saved yet", color = Color.White.copy(alpha = 0.5f))
            }
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 112.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(items, key = { it.uri.toString() }) { item ->
                val isSelected = item.uri in selected
                val inSelectMode = selected.isNotEmpty()
                Column(
                    modifier = Modifier.combinedClickable(
                        onClick = {
                            // Select-mode: tap toggles selection. Normal mode:
                            // tap opens the single-item viewer.
                            if (inSelectMode) {
                                selected = if (isSelected) selected - item.uri else selected + item.uri
                            } else {
                                viewing = item
                            }
                        },
                        onLongClick = {
                            // Long-press enters select mode and selects this tile.
                            selected = selected + item.uri
                        },
                    ),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.DarkGray)
                            .then(
                                if (isSelected)
                                    Modifier.border(
                                        width = 3.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(10.dp),
                                    )
                                else Modifier
                            ),
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Empty-circle checkbox on every tile in select mode
                        // so the user sees the affordance. Fills in for selected.
                        if (inSelectMode) {
                            Box(
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else Color.Black.copy(alpha = 0.5f)
                                    )
                                    .then(
                                        if (isSelected) Modifier
                                        else Modifier.border(
                                            width = 2.dp,
                                            color = Color.White.copy(alpha = 0.8f),
                                            shape = CircleShape,
                                        )
                                    )
                                    .align(Alignment.TopEnd),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color.Black,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = item.name.removeSuffix(".jpg"),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    totalCount: Int,
    selectedCount: Int,
    queueSize: Int,
    sentCount: Int,
    sendInFlight: Boolean,
    sendError: String?,
    onCancelSelect: () -> Unit,
    onStartSend: () -> Unit,
    onClearQueue: () -> Unit,
    onDismissError: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        when {
            sendError != null -> {
                Text(
                    text = "Coupa send failed",
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = sendError,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Spacer(Modifier.height(6.dp))
                Row {
                    TextButton(onClick = onStartSend) {
                        Text("Retry", color = MaterialTheme.colorScheme.primary)
                    }
                    TextButton(onClick = onDismissError) {
                        Text("Dismiss", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            queueSize > 0 || sendInFlight -> {
                val total = sentCount + queueSize
                val done = sentCount
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (sendInFlight)
                            "Sending to Coupa · ${done + 1} of $total…"
                        else "Queued · $queueSize remaining",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onClearQueue, enabled = !sendInFlight) {
                        Text("Stop", color = Color.White.copy(alpha = 0.8f))
                    }
                }
            }

            selectedCount > 0 -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onCancelSelect) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                    Text(
                        text = "$selectedCount selected",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onRequestDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                    Button(
                        onClick = onStartSend,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = Color.Black,
                        ),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Coupa")
                    }
                }
            }

            else -> {
                Text(
                    text = "Library · $totalCount",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Tap to view · Long-press to select",
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SingleItemViewer(item: LibraryItem, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                )
            }
            Text(
                text = item.name.removeSuffix(".jpg"),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
        }
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = item.uri,
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Returns non-null IntentSender when user confirmation is required. */
private fun tryDeleteDirectly(context: Context, uris: List<Uri>): android.content.IntentSender? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        // Scoped storage on Android 11+: batch-delete via MediaStore returns
        // a PendingIntent so the system can prompt the user.
        val pending = MediaStore.createDeleteRequest(context.contentResolver, uris)
        return pending.intentSender
    }
    // Pre-Android 11: delete each directly.
    for (uri in uris) {
        runCatching { context.contentResolver.delete(uri, null, null) }
    }
    return null
}

private fun loadItems(context: Context): List<LibraryItem> {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("Pictures/ReceiptSnap%")
    val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    val out = mutableListOf<LibraryItem>()
    context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
        val idxId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val idxName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val idxAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        while (c.moveToNext()) {
            val id = c.getLong(idxId)
            val uri = ContentUris.withAppendedId(collection, id)
            out += LibraryItem(uri, c.getString(idxName), c.getLong(idxAdded))
        }
    }
    return out
}
