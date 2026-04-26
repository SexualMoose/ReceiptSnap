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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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

    // Default width cap. Callers can override via makePdf(maxWidth=…)
    // for higher-quality folder uploads where the source may be a
    // variable-quality photo we didn't take ourselves. See
    // LibraryScreen's folder-upload path for the higher-width variant.
    //
    // 1000 px across 80 mm thermal roll yields ~317 DPI, enough that
    // 10 pt body text is ~35 px tall (2× ML Kit's reliable-recognition
    // threshold) and 5 pt fine print is ~18 px (above threshold).
    const val DEFAULT_MAX_WIDTH_PX = 1000

    /** Wider cap used for folder uploads whose source images may be
     *  lower-quality photos than our own captures — keeps more pixels
     *  to preserve legibility. */
    const val FOLDER_UPLOAD_MAX_WIDTH_PX = 1600

    private const val JPEG_QUALITY = 72     // moderate compression, still crisp

    /** Output directory for generated PDFs; paired with file_paths.xml. */
    fun outputDir(context: Context): File =
        File(context.cacheDir, "coupa_pdfs").apply { mkdirs() }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun makePdf(
        context: Context,
        imageUri: Uri,
        outputDir: File,
        baseName: String,
        maxWidthPx: Int = DEFAULT_MAX_WIDTH_PX,
        jpegQuality: Int = JPEG_QUALITY,
        desaturate: Boolean = true,
        searchable: Boolean = true,
    ): File {
        val safeName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "receipt" }
        val outFile = File(outputDir, "$safeName.pdf")

        val color = loadBitmap(context, imageUri, maxWidthPx)
            ?: error("Could not decode $imageUri")

        // Recognize text BEFORE desaturating so the recognizer sees the
        // original color image (slightly better recall on color logos and
        // anti-aliased glyphs). Skip when [searchable] is false to save
        // ~300-500 ms on flows that don't need a text layer.
        val textLines: List<TextLineRecord> = if (searchable) {
            try {
                recognizeLineRecords(color)
            } catch (t: Throwable) {
                Log.w("PdfMaker", "OCR for searchable layer failed; embedding image only", t)
                emptyList()
            }
        } else emptyList()

        val processed = try {
            if (desaturate) desaturate(color) else color
        } catch (t: Throwable) { color.recycle(); throw t }

        val ownsProcessed = processed !== color
        if (ownsProcessed) color.recycle()

        val bmpW = processed.width
        val bmpH = processed.height

        val jpegBytes = try {
            ByteArrayOutputStream().also { out ->
                processed.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            }.toByteArray()
        } finally {
            processed.recycle()
        }

        FileOutputStream(outFile).use { os ->
            writePdf(os, jpegBytes, bmpW, bmpH, textLines)
        }
        Log.i(
            "PdfMaker",
            "Wrote ${outFile.name}: ${outFile.length() / 1024} KB " +
                "(jpeg ${jpegBytes.size / 1024} KB, ${bmpW}×${bmpH}, q=$jpegQuality, gray=$desaturate, text=${textLines.size})",
        )
        return outFile
    }

    /** One OCR'd line position in source-bitmap pixel space. Used as input
     *  to the searchable-PDF text layer. */
    private data class TextLineRecord(
        val text: String,
        // Tight bounding box in pre-desaturate bitmap pixels.
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
    )

    /** Run ML Kit on the source bitmap synchronously (we're already on a
     *  worker thread for the whole PdfMaker.makePdf call) and collect each
     *  recognized line's text + bounding box. */
    private fun recognizeLineRecords(bitmap: Bitmap): List<TextLineRecord> {
        val result = runBlocking {
            suspendCancellableCoroutine<Text> { cont ->
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { cont.resume(it) }
                    .addOnFailureListener { cont.resumeWithException(it) }
            }
        }
        val out = mutableListOf<TextLineRecord>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (box.width() <= 0 || box.height() <= 0) continue
                val text = line.text.trim()
                if (text.isEmpty()) continue
                out += TextLineRecord(
                    text = text,
                    x = box.left.coerceAtLeast(0),
                    y = box.top.coerceAtLeast(0),
                    w = box.width(),
                    h = box.height(),
                )
            }
        }
        return out
    }

    /**
     * Passthrough mode: embed the source bytes directly in a PDF with NO
     * re-encoding or downscaling. For JPEGs this is true passthrough
     * (original bytes land in the PDF stream). For PNG/WebP we decode and
     * re-encode once at near-lossless quality to get bytes the PDF reader
     * can consume via DCTDecode.
     *
     * Used for user-initiated re-upload from the Failed Coupa Uploads
     * folder — we preserve the picture as-is.
     */
    fun makePdfPassthrough(
        context: Context,
        imageUri: Uri,
        outputDir: File,
        baseName: String,
    ): File {
        val safeName = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "receipt" }
        val outFile = File(outputDir, "$safeName.pdf")

        val resolver = context.contentResolver
        val mime = resolver.getType(imageUri) ?: ""

        if (mime == "image/jpeg" || mime.endsWith("/jpeg")) {
            val rawBytes = resolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: error("Could not read $imageUri")
            val (w, h) = readJpegDimensions(rawBytes)
                ?: error("Could not read JPEG dimensions for $imageUri")
            FileOutputStream(outFile).use { os ->
                writePdf(os, rawBytes, w, h)
            }
            Log.i(
                "PdfMaker",
                "Passthrough ${outFile.name}: ${outFile.length() / 1024} KB (JPEG bytes untouched, ${w}×${h})",
            )
            return outFile
        }

        // Non-JPEG input: decode at native size, re-encode once at high quality.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(imageUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = resolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("Could not decode $imageUri")

        val w = decoded.width
        val h = decoded.height
        val jpegBytes = try {
            ByteArrayOutputStream().also { out ->
                decoded.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }.toByteArray()
        } finally { decoded.recycle() }

        FileOutputStream(outFile).use { os ->
            writePdf(os, jpegBytes, w, h)
        }
        Log.i(
            "PdfMaker",
            "Passthrough ${outFile.name}: ${outFile.length() / 1024} KB (re-encoded q=95, ${w}×${h})",
        )
        return outFile
    }

    /** Escape a Kotlin string for safe inclusion inside a PDF literal
     *  string `(…)`. PDF requires escaping of `(`, `)`, and `\`; we also
     *  drop control characters that could break parsers. Encoded as
     *  WinAnsi (best fit for Helvetica's default encoding) — non-ASCII
     *  glyphs render as `?` since we don't ship a Unicode font. The
     *  substitution doesn't matter visually because the text is invisible;
     *  it only affects search/copy. */
    private fun escapePdfString(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '(' -> sb.append("\\(")
                ')' -> sb.append("\\)")
                '\r' -> sb.append("\\r")
                '\n' -> sb.append(' ')
                '\t' -> sb.append(' ')
                else -> {
                    if (c.code in 32..126) sb.append(c)
                    else if (c.code in 0xA0..0xFF) sb.append(c)
                    else sb.append('?')
                }
            }
        }
        return sb.toString()
    }

    /** Minimal JPEG SOF-marker parser. Walks the JPEG segments until it
     *  hits a Start-Of-Frame (C0..CF, excluding DHT/JPG/DAC) which carries
     *  width/height. Fast and dependency-free. */
    private fun readJpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 4 < bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            // Skip any fill bytes (0xFF padding)
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) return null
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD9 || marker == 0xDA) return null // EOI or SOS before SOF
            val segLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            // SOF0..SOF15, but exclude 0xC4 (DHT), 0xC8 (JPG), 0xCC (DAC)
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val h = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                val w = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                return w to h
            }
            i += segLen
        }
        return null
    }

    // --- bitmap prep --------------------------------------------------------

    private fun loadBitmap(context: Context, uri: Uri, maxWidthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) > maxWidthPx) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        if (decoded.width <= maxWidthPx) return decoded

        val newW = maxWidthPx
        val newH = ((maxWidthPx.toLong() * decoded.height) / decoded.width).toInt()
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
    private fun writePdf(
        out: OutputStream,
        jpegBytes: ByteArray,
        imgW: Int,
        imgH: Int,
        textLines: List<TextLineRecord> = emptyList(),
    ) {
        val availW = PAGE_W_PT - 2 * MARGIN_PT
        val drawW = availW
        val drawH = drawW * imgH.toFloat() / imgW
        val pageH = (drawH + 2 * MARGIN_PT).coerceAtLeast(MIN_PAGE_H_PT)
        val x = MARGIN_PT
        val y = (pageH - drawH) / 2f

        // Content stream: paint the image, then optionally lay invisible
        // text on top of it at the correct line positions so PDF readers
        // and Coupa's downstream OCR can extract text without re-OCRing
        // the JPEG. Invisible text uses rendering mode 3 (clip-no-fill).
        val contentStreamBytes = buildString {
            // Image rendering
            append("q\n")
            append("%.4f 0 0 %.4f %.4f %.4f cm\n".format(drawW, drawH, x, y))
            append("/Im1 Do\n")
            append("Q\n")
            // Invisible text layer
            if (textLines.isNotEmpty()) {
                // BT … ET demarcates the text-object block.
                // 3 Tr selects "invisible text" rendering mode — glyphs
                // contribute nothing to the painted page but remain in
                // the text stream for selection / search / OCR-by-reader.
                append("BT\n")
                append("3 Tr\n")
                for (line in textLines) {
                    val pdfX = x + (line.x.toFloat() / imgW) * drawW
                    // PDF y is bottom-up; flip the image-space y.
                    val pdfYBaseline = y + drawH -
                        ((line.y + line.h).toFloat() / imgH) * drawH
                    val fontSize = (line.h.toFloat() / imgH) * drawH
                    append("/F1 %.3f Tf\n".format(fontSize.coerceAtLeast(1f)))
                    append("%.4f %.4f Td\n".format(pdfX, pdfYBaseline))
                    append("(${escapePdfString(line.text)}) Tj\n")
                    // Tm/Td reset on next line — easiest is to emit an
                    // absolute matrix per line via Tm. Td is relative;
                    // reset the text matrix between lines so positions
                    // are independent.
                    append("1 0 0 1 0 0 Tm\n")
                }
                append("ET\n")
            }
        }.toByteArray(Charsets.US_ASCII)

        val buf = ByteArrayOutputStream(jpegBytes.size + 1024)
        // Six objects total now: catalog, pages, page, content, image, font.
        val offsets = IntArray(7)  // offsets[1..6] for objects 1..6

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
                "/Resources << /XObject << /Im1 5 0 R >> " +
                "/Font << /F1 6 0 R >> " +
                "/ProcSet [/PDF /ImageC /Text] >> " +
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

        // 6: Font — built-in Helvetica. Standard 14 PDF fonts don't need
        // an embedded program; readers ship them. Plenty good for
        // invisible search-layer text.
        offsets[6] = buf.size()
        writeAscii(
            "6 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n"
        )

        // xref table
        val xrefOffset = buf.size()
        writeAscii("xref\n0 7\n")
        writeAscii("0000000000 65535 f \n")
        for (i in 1..6) {
            writeAscii("%010d 00000 n \n".format(offsets[i]))
        }

        // Trailer
        writeAscii("trailer\n<< /Size 7 /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n")

        out.write(buf.toByteArray())
    }
}
