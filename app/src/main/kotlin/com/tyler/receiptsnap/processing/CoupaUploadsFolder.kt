package com.tyler.receiptsnap.processing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log

/**
 * Moves a just-uploaded external receipt into `Pictures/Coupa Uploads/` so
 * the user has a clean "already sent" archive separate from their input
 * folder. Used only for the folder-upload path; in-app captures already
 * live under `Pictures/ReceiptSnap/`.
 *
 * Creates the destination directory automatically via MediaStore
 * RELATIVE_PATH (Android Q+) or a plain mkdirs() on legacy builds.
 *
 * On any failure we keep the source intact — partial success (copied but
 * didn't delete) leaves the user with the file in both places, which is
 * better than losing it.
 */
object CoupaUploadsFolder {

    private const val TAG = "CoupaUploadsFolder"
    private const val RELATIVE_DIR = "Pictures/Coupa Uploads"

    fun moveToArchive(
        context: Context,
        sourceUri: Uri,
        displayName: String,
    ): Boolean {
        val resolver = context.contentResolver
        val mime = mimeFromName(displayName)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val dir = java.io.File(pictures, "Coupa Uploads").apply { mkdirs() }
                put(
                    MediaStore.Images.Media.DATA,
                    java.io.File(dir, displayName).absolutePath,
                )
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

        val destUri = resolver.insert(collection, values) ?: run {
            Log.w(TAG, "insert returned null for $displayName")
            return false
        }

        val copied = try {
            resolver.openOutputStream(destUri)?.use { out ->
                resolver.openInputStream(sourceUri)?.use { inStream ->
                    inStream.copyTo(out)
                    true
                }
            } ?: false
        } catch (t: Throwable) {
            Log.e(TAG, "copy failed for $sourceUri → $destUri", t)
            false
        }

        if (!copied) {
            // Clean up the placeholder MediaStore row so we don't leave an
            // empty "Coupa Uploads" entry in the user's gallery.
            runCatching { resolver.delete(destUri, null, null) }
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            runCatching { resolver.update(destUri, finalize, null, null) }
        }

        // Now attempt to delete the source. The tree-picker gave us R/W
        // permission, so this should succeed — but if it doesn't (e.g., the
        // file was removed externally during the send) we still count the
        // move as a partial success because the archive copy is in place.
        val sourceDeleted = try {
            DocumentsContract.deleteDocument(resolver, sourceUri)
        } catch (t: Throwable) {
            Log.w(TAG, "source delete failed for $sourceUri", t); false
        }
        if (!sourceDeleted) {
            Log.w(TAG, "Archived $displayName but couldn't delete source $sourceUri")
        }
        return true
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
