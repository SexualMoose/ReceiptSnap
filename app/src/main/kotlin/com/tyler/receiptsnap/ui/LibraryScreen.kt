package com.tyler.receiptsnap.ui

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LibraryItem(
    val uri: Uri,
    val name: String,
    val dateAddedSec: Long,
)

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Bumping this int re-runs the LaunchedEffect that reloads items.
    var refreshTick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }

    LaunchedEffect(refreshTick) {
        items = withContext(Dispatchers.IO) { loadItems(context) }
    }

    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshTick += 1
            }
        }
        resolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        Text(
            text = "Library · ${items.size}",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(16.dp),
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
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.DarkGray),
                    ) {
                        AsyncImage(
                            model = item.uri,
                            contentDescription = item.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
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
