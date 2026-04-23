package com.tyler.receiptsnap.storage

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.tyler.receiptsnap.processing.ReceiptParser
import java.io.OutputStream
import java.time.LocalDate
import java.util.Locale

/**
 * Writes a cropped receipt bitmap into the shared Pictures/ReceiptSnap/ collection
 * via MediaStore. Filenames follow `{yyyy-MM-dd}_{Merchant}.jpg` when both are
 * available, with graceful degradation, and are always made unique against the
 * existing set so nothing is ever overwritten.
 */
object ReceiptStorage {

    private const val RELATIVE_DIR = "Pictures/ReceiptSnap"
    private const val FALLBACK_BASE = "receipt"

    data class SaveResult(val uri: Uri, val displayName: String)

    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        merchant: String?,
        date: LocalDate?,
    ): SaveResult {
        val resolver = context.contentResolver
        val baseName = buildBaseName(merchant, date)
        val uniqueName = allocateUniqueName(resolver, baseName, merchant, date)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$uniqueName.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val picturesDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val targetDir = java.io.File(picturesDir, "ReceiptSnap").apply { mkdirs() }
                put(
                    MediaStore.Images.Media.DATA,
                    java.io.File(targetDir, "$uniqueName.jpg").absolutePath,
                )
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val uri = resolver.insert(collection, values)
            ?: error("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { out ->
                writeJpeg(bitmap, out)
            } ?: error("Could not open output stream for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val finalize = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, finalize, null, null)
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }

        return SaveResult(uri, "$uniqueName.jpg")
    }

    private fun writeJpeg(bitmap: Bitmap, out: OutputStream) {
        // 95% is the sweet spot: near-lossless visually while keeping multi-MB
        // receipts comfortably under 10MB even at 200MP pipeline output.
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        out.flush()
    }

    private fun buildBaseName(merchant: String?, date: LocalDate?): String {
        val sanitizedMerchant = merchant?.let(::sanitize)?.takeIf { it.isNotBlank() }
        val dateStr = date?.let(ReceiptParser::formatDateForFilename)
        return when {
            dateStr != null && sanitizedMerchant != null -> "${dateStr}_$sanitizedMerchant"
            dateStr != null -> dateStr
            sanitizedMerchant != null -> sanitizedMerchant
            else -> FALLBACK_BASE
        }
    }

    /** Pick a filename not already present in Pictures/ReceiptSnap/. We check
     *  MediaStore and always append a " - N" suffix for the fallback branch
     *  per the product spec; for the regular branch, we only add " (N)" if a
     *  collision actually exists. */
    private fun allocateUniqueName(
        resolver: ContentResolver,
        base: String,
        merchant: String?,
        date: LocalDate?,
    ): String {
        val isFallback = merchant == null && date == null
        if (isFallback) {
            // Spec: "receipt - 1", "receipt - 2", ... always numbered.
            var n = 1
            while (true) {
                val candidate = "$base - $n"
                if (!exists(resolver, "$candidate.jpg")) return candidate
                n++
            }
        } else {
            if (!exists(resolver, "$base.jpg")) return base
            var n = 2
            while (true) {
                val candidate = "$base ($n)"
                if (!exists(resolver, "$candidate.jpg")) return candidate
                n++
            }
        }
    }

    private fun exists(resolver: ContentResolver, displayName: String): Boolean {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf(displayName, "$RELATIVE_DIR%")
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        return resolver.query(collection, projection, selection, args, null)?.use {
            it.moveToFirst()
        } ?: false
    }

    /** Filesystem-safe filename fragment. */
    private fun sanitize(raw: String): String {
        val trimmed = raw.trim().take(48)
        val stripped = trimmed.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        return stripped
            .split(" ")
            .joinToString(" ") {
                it.replaceFirstChar { c -> c.titlecase(Locale.US) }
            }
    }
}
