package com.tyler.receiptsnap.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Renders a JPEG at [imageUri] onto a single-page US Letter PDF, scaled to
 * fit the page with a modest margin. Coupa's receipt ingest accepts PDFs
 * (and JPEGs directly) but PDF is the conventional attachment format for
 * expense workflows.
 */
object PdfMaker {

    // US Letter at 72 DPI
    private const val PAGE_W = 612
    private const val PAGE_H = 792
    private const val MARGIN = 36f

    /** Output directory for PDFs, matched by file_paths.xml. */
    fun outputDir(context: Context): File =
        File(context.cacheDir, "coupa_pdfs").apply { mkdirs() }

    /** Creates a PDF file named "{baseName}.pdf" under [outputDir]. Returns
     *  the written file. Bitmap is loaded and recycled internally. */
    fun makePdf(context: Context, imageUri: Uri, outputDir: File, baseName: String): File {
        val bitmap = loadBitmap(context, imageUri)
            ?: error("Could not decode $imageUri")
        try {
            val safeName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "receipt" }
            val outFile = File(outputDir, "$safeName.pdf")

            val pdf = PdfDocument()
            try {
                val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create()
                val page = pdf.startPage(info)
                val canvas = page.canvas

                val availW = PAGE_W - 2 * MARGIN
                val availH = PAGE_H - 2 * MARGIN
                val scale = minOf(availW / bitmap.width, availH / bitmap.height)
                val drawW = bitmap.width * scale
                val drawH = bitmap.height * scale
                val left = MARGIN + (availW - drawW) / 2f
                val top = MARGIN + (availH - drawH) / 2f
                canvas.drawBitmap(
                    bitmap, null,
                    RectF(left, top, left + drawW, top + drawH),
                    null,
                )

                pdf.finishPage(page)
                FileOutputStream(outFile).use { pdf.writeTo(it) }
            } finally {
                pdf.close()
            }
            return outFile
        } finally {
            bitmap.recycle()
        }
    }

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        // Cap the decoded bitmap so we don't allocate a 200MP bitmap into RAM
        // for PDF rendering — print resolution doesn't benefit from that.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxSide = 2400  // at 300 DPI, ~8x6 inches — plenty for US Letter
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
