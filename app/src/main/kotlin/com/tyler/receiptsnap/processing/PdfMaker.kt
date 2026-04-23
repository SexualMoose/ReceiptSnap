package com.tyler.receiptsnap.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Produces a minimal, single-page PDF containing the receipt as an embedded
 * grayscale JPEG. Hand-rolled rather than routed through `android.graphics
 * .pdf.PdfDocument` because the framework class stores bitmap data
 * effectively losslessly inside the PDF's content stream, producing 1–3 MB
 * attachments per receipt even when the source is a simple photo of paper.
 *
 * This implementation:
 *   1. Downscales the bitmap to a sensible target long-side (receipts are
 *      readable at ~1400 px).
 *   2. Desaturates to grayscale via a color-matrix pass. Receipt ink carries
 *      no useful chroma; removing it lets the JPEG encoder collapse the
 *      chroma planes to near-zero bytes.
 *   3. JPEG-encodes at moderate quality (72) — plenty for legible text.
 *   4. Writes a minimal PDF structure (catalog / pages / page / content
 *      stream / image XObject) with the JPEG bytes embedded directly via
 *      /Filter /DCTDecode. No third-party dependency, no extra Paint-canvas
 *      record in the page stream.
 *
 * Typical output: 60–250 KB per receipt, versus 1–3 MB before.
 */
object PdfMaker {

    // Page width is fixed at 8.5 in (612 pt); page height scales to match
    // the receipt's aspect ratio. A 3:1 receipt gets a 612×1836 pt page, a
    // 10:1 gets 612×6120. Viewers handle arbitrary page sizes fine and
    // this avoids the "skinny strip in a sea of whitespace" problem that
    // fixed-Letter layout produces for long receipts.
    private const val PAGE_W_PT = 612f
    private const val MIN_PAGE_H_PT = 396f   // half-Letter floor for very short receipts
    private const val MARGIN_PT = 12f

    // Cap the WIDTH, not the longest side. Receipt widths are fairly
    // consistent (thermal roll is typically ~80 mm) but length varies
    // enormously — capping max-side would squeeze text on long receipts
    // until it becomes illegible. Capping width preserves text pixel
    // density on every receipt at the cost of a slightly larger file
    // for unusually long ones.
    //
    // 1000 px across 80 mm yields ~317 DPI, enough that 10 pt body text
    // is ~35 px tall (2× ML Kit's reliable-recognition threshold) and
    // even 5 pt fine-print terms are ~18 px (above the threshold). Below
    // 800 px, fine-print OCR starts to miss.
    private const val MAX_WIDTH_PX = 1000
    private const val JPEG_QUALITY = 72     // moderate compression, still crisp

    /** Output directory for generated PDFs; paired with file_paths.xml. */
    fun outputDir(context: Context): File =
        File(context.cacheDir, "coupa_pdfs").apply { mkdirs() }

    fun makePdf(context: Context, imageUri: Uri, outputDir: File, baseName: String): File {
        val safeName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "receipt" }
        val outFile = File(outputDir, "$safeName.pdf")

        val color = loadBitmap(context, imageUri)
            ?: error("Could not decode $imageUri")

        val gray = try {
            desaturate(color)
        } finally {
            color.recycle()
        }

        // Capture dimensions before recycling — we still need them for the
        // PDF's image-XObject header.
        val bmpW = gray.width
        val bmpH = gray.height

        val jpegBytes = try {
            ByteArrayOutputStream().also { out ->
                gray.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }.toByteArray()
        } finally {
            gray.recycle()
        }

        FileOutputStream(outFile).use { os ->
            writePdf(os, jpegBytes, bmpW, bmpH)
        }
        Log.i(
            "PdfMaker",
            "Wrote ${outFile.name}: ${outFile.length() / 1024} KB " +
                "(jpeg ${jpegBytes.size / 1024} KB, ${bmpW}×${bmpH})",
        )
        return outFile
    }

    // --- bitmap prep --------------------------------------------------------

    private fun loadBitmap(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Two-step scale: first use inSampleSize to get reasonably close
        // without decoding the full-size bitmap, then createScaledBitmap
        // for an exact width match. inSampleSize only supports powers of
        // two, so we stop halving once the decoded width would no longer
        // safely exceed our target.
        var sample = 1
        while (bounds.outWidth / (sample * 2) > MAX_WIDTH_PX) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // RGB_565 halves the decoded-bitmap RAM compared to ARGB_8888.
            // Once desaturated and JPEG-compressed, the output is identical
            // for our purposes.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        if (decoded.width <= MAX_WIDTH_PX) return decoded

        // Width still over target after inSampleSize — scale precisely.
        // Height scales proportionally so aspect ratio is preserved for
        // long receipts (a 3:1 receipt stays 3:1; a 10:1 stays 10:1).
        val newW = MAX_WIDTH_PX
        val newH = ((MAX_WIDTH_PX.toLong() * decoded.height) / decoded.width).toInt()
            .coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(decoded, newW, newH, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /** Saturate to 0 via ColorMatrix. The output bitmap is still RGB but
     *  every pixel has R=G=B, so the downstream JPEG encoder's chroma
     *  subsampling collapses to near-zero bytes. */
    private fun desaturate(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isFilterBitmap = true
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    // --- PDF writer ---------------------------------------------------------

    /**
     * Emits a minimal PDF-1.4 document with five objects: catalog, pages
     * root, page, content stream, and a JPEG-encoded image XObject. The
     * content stream places a single image on the page, fit to margins with
     * preserved aspect ratio.
     */
    private fun writePdf(out: OutputStream, jpegBytes: ByteArray, imgW: Int, imgH: Int) {
        val availW = PAGE_W_PT - 2 * MARGIN_PT
        val drawW = availW
        val drawH = drawW * imgH.toFloat() / imgW
        val pageH = (drawH + 2 * MARGIN_PT).coerceAtLeast(MIN_PAGE_H_PT)
        val x = MARGIN_PT
        val y = (pageH - drawH) / 2f

        // Content stream: save graphics state, set up transform so the unit
        // 1×1 image covers (drawW × drawH), paint the image, restore.
        val contentStreamBytes = buildString {
            append("q\n")
            append("%.4f 0 0 %.4f %.4f %.4f cm\n".format(drawW, drawH, x, y))
            append("/Im1 Do\n")
            append("Q\n")
        }.toByteArray(Charsets.US_ASCII)

        val buf = ByteArrayOutputStream(jpegBytes.size + 1024)
        val offsets = IntArray(6)  // offsets[1..5] for objects 1..5

        fun writeAscii(s: String) = buf.write(s.toByteArray(Charsets.US_ASCII))
        fun writeBytes(b: ByteArray) = buf.write(b)

        // PDF header + binary marker (signals that the file contains
        // non-ASCII bytes, which our image stream will).
        writeAscii("%PDF-1.4\n")
        buf.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))

        // 1: Catalog
        offsets[1] = buf.size()
        writeAscii("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")

        // 2: Pages root
        offsets[2] = buf.size()
        writeAscii("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")

        // 3: Page — dynamically sized so the receipt fills the page
        offsets[3] = buf.size()
        writeAscii(
            "3 0 obj\n<< /Type /Page /Parent 2 0 R " +
                "/MediaBox [0 0 %.2f %.2f] ".format(PAGE_W_PT, pageH) +
                "/Resources << /XObject << /Im1 5 0 R >> /ProcSet [/PDF /ImageC] >> " +
                "/Contents 4 0 R >>\nendobj\n"
        )

        // 4: Content stream
        offsets[4] = buf.size()
        writeAscii("4 0 obj\n<< /Length ${contentStreamBytes.size} >>\nstream\n")
        writeBytes(contentStreamBytes)
        writeAscii("\nendstream\nendobj\n")

        // 5: Image XObject (JPEG). We declare DeviceRGB because Bitmap.compress
        // produces a 3-channel JPEG even when pixels are grayscale; the chroma
        // planes compress to almost nothing in practice. DCTDecode means
        // "JPEG" to PDF readers.
        offsets[5] = buf.size()
        writeAscii(
            "5 0 obj\n<< /Type /XObject /Subtype /Image " +
                "/Width $imgW /Height $imgH " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 " +
                "/Filter /DCTDecode /Length ${jpegBytes.size} >>\nstream\n"
        )
        writeBytes(jpegBytes)
        writeAscii("\nendstream\nendobj\n")

        // xref table
        val xrefOffset = buf.size()
        writeAscii("xref\n0 6\n")
        writeAscii("0000000000 65535 f \n")
        for (i in 1..5) {
            writeAscii("%010d 00000 n \n".format(offsets[i]))
        }

        // Trailer
        writeAscii("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

        out.write(buf.toByteArray())
    }
}
