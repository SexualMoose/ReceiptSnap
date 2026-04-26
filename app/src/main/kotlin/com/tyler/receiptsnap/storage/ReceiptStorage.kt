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
 * Saves a cropped receipt bitmap to Pictures/ReceiptSnap/ via MediaStore.
 *
 * Naming contract:
 *  - Base is joined from any of date / location / "meal", in that order,
 *    separated by underscores. Examples:
 *      date + loc + meal → `2026-04-23_Seattle_meal.jpg`
 *      date + meal        → `2026-04-23_meal.jpg`
 *      loc only           → `Seattle.jpg`
 *      meal only          → `meal - {N}.jpg` (always numbered)
 *  - When nothing identifiable is extracted → `receipt - {N}.jpg` (numbered).
 *  - On collision with an existing MediaStore entry we append ` (2)`, ` (3)`, …
 *    to the base rather than overwriting.
 */
object ReceiptStorage {

    private const val RELATIVE_DIR = "Pictures/ReceiptSnap"
    private const val FALLBACK_BASE = "receipt"
    private const val MEAL_ONLY_BASE = "meal"

    data class SaveResult(val uri: Uri, val displayName: String)

    suspend fun save(
        context: Context,
        bitmap: Bitmap,
        date: LocalDate?,
        location: String?,
        isMeal: Boolean,
        total: ReceiptParser.Total? = null,
    ): SaveResult {
        val resolver = context.contentResolver
        val baseName = buildBaseName(date, location, isMeal, total)
        val uniqueName = allocateUniqueName(resolver, baseName, date, location, isMeal)

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
            resolver.openOutputStream(uri)?.use { out -> writeJpeg(bitmap, out) }
                ?: error("Could not open output stream for $uri")

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
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        out.flush()
    }

    /** Public variant for callers (e.g. folder-upload pre-processing) that
     *  need the same naming convention but write to their own destination. */
    fun buildDisplayName(
        date: LocalDate?,
        location: String?,
        isMeal: Boolean,
        total: ReceiptParser.Total? = null,
    ): String = buildBaseName(date, location, isMeal, total)

    private fun buildBaseName(
        date: LocalDate?,
        location: String?,
        isMeal: Boolean,
        total: ReceiptParser.Total? = null,
    ): String {
        val sanitizedLoc = location?.let(::sanitize)?.takeIf { it.isNotBlank() }
        val dateStr = date?.let(ReceiptParser::formatDateForFilename)
        val parts = mutableListOf<String>()
        if (dateStr != null) parts += dateStr
        if (sanitizedLoc != null) parts += sanitizedLoc
        // Amount embedded in filename helps when corporate audit asks
        // "find me the receipt for the £12.65 charge from April".
        if (total != null) parts += total.compactForFilename()
        if (isMeal) parts += "meal"
        return when {
            parts.isEmpty() -> FALLBACK_BASE
            parts.size == 1 && parts[0] == "meal" -> MEAL_ONLY_BASE
            else -> parts.joinToString("_")
        }
    }

    private fun allocateUniqueName(
        resolver: ContentResolver,
        base: String,
        date: LocalDate?,
        location: String?,
        isMeal: Boolean,
    ): String {
        // When the only identifying fact is that it's a meal (or nothing at
        // all), always number. Multiple anonymous meals in a row would
        // otherwise have to pile up as "meal (2)", "meal (3)" which is ugly.
        val alwaysNumber = (date == null && location == null)
        if (alwaysNumber) {
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
