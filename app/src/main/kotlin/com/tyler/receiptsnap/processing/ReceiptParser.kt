package com.tyler.receiptsnap.processing

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Pulls a merchant name and transaction date out of an already-cropped receipt
 * image. Merchant heuristic: the first reasonable line near the top of the
 * receipt (uppercase-heavy, alphabetic, not a date/total/amount). Date heuristic:
 * regex over a battery of common on-receipt formats, first hit wins.
 */
object ReceiptParser {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class ReceiptInfo(val merchant: String?, val date: LocalDate?)

    suspend fun parse(bitmap: Bitmap): ReceiptInfo {
        val text = recognize(bitmap)
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        return ReceiptInfo(
            merchant = detectMerchant(lines),
            date = detectDate(lines),
        )
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    // --- merchant -----------------------------------------------------------

    private val NON_MERCHANT_WORDS = setOf(
        "receipt", "invoice", "order", "thank you", "thanks", "welcome",
        "customer", "copy", "tel", "phone", "fax", "address", "street",
        "cashier", "cash", "visa", "mastercard", "amex", "debit", "credit",
        "total", "subtotal", "tax", "change", "balance", "amount",
    )

    private fun detectMerchant(lines: List<String>): String? {
        // Scan only the first ~8 lines — merchant is conventionally printed at
        // the very top of a receipt. Going deeper would risk picking up a
        // department name or item description.
        val head = lines.take(8)
        val candidates = head.mapIndexedNotNull { idx, raw ->
            val line = raw.replace("\\s+".toRegex(), " ").trim()
            if (!isMerchantLikely(line)) return@mapIndexedNotNull null
            val score = scoreMerchant(line) - idx * 2 // prefer earlier lines
            line to score
        }
        val best = candidates.maxByOrNull { it.second } ?: return null
        return titleCase(best.first)
    }

    private fun isMerchantLikely(line: String): Boolean {
        if (line.length < 3 || line.length > 40) return false
        val lower = line.lowercase(Locale.US)
        if (NON_MERCHANT_WORDS.any { lower.contains(it) }) return false
        if (DATE_PATTERNS.any { it.containsMatchIn(line) }) return false
        // Must contain letters, not be purely digits/punctuation.
        val letterCount = line.count { it.isLetter() }
        if (letterCount < 3) return false
        // Phone numbers or IDs
        val digitCount = line.count { it.isDigit() }
        if (digitCount > letterCount) return false
        return true
    }

    private fun scoreMerchant(line: String): Int {
        var score = 0
        val letters = line.filter { it.isLetter() }
        if (letters.isEmpty()) return -100
        val uppercaseRatio = letters.count { it.isUpperCase() }.toDouble() / letters.length
        score += (uppercaseRatio * 10).toInt()              // caps-heavy names win
        if (line.length in 6..25) score += 3                // typical merchant length
        if (line.any { it == '&' || it == '\'' }) score += 1
        if (line.contains(Regex("\\d{3,}"))) score -= 5     // long digit runs penalised
        return score
    }

    private fun titleCase(s: String): String {
        val lower = s.lowercase(Locale.US)
        return lower.split(" ").joinToString(" ") { w ->
            if (w.isEmpty()) w else w.replaceFirstChar { it.uppercase(Locale.US) }
        }
    }

    // --- date ---------------------------------------------------------------

    // Ordering matters: more specific patterns first to avoid partial matches.
    private val DATE_PATTERNS = listOf(
        Regex("\\b(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b"),                 // 2026-04-23
        Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})\\b"),                 // 04/23/2026 or 23/04/2026
        Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2})\\b"),                 // 04/23/26
        Regex("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{2,4})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{2,4})\\b", RegexOption.IGNORE_CASE),
    )

    private val MONTHS: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private fun detectDate(lines: List<String>): LocalDate? {
        for (line in lines) {
            for (pattern in DATE_PATTERNS) {
                val m = pattern.find(line) ?: continue
                val parsed = parseMatch(pattern, m) ?: continue
                if (parsed.year in 1990..2099) return parsed
            }
        }
        return null
    }

    private fun parseMatch(pattern: Regex, m: MatchResult): LocalDate? {
        val g = m.groupValues
        return try {
            when {
                // 2026-04-23
                pattern.pattern.startsWith("\\b(\\d{4})") ->
                    LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())

                // 04/23/2026 or 23/04/2026 — disambiguate by range
                pattern.pattern.contains("(\\d{4})\\b") &&
                    pattern.pattern.contains("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})") -> {
                    val a = g[1].toInt(); val b = g[2].toInt(); val y = g[3].toInt()
                    val (month, day) = if (a > 12 && b <= 12) b to a else a to b
                    LocalDate.of(y, month, day)
                }

                // 2-digit year
                pattern.pattern.contains("(\\d{2})\\b") &&
                    pattern.pattern.contains("(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2})") -> {
                    val a = g[1].toInt(); val b = g[2].toInt()
                    val y = g[3].toInt().let { if (it < 70) 2000 + it else 1900 + it }
                    val (month, day) = if (a > 12 && b <= 12) b to a else a to b
                    LocalDate.of(y, month, day)
                }

                // Month-name leading: "Apr 23, 2026"
                pattern.pattern.startsWith("\\b(Jan") -> {
                    val month = MONTHS[g[1].lowercase(Locale.US).take(3)] ?: return null
                    val day = g[2].toInt()
                    val year = expandYear(g[3].toInt())
                    LocalDate.of(year, month, day)
                }

                // Day-leading: "23 Apr 2026"
                else -> {
                    val day = g[1].toInt()
                    val month = MONTHS[g[2].lowercase(Locale.US).take(3)] ?: return null
                    val year = expandYear(g[3].toInt())
                    LocalDate.of(year, month, day)
                }
            }
        } catch (_: Throwable) { null }
    }

    private fun expandYear(y: Int): Int = when {
        y >= 1000 -> y
        y < 70 -> 2000 + y
        else -> 1900 + y
    }

    fun formatDateForFilename(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
}
