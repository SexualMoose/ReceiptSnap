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
import androidx.documentfile.provider.DocumentFile
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
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.runtime.collectAsState
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
import com.tyler.receiptsnap.processing.CoupaUploadsFolder
import com.tyler.receiptsnap.processing.PdfMaker
import com.tyler.receiptsnap.processing.SmtpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Number of SMTP sends the library will run concurrently. Stays well under
 *  both Gmail (~10) and Office 365 (~20) per-account connection limits. */
private const val MAX_PARALLEL_SENDS = 4

data class LibraryItem(
    val uri: Uri,
    val name: String,
    val dateAddedSec: Long,
    /** When true, the item was picked via the external-folder upload flow;
     *  after a successful send we move it into Pictures/Coupa Uploads so
     *  the user's inbox folder stays clean. */
    val moveAfterSend: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as ReceiptSnapApp
    val settings = app.settings
    val sentTracker = app.sentTracker
    val sentSet by sentTracker.sent.collectAsState()

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
        // Prune the sent set for entries whose files no longer exist so it
        // can't grow unboundedly across deletes.
        sentTracker.retain(items.map { it.uri.toString() }.toSet())
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
     * Process the send queue in parallel: [MAX_PARALLEL_SENDS] workers each
     * pull receipts off the queue, PDF them, and fire them at Coupa's SMTP
     * endpoint. Keeps the per-item overhead low (each worker reuses its
     * coroutine for its share of items) while avoiding rate-limit
     * trouble — Gmail and Office 365 both tolerate ~10 concurrent SMTP
     * submissions per account, and 4 is comfortably under that.
     *
     * On any send failure we record the first error and let in-flight
     * workers finish; the queue halts so the user doesn't get a cascade
     * of identical auth-rejection attempts (almost always the culprit).
     */
    fun runSendLoop() {
        if (sendInFlight) return
        scope.launch {
            sendInFlight = true
            try {
                val recipient = settings.currentWalletEmail()
                if (recipient.isBlank()) {
                    sendError = "Set Coupa host and email in Settings first."
                    sendQueue = emptyList()
                    return@launch
                }
                val config = SmtpSender.Config(
                    host = settings.smtpHost.value,
                    port = settings.smtpPort.value,
                    fromEmail = settings.currentSenderEmail(),
                    password = settings.smtpPassword.value,
                )

                val queueSnapshot = sendQueue
                val semaphore = Semaphore(MAX_PARALLEL_SENDS)
                val errorRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

                coroutineScope {
                    queueSnapshot.forEach { item ->
                        launch(Dispatchers.IO) {
                            if (errorRef.get() != null) return@launch
                            semaphore.withPermit {
                                if (errorRef.get() != null) return@withPermit
                                val baseName = item.name
                                    .removeSuffix(".jpg")
                                    .removeSuffix(".jpeg")
                                    .removeSuffix(".png")
                                    .removeSuffix(".webp")
                                val result = try {
                                    val pdf = PdfMaker.makePdf(
                                        context = context,
                                        imageUri = item.uri,
                                        outputDir = PdfMaker.outputDir(context),
                                        baseName = baseName,
                                    )
                                    SmtpSender.send(
                                        config = config,
                                        toEmail = recipient,
                                        subject = baseName,
                                        bodyText = "Receipt attached (sent from ReceiptSnap).",
                                        attachment = pdf,
                                    )
                                } catch (t: Throwable) {
                                    SmtpSender.SendResult.Failure(
                                        "Prepare failed: ${t.message}", t,
                                    )
                                }

                                when (result) {
                                    is SmtpSender.SendResult.Success -> {
                                        sentTracker.markSent(item.uri)
                                        if (item.moveAfterSend) {
                                            // Fire and forget — archive failure
                                            // shouldn't block the send report.
                                            runCatching {
                                                CoupaUploadsFolder.moveToArchive(
                                                    context, item.uri, item.name,
                                                )
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            sendQueue = sendQueue - item
                                            sentCount += 1
                                        }
                                    }
                                    is SmtpSender.SendResult.Failure -> {
                                        errorRef.compareAndSet(null, result.message)
                                    }
                                }
                            }
                        }
                    }
                }

                val err = errorRef.get()
                if (err != null) {
                    sendError = err
                } else if (sentCount > 0) {
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

    // External-folder upload: user picks any directory (Downloads, a backup
    // folder, a screenshot album, etc.) via SAF; we enumerate every image
    // file inside and push each through the same PDF+SMTP pipeline the
    // in-app gallery uses. No detection — we assume each file is one
    // receipt, ready to upload.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        // Persist R/W access for this tree across process restarts so a
        // resumed queue keeps working. WRITE is required for the "move
        // successful uploads into Pictures/Coupa Uploads" archive step.
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@rememberLauncherForActivityResult
        val imageFiles = root.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true) }
            .sortedBy { it.name ?: "" }
        if (imageFiles.isEmpty()) {
            Toast.makeText(context, "No images found in selected folder.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        val now = System.currentTimeMillis() / 1000
        sendQueue = imageFiles.map { f ->
            LibraryItem(
                uri = f.uri,
                name = f.name ?: "receipt.jpg",
                dateAddedSec = now,
                moveAfterSend = true,
            )
        }
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
            onUploadFolder = { folderLauncher.launch(null) },
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
                val alreadySent = item.uri.toString() in sentSet
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
                        // "Already sent to Coupa" indicator: small accent
                        // check badge in the top-left corner. Stays visible
                        // whether or not we're in select mode so users can
                        // see at a glance what's been forwarded.
                        if (alreadySent) {
                            Box(
                                modifier = Modifier
                                    .padding(6.dp)
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .align(Alignment.TopStart),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Sent to Coupa",
                                    tint = Color.Black,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }

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
    onUploadFolder: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Library · $totalCount",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onUploadFolder) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.size(4.dp))
                        Text("Upload folder", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    text = "Tap to view · Long-press to select · Upload folder sends every image " +
                        "in a chosen directory through the same PDF + SMTP pipeline.",
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
