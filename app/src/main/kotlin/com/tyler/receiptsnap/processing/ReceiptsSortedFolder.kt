package com.tyler.receiptsnap.processing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.time.LocalDate

/**
 * Writes a warped/cropped receipt into `Pictures/Receipts Sorted/yyyy-MM/`
 * — the destination used by the folder upload's "sort into month folders"
 * option. Receipts whose OCR didn't yield a date bucket into `Undated/`
 * so they're still surfaced and easy to find later.
 *
 * yyyy-MM (zero-padded) keeps the folders sorted both alphabetically and
 * chronologically — `2025-09` comes before `2026-04` in every file browser.
 */
object ReceiptsSortedFolder {

    private const val TAG = "ReceiptsSortedFolder"
    private const val ROOT_DIR = "Pictures/Receipts Sorted"

    /** Copies [tempFile] into the yyyy-MM subfolder for [date]. Returns the
     *  new MediaStore URI on success, or null if the write failed. */
    fun saveToMonthFolder(
        context: Context,
        tempFile: File,
        date: LocalDate?,
        displayName: String,
    ): Uri? {
        val folder = monthFolderFor(date)
        val relativeDir = "$ROOT_DIR/$folder"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeFromName(displayName))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeDir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val sub = relativeDir.removePrefix("Pictures/")
                val dir = File(pictures, sub).apply { mkdirs() }
                put(MediaStore.Images.Media.DATA, File(dir, displayName).absolutePath)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val resolver = context.contentResolver
        val destUri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "insert returned null for $relativeDir / $displayName")
            return null
        }

        val copied = try {
            resolver.openOutputStream(destUri)?.use { out ->
                FileInputStream(tempFile).use { it.copyTo(out) }
                true
            } ?: false
        } catch (t: Throwable) {
            Log.e(TAG, "copy failed for $tempFile → $destUri", t)
            false
        }
        if (!copied) {
            runCatching { resolver.delete(destUri, null, null) }
            return null
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            runCatching { resolver.update(destUri, finalize, null, null) }
        }
        return destUri
    }

    private fun monthFolderFor(date: LocalDate?): String {
        if (date == null) return "Undated"
        return "%04d-%02d".format(date.year, date.monthValue)
    }

    private fun mimeFromName(name: String): String {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".webp") -> "image/webp"
            lower.endsWith(".heic") || lower.endsWith(".heif") -> "image/heif"
            else -> "image/jpeg"
        }
    }
}
