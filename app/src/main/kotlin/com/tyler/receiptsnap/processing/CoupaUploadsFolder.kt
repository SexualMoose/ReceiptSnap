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
 * Helpers for routing external-upload receipts into per-outcome archive
 * folders under `Pictures/`:
 *
 *   `Pictures/Coupa Uploads/`         — successfully sent to Coupa
 *   `Pictures/Failed Coupa Uploads/`  — image we couldn't identify as a
 *                                       receipt (no DocumentDetector hit)
 *
 * MediaStore auto-creates the destination directory on Android Q+. On
 * legacy builds we mkdirs() before inserting.
 *
 * Partial-success safety: if the copy stream fails we roll back the
 * MediaStore placeholder so the gallery doesn't show an empty row. If
 * we successfully copied but couldn't delete the source, we keep both
 * — a dup is better than losing a receipt.
 */
object CoupaUploadsFolder {

    private const val TAG = "CoupaUploadsFolder"

    const val COUPA_UPLOADS_DIR = "Pictures/Coupa Uploads"
    const val FAILED_UPLOADS_DIR = "Pictures/Failed Coupa Uploads"

    /** Folder display name that activates passthrough-re-upload mode when
     *  a user picks it via the SAF tree picker. */
    const val FAILED_UPLOADS_FOLDER_NAME = "Failed Coupa Uploads"

    fun moveToArchive(context: Context, sourceUri: Uri, displayName: String): Boolean =
        moveToDir(context, sourceUri, displayName, COUPA_UPLOADS_DIR)

    fun moveToFailed(context: Context, sourceUri: Uri, displayName: String): Boolean =
        moveToDir(context, sourceUri, displayName, FAILED_UPLOADS_DIR)

    private fun moveToDir(
        context: Context,
        sourceUri: Uri,
        displayName: String,
        relativeDir: String,
    ): Boolean {
        val resolver = context.contentResolver
        val mime = mimeFromName(displayName)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativeDir)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val pictures = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_PICTURES
                )
                val subDir = relativeDir.removePrefix("Pictures/")
                val dir = java.io.File(pictures, subDir).apply { mkdirs() }
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
            Log.w(TAG, "insert returned null ($relativeDir / $displayName)")
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
            runCatching { resolver.delete(destUri, null, null) }
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            runCatching { resolver.update(destUri, finalize, null, null) }
        }

        val sourceDeleted = try {
            DocumentsContract.deleteDocument(resolver, sourceUri)
        } catch (t: Throwable) {
            Log.w(TAG, "source delete failed for $sourceUri", t); false
        }
        if (!sourceDeleted) {
            Log.w(TAG, "Archived $displayName to $relativeDir but couldn't delete source $sourceUri")
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
