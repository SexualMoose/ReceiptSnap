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
import com.tyler.receiptsnap.processing.EmailContent
import com.tyler.receiptsnap.processing.FolderUploadProcessor
import com.tyler.receiptsnap.processing.PdfMaker
import com.tyler.receiptsnap.processing.ReceiptsSortedFolder
import com.tyler.receiptsnap.processing.SmtpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What the user picked in the Upload-folder chooser dialog. The folder
 *  launcher branches on this after the SAF tree picker returns. */
private enum class FolderAction { CoupaUpload, SortByMonth }

private sealed interface PassOutcome {
    /** Every item in the queue that this pass started with was sent. */
    data object AllSent : PassOutcome
    /** Account hit a rate/quota/too-many-logins error. Remaining items
     *  stay in the queue; caller may try the next account. */
    data class LimitHit(val message: String) : PassOutcome
    /** Something else failed (bad credentials, network down, bad config)
     *  that switching accounts won't fix — stop. */
    data class FatalError(val message: String) : PassOutcome
}

/** SMTP server error strings that indicate a rate/quota limit rather than
 *  a bad config. When failover is enabled these are the signal to switch
 *  accounts instead of stopping the whole batch. */
private fun isLimitError(msg: String?): Boolean {
    if (msg == null) return false
    val lower = msg.lowercase()
    return "545" in lower ||                   // too many login attempts (widely used)
        "421" in lower ||                      // service not available / throttled
        "450" in lower || "452" in lower ||    // mailbox busy / insufficient storage (temporary)
        "too many" in lower ||
        "rate limit" in lower ||
        "daily" in lower ||
        "quota" in lower ||
        "limit exceeded" in lower ||
        "5.4.5" in lower ||                    // Gmail daily-user limit
        "5.1.1" in lower ||                    // user unknown — can also signal shutdown
        "4.4.2" in lower ||
        "4.7.1" in lower                       // temporary rate-limit code
}

/**
 * Run one end-to-end pass of the queue against [account]. Workers each
 * open their own persistent connection (to avoid per-message re-auth)
 * and drain the shared channel. On any worker failure the pass short-
 * circuits with either [PassOutcome.LimitHit] or
 * [PassOutcome.FatalError] so the outer loop can decide whether to
 * fail over.
 */
private suspend fun runSendPass(
    account: com.tyler.receiptsnap.data.SmtpAccount,
    recipient: String,
    context: android.content.Context,
    sentTracker: com.tyler.receiptsnap.data.SentTracker,
    sendLog: com.tyler.receiptsnap.data.SendLog,
    onSuccess: suspend (LibraryItem) -> Unit,
    currentQueue: () -> List<LibraryItem>,
): PassOutcome {
    val queueSnapshot = currentQueue()
    if (queueSnapshot.isEmpty()) return PassOutcome.AllSent

    val config = SmtpSender.Config(
        host = account.host,
        port = account.port,
        fromEmail = account.email,
        password = account.password,
    )

    val limitRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val fatalRef = java.util.concurrent.atomic.AtomicReference<String?>(null)
    val successfulSources = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Uri, String>>()
    val failedSources = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Uri, String>>()

    val channel = Channel<LibraryItem>(capacity = queueSnapshot.size.coerceAtLeast(1))
    queueSnapshot.forEach { channel.trySend(it) }
    channel.close()

    val workerCount = minOf(MAX_PARALLEL_SENDS, queueSnapshot.size).coerceAtLeast(1)
    coroutineScope {
        repeat(workerCount) { workerIdx ->
            launch(Dispatchers.IO) {
                delay(workerIdx * AUTH_STAGGER_MS)
                if (limitRef.get() != null || fatalRef.get() != null) return@launch

                val connection = try {
                    SmtpSender.openConnection(config)
                } catch (t: Throwable) {
                    val msg = t.message ?: "connect failed"
                    if (isLimitError(msg)) limitRef.compareAndSet(null, msg)
                    else fatalRef.compareAndSet(null, "Worker $workerIdx couldn't open SMTP: $msg")
                    return@launch
                }

                connection.use { conn ->
                    for (item in channel) {
                        if (limitRef.get() != null || fatalRef.get() != null) break
                        val baseName = item.name
                            .removeSuffix(".jpg").removeSuffix(".jpeg")
                            .removeSuffix(".png").removeSuffix(".webp")
                        val result = try {
                            val pdf = if (item.pdfPassthrough) {
                                PdfMaker.makePdfPassthrough(
                                    context = context,
                                    imageUri = item.uri,
                                    outputDir = PdfMaker.outputDir(context),
                                    baseName = baseName,
                                )
                            } else {
                                PdfMaker.makePdf(
                                    context = context,
                                    imageUri = item.uri,
                                    outputDir = PdfMaker.outputDir(context),
                                    baseName = baseName,
                                    maxWidthPx = item.pdfMaxWidthPx ?: PdfMaker.DEFAULT_MAX_WIDTH_PX,
                                )
                            }
                            conn.send(
                                toEmail = recipient,
                                subject = EmailContent.subjectFor(baseName),
                                bodyText = EmailContent.bodyFor(baseName),
                                attachment = pdf,
                            )
                        } catch (t: Throwable) {
                            SmtpSender.SendResult.Failure("Prepare failed: ${t.message}", t)
                        }

                        when (result) {
                            is SmtpSender.SendResult.Success -> {
                                sentTracker.markSent(item.uri)
                                sendLog.record(
                                    receiptName = item.name,
                                    recipient = recipient,
                                    fromAccount = account.email,
                                    messageId = result.messageId,
                                    smtpHost = "${account.host}:${account.port}",
                                )
                                if (item.moveAfterSend) {
                                    val srcUri = item.sourceUri
                                    val srcName = item.sourceName
                                    if (srcUri != null && srcName != null) {
                                        successfulSources.add(srcUri to srcName)
                                    } else {
                                        runCatching {
                                            CoupaUploadsFolder.moveToArchive(context, item.uri, item.name)
                                        }
                                    }
                                }
                                if (item.uri.scheme == "file") {
                                    runCatching { java.io.File(item.uri.path!!).delete() }
                                }
                                onSuccess(item)
                            }
                            is SmtpSender.SendResult.Failure -> {
                                val srcUri = item.sourceUri
                                val srcName = item.sourceName
                                if (srcUri != null && srcName != null) {
                                    failedSources.add(srcUri to srcName)
                                }
                                // Classify: if it smells like a rate limit,
                                // record as such so the outer loop can fail
                                // over rather than marking the whole batch
                                // fatal.
                                if (isLimitError(result.message)) {
                                    limitRef.compareAndSet(null, result.message)
                                } else {
                                    fatalRef.compareAndSet(null, result.message)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Archive sources whose crops all landed — even when we're returning
    // LimitHit for the pass, successful crops still deserve their source
    // moved since the failed ones can be retried against the next
    // account on the next pass.
    val sourcesToArchive = successfulSources - failedSources
    withContext(Dispatchers.IO) {
        for ((srcUri, srcName) in sourcesToArchive) {
            runCatching { CoupaUploadsFolder.moveToArchive(context, srcUri, srcName) }
        }
    }

    return when {
        limitRef.get() != null -> PassOutcome.LimitHit(limitRef.get()!!)
        fatalRef.get() != null -> PassOutcome.FatalError(fatalRef.get()!!)
        else -> PassOutcome.AllSent
    }
}

/** Concurrent SMTP workers. Each keeps one authenticated connection open
 *  for its share of the queue — so this also equals the number of auth
 *  attempts we make per batch. Stays well under Gmail (~10) and Office
 *  365 (~20) per-account connection caps, with room on either side. */
private const val MAX_PARALLEL_SENDS = 6

/** Milliseconds between worker cold-starts. Prevents N simultaneous login
 *  attempts which can trigger anti-abuse throttling on Office 365. */
private const val AUTH_STAGGER_MS = 250L

/** Concurrent workers running DocumentDetector + ReceiptParser on picked
 *  folder images. CPU-bound (OpenCV + ML Kit); 4 saturates a modern 8-core
 *  Snapdragon without peaking bitmap memory past what largeHeap grants us. */
private const val MAX_PARALLEL_PREPROCESS = 4

data class LibraryItem(
    val uri: Uri,
    val name: String,
    val dateAddedSec: Long,
    /** When true, the original source gets moved on successful send. For
     *  gallery items: source == uri, so the Library tile disappears. For
     *  folder uploads: the original source (possibly different from uri,
     *  which may point at a temp crop) is moved to Pictures/Coupa Uploads. */
    val moveAfterSend: Boolean = false,
    /** The original folder-picked file, if this item is a processed crop.
     *  Used for archival routing: once every item that traces back to this
     *  source finishes, the source moves to Pictures/Coupa Uploads. */
    val sourceUri: Uri? = null,
    val sourceName: String? = null,
    /** PdfMaker width override — null uses the default. Folder uploads
     *  use a wider cap since the source may be variable-quality. */
    val pdfMaxWidthPx: Int? = null,
    /** When true, PdfMaker embeds source bytes directly (or re-encodes
     *  once at high quality) without scaling or aggressive compression.
     *  Used for re-uploads from Failed Coupa Uploads. */
    val pdfPassthrough: Boolean = false,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as ReceiptSnapApp
    val settings = app.settings
    val sentTracker = app.sentTracker
    val sendLog = app.sendLog
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

    // Preprocessing progress — shown when running DocumentDetector over a
    // picked folder before the SMTP queue fires.
    var preprocessDone by remember { mutableIntStateOf(0) }
    var preprocessTotal by remember { mutableIntStateOf(0) }
    var preprocessLabel by remember { mutableStateOf<String?>(null) }

    // "Upload folder" opens a chooser dialog first — user picks whether
    // the selected folder should be processed + sent to Coupa, or
    // processed + sorted into Pictures/Receipts Sorted/yyyy-MM/ folders.
    var showUploadChooser by remember { mutableStateOf(false) }
    var pendingFolderAction by remember { mutableStateOf(FolderAction.CoupaUpload) }

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
     * Process the send queue. Each saved SMTP account runs as a "pass" —
     * all MAX_PARALLEL_SENDS workers open their connections against the
     * current account and drain as much of the queue as they can.
     * If the account replies with a rate/quota/too-many-logins error AND
     * failover is enabled, we switch to the next saved account and
     * continue. Successful items stay removed from the queue; the next
     * account picks up where the prior one stopped.
     *
     * Persistent connections keep auth cost per account at N (one per
     * worker) instead of N × queue size.
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

                val allAccounts = settings.accounts.value
                if (allAccounts.isEmpty()) {
                    sendError = "No SMTP accounts configured. Add one in Settings."
                    sendQueue = emptyList()
                    return@launch
                }

                // Accounts to attempt, in order. Active first, then others
                // only when failover is on. With failover off, we just
                // try the active account and stop on any error.
                val activeId = settings.activeAccountId.value
                val active = allAccounts.firstOrNull { it.id == activeId } ?: allAccounts.first()
                val failover = settings.failoverEnabled.value
                val accountOrder = buildList {
                    add(active)
                    if (failover) addAll(allAccounts.filter { it.id != active.id })
                }

                for ((accountIdx, account) in accountOrder.withIndex()) {
                    if (sendQueue.isEmpty()) break
                    if (account.id != settings.activeAccountId.value) {
                        settings.setActiveAccount(account.id)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                context,
                                "Rate limit hit — switching to ${account.email}",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }

                    val outcome = runSendPass(
                        account = account,
                        recipient = recipient,
                        context = context,
                        sentTracker = sentTracker,
                        sendLog = sendLog,
                        onSuccess = { item ->
                            withContext(Dispatchers.Main) {
                                sendQueue = sendQueue - item
                                sentCount += 1
                            }
                        },
                        currentQueue = { sendQueue },
                    )
                    when (outcome) {
                        is PassOutcome.AllSent -> break
                        is PassOutcome.FatalError -> {
                            sendError = outcome.message
                            break
                        }
                        is PassOutcome.LimitHit -> {
                            if (!failover || accountIdx == accountOrder.lastIndex) {
                                sendError = if (accountOrder.size > 1)
                                    "Every saved account is rate-limited. Try again later. Last: ${outcome.message}"
                                else
                                    "Rate limit hit. Enable failover + add another account to continue. " +
                                        "Server said: ${outcome.message}"
                                break
                            }
                            // else fall through to the next account
                        }
                    }
                }

                if (sendError == null && sentCount > 0) {
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

    // External-folder upload — two branches:
    //
    //   1. User picks Pictures/Failed Coupa Uploads → passthrough mode.
    //      Skip all detection/compression, embed each image's raw bytes
    //      in a PDF and send. They've already decided these are receipts.
    //
    //   2. Any other folder → run the same DocumentDetector + ReceiptParser
    //      pipeline as on-device captures. Each picked image can yield
    //      0-N structured-name receipt crops. Sources with 0 detected
    //      receipts get moved to Pictures/Failed Coupa Uploads untouched
    //      so the user can retry manually via branch 1 if we got it wrong.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return@rememberLauncherForActivityResult
        val folderName = root.name.orEmpty()
        val isFailedFolder = folderName == CoupaUploadsFolder.FAILED_UPLOADS_FOLDER_NAME
        val imageFiles = root.listFiles()
            .filter { it.isFile && (it.type?.startsWith("image/") == true) }
            .sortedBy { it.name ?: "" }
        if (imageFiles.isEmpty()) {
            Toast.makeText(context, "No images found in selected folder.", Toast.LENGTH_SHORT).show()
            return@rememberLauncherForActivityResult
        }
        sentCount = 0
        sendError = null
        selected = emptySet()

        if (pendingFolderAction == FolderAction.SortByMonth) {
            // Branch 3 — process and sort into Pictures/Receipts Sorted/yyyy-MM/.
            // Runs the same DocumentDetector + ReceiptParser pipeline as the
            // Coupa upload path but saves each detected crop into a month-
            // named folder instead of queuing for email. No SMTP involvement.
            scope.launch {
                withContext(Dispatchers.IO) {
                    FolderUploadProcessor.cropsDir(context).listFiles()?.forEach {
                        runCatching { it.delete() }
                    }
                }
                preprocessTotal = imageFiles.size
                preprocessDone = 0
                preprocessLabel = "Sorting receipts"

                val savedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val undatedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val doneCounter = java.util.concurrent.atomic.AtomicInteger(0)
                val semaphore = Semaphore(MAX_PARALLEL_PREPROCESS)
                val successfulSources =
                    java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Uri, String>>()
                val failedSources =
                    java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Uri, String>>()

                coroutineScope {
                    imageFiles.forEach { file ->
                        launch(Dispatchers.Default) {
                            semaphore.withPermit {
                                val srcUri = file.uri
                                val srcName = file.name ?: "receipt.jpg"
                                val result = FolderUploadProcessor.process(context, srcUri)
                                when (result) {
                                    is FolderUploadProcessor.Result.Detected -> {
                                        var allOk = true
                                        for (crop in result.crops) {
                                            val dest = withContext(Dispatchers.IO) {
                                                ReceiptsSortedFolder.saveToMonthFolder(
                                                    context = context,
                                                    tempFile = crop.tempFile,
                                                    date = crop.info.date,
                                                    displayName = crop.displayName,
                                                )
                                            }
                                            if (dest != null) {
                                                savedCount.incrementAndGet()
                                                if (crop.info.date == null) {
                                                    undatedCount.incrementAndGet()
                                                }
                                                runCatching { crop.tempFile.delete() }
                                            } else {
                                                allOk = false
                                            }
                                        }
                                        if (allOk) successfulSources.add(srcUri to srcName)
                                        else failedSources.add(srcUri to srcName)
                                    }
                                    is FolderUploadProcessor.Result.NoReceipts,
                                    is FolderUploadProcessor.Result.LoadFailed -> {
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                CoupaUploadsFolder.moveToFailed(
                                                    context, srcUri, srcName,
                                                )
                                            }
                                        }
                                    }
                                }
                                val n = doneCounter.incrementAndGet()
                                withContext(Dispatchers.Main) {
                                    preprocessDone = n
                                    preprocessLabel = "Sorting $n of ${imageFiles.size}"
                                }
                            }
                        }
                    }
                }

                // Archive sources whose crops ALL landed in month folders
                // (same convention as the Coupa-upload flow).
                withContext(Dispatchers.IO) {
                    for ((srcUri, srcName) in successfulSources - failedSources) {
                        runCatching { CoupaUploadsFolder.moveToArchive(context, srcUri, srcName) }
                    }
                }

                preprocessTotal = 0
                preprocessDone = 0
                preprocessLabel = null

                val saved = savedCount.get()
                val undated = undatedCount.get()
                val msg = buildString {
                    append("Sorted $saved receipt${if (saved == 1) "" else "s"} ")
                    append("into Pictures/Receipts Sorted.")
                    if (undated > 0) append(" $undated went to Undated/.")
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
            return@rememberLauncherForActivityResult
        }

        if (isFailedFolder) {
            // Branch 1 — passthrough. One PDF per source, source bytes
            // embedded as-is. moveAfterSend routes to Pictures/Coupa Uploads.
            val now = System.currentTimeMillis() / 1000
            sendQueue = imageFiles.map { f ->
                LibraryItem(
                    uri = f.uri,
                    name = f.name ?: "receipt.jpg",
                    dateAddedSec = now,
                    moveAfterSend = true,
                    sourceUri = f.uri,
                    sourceName = f.name ?: "receipt.jpg",
                    pdfPassthrough = true,
                )
            }
            runSendLoop()
            return@rememberLauncherForActivityResult
        }

        // Branch 2 — detection preprocessing, parallelized. Up to
        // MAX_PARALLEL_PREPROCESS images get detect+warp+parse'd at once
        // so a large folder isn't a serial wait. Progress is an atomic
        // counter because workers complete in arbitrary order.
        scope.launch {
            // Wipe any leftover temp crops from a previous (possibly
            // crashed) session so the cache doesn't accumulate forever.
            withContext(Dispatchers.IO) {
                FolderUploadProcessor.cropsDir(context).listFiles()?.forEach {
                    runCatching { it.delete() }
                }
            }
            preprocessTotal = imageFiles.size
            preprocessDone = 0
            preprocessLabel = "Analyzing images"

            // Thread-safe accumulator. Workers append crops or move
            // sources to the Failed archive under synchronized access.
            val queue = java.util.Collections.synchronizedList(mutableListOf<LibraryItem>())
            val doneCounter = java.util.concurrent.atomic.AtomicInteger(0)
            val semaphore = Semaphore(MAX_PARALLEL_PREPROCESS)
            val now = System.currentTimeMillis() / 1000

            coroutineScope {
                imageFiles.forEach { file ->
                    launch(Dispatchers.Default) {
                        semaphore.withPermit {
                            val srcUri = file.uri
                            val srcName = file.name ?: "receipt.jpg"
                            val result = FolderUploadProcessor.process(context, srcUri)
                            when (result) {
                                is FolderUploadProcessor.Result.Detected -> {
                                    for (crop in result.crops) {
                                        queue.add(
                                            LibraryItem(
                                                uri = Uri.fromFile(crop.tempFile),
                                                name = crop.displayName,
                                                dateAddedSec = now,
                                                moveAfterSend = true,
                                                sourceUri = srcUri,
                                                sourceName = srcName,
                                                pdfMaxWidthPx = PdfMaker.FOLDER_UPLOAD_MAX_WIDTH_PX,
                                            )
                                        )
                                    }
                                }
                                is FolderUploadProcessor.Result.NoReceipts,
                                is FolderUploadProcessor.Result.LoadFailed -> {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            CoupaUploadsFolder.moveToFailed(context, srcUri, srcName)
                                        }
                                    }
                                }
                            }
                            val n = doneCounter.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                preprocessDone = n
                                preprocessLabel = "Analyzing $n of ${imageFiles.size}"
                            }
                        }
                    }
                }
            }

            preprocessTotal = 0
            preprocessDone = 0
            preprocessLabel = null

            // Snapshot the synchronized accumulator into a plain List
            // before handing it to Compose state, to avoid downstream
            // readers needing to hold the synchronization lock.
            val finalQueue = synchronized(queue) { queue.toList() }
            if (finalQueue.isEmpty()) {
                Toast.makeText(
                    context,
                    "No receipts identified. Unrecognized images moved to " +
                        "Pictures/Failed Coupa Uploads.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            sendQueue = finalQueue
            runSendLoop()
        }
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

    if (showUploadChooser) {
        AlertDialog(
            onDismissRequest = { showUploadChooser = false },
            title = { Text("Upload folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "What would you like to do with the images in that folder?",
                        color = Color.White.copy(alpha = 0.85f),
                    )
                    Text(
                        text = "• Send to Coupa — process each image, then email the " +
                            "detected receipts.\n" +
                            "• Sort by month — process each image and save the detected " +
                            "receipts into Pictures/Receipts Sorted/yyyy-MM/. Nothing is emailed.",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        showUploadChooser = false
                        pendingFolderAction = FolderAction.CoupaUpload
                        folderLauncher.launch(null)
                    }) { Text("Process and send to Coupa", color = MaterialTheme.colorScheme.primary) }
                    TextButton(onClick = {
                        showUploadChooser = false
                        pendingFolderAction = FolderAction.SortByMonth
                        folderLauncher.launch(null)
                    }) { Text("Process and sort by month", color = MaterialTheme.colorScheme.primary) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUploadChooser = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.8f))
                }
            },
            containerColor = Color(0xFF141414),
            titleContentColor = MaterialTheme.colorScheme.primary,
            textContentColor = Color.White,
        )
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
            preprocessDone = preprocessDone,
            preprocessTotal = preprocessTotal,
            preprocessLabel = preprocessLabel,
            onCancelSelect = { selected = emptySet() },
            onStartSend = ::beginSend,
            onClearQueue = {
                sendQueue = emptyList()
                sentCount = 0
                sendError = null
            },
            onDismissError = { sendError = null },
            onRequestDelete = { confirmDelete = true },
            onUploadFolder = { showUploadChooser = true },
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
    preprocessDone: Int,
    preprocessTotal: Int,
    preprocessLabel: String?,
    onCancelSelect: () -> Unit,
    onStartSend: () -> Unit,
    onClearQueue: () -> Unit,
    onDismissError: () -> Unit,
    onRequestDelete: () -> Unit,
    onUploadFolder: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        when {
            preprocessTotal > 0 -> {
                Text(
                    text = preprocessLabel ?: "Analyzing images",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${preprocessDone + 1} of $preprocessTotal · detecting receipts, " +
                        "cropping, reading date/location",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = {
                        if (preprocessTotal <= 0) 0f
                        else (preprocessDone.toFloat() / preprocessTotal).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                )
            }

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
