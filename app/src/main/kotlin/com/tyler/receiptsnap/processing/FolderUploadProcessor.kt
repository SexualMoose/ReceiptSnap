package com.tyler.receiptsnap.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.tyler.receiptsnap.storage.ReceiptStorage
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * For the "Upload folder to Coupa" flow: runs each picked image through
 * the same receipt-detection + parse pipeline that on-device captures
 * use, producing zero or more warped, structured-name crops ready for
 * the SMTP send loop.
 *
 * Contract mirrors DocumentDetector.detect() → if nothing identifiable
 * as a receipt turns up, the caller should route the source into the
 * Failed Coupa Uploads archive instead of sending anything.
 */
object FolderUploadProcessor {

    private const val TAG = "FolderUploadProcessor"

    /** Cache directory for warped receipt crops waiting for SMTP send. */
    fun cropsDir(context: Context): File =
        File(context.cacheDir, "folder_crops").apply { mkdirs() }

    data class ProcessedCrop(
        val tempFile: File,
        val displayName: String,  // e.g. "2026-04-23_London_meal.jpg"
    )

    sealed interface Result {
        data class Detected(val crops: List<ProcessedCrop>) : Result
        data object NoReceipts : Result
        data class LoadFailed(val reason: String) : Result
    }

    suspend fun process(context: Context, sourceUri: Uri): Result {
        val bitmap = try {
            loadFullBitmap(context, sourceUri)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decode $sourceUri", t); null
        } ?: return Result.LoadFailed("Could not decode image")

        return try {
            val quads = try {
                DocumentDetector.detect(bitmap)
            } catch (t: Throwable) {
                Log.e(TAG, "detect() failed for $sourceUri", t); emptyList()
            }
            if (quads.isEmpty()) return Result.NoReceipts

            val crops = mutableListOf<ProcessedCrop>()
            val usedNames = mutableSetOf<String>()
            for (quad in quads) {
                val warped = try {
                    DocumentDetector.warp(bitmap, quad)
                } catch (t: Throwable) {
                    Log.w(TAG, "warp failed; skipping quad", t); continue
                }

                val info = try {
                    ReceiptParser.parse(warped)
                } catch (t: Throwable) {
                    Log.w(TAG, "parse failed; using fallback name", t)
                    ReceiptParser.Info(null, null, false, "")
                }

                val baseName = ReceiptStorage.buildDisplayName(info.date, info.location, info.isMeal)
                val uniqueName = uniqify(baseName, usedNames)
                usedNames += uniqueName

                val tmpFile = File(cropsDir(context), "${UUID.randomUUID()}.jpg").also { f ->
                    FileOutputStream(f).use { os ->
                        warped.compress(Bitmap.CompressFormat.JPEG, 92, os)
                    }
                }
                warped.recycle()
                crops += ProcessedCrop(tempFile = tmpFile, displayName = "$uniqueName.jpg")
            }

            if (crops.isEmpty()) Result.NoReceipts
            else Result.Detected(crops)
        } finally {
            bitmap.recycle()
        }
    }

    /** Make sure two crops from the same source don't collide. The
     *  display-name builder doesn't do uniqueness because it's usually
     *  called right before MediaStore insertion which handles collisions
     *  via ` (2)` suffixes. */
    private fun uniqify(base: String, used: Set<String>): String {
        if (base !in used) return base
        var n = 2
        while ("$base ($n)" in used) n++
        return "$base ($n)"
    }

    /**
     * Decode the source bitmap at a reasonable working size for detection.
     * DocumentDetector expects ARGB_8888 and handles its own internal
     * downscaling for OCR; we just need enough pixels for it to see
     * receipt edges cleanly.
     */
    private fun loadFullBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Keep the source at native resolution when it's under ~16 MP.
        // Anything larger gets sampled down to stay memory-safe even with
        // largeHeap on; detection stays reliable well below 16 MP.
        val maxSide = 5000
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxSide) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }
}
