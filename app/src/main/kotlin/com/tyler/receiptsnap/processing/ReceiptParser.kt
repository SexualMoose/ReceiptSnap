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
 * OCRs a cropped receipt once and extracts everything the filename generator
 * needs out of that single pass: the transaction date, a location (city from
 * the printed address, or a country if only that's present), and a flag for
 * whether the receipt looks like a meal.
 */
object ReceiptParser {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class Info(
        val date: LocalDate?,
        val location: String?,
        val isMeal: Boolean,
        val total: Total?,
        val rawText: String,
    )

    /** Total amount printed on the receipt. Pulled from a TOTAL/AMOUNT
     *  DUE/BALANCE label whenever possible. [currencyCode] is null when
     *  we couldn't infer a currency from symbols, ISO codes, or context. */
    data class Total(
        val amount: String,        // normalized "12.65" form (period decimal)
        val currencyCode: String?, // ISO 4217: USD / GBP / EUR / etc.
    ) {
        fun formatted(): String =
            if (currencyCode.isNullOrBlank()) amount else "$currencyCode $amount"

        /** Compact form for filenames: "GBP12.65", or just "12.65" when no
         *  currency is known. No spaces; safe in any filesystem. */
        fun compactForFilename(): String =
            if (currencyCode.isNullOrBlank()) amount else "$currencyCode$amount"
    }

    suspend fun parse(bitmap: Bitmap): Info {
        val text = runCatching { recognize(bitmap) }.getOrDefault("")
        return Info(
            date = detectDateInText(text),
            location = AddressParser.extractLocation(text),
            isMeal = detectMeal(text),
            total = detectTotal(text),
            rawText = text,
        )
    }

    private suspend fun recognize(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    fun formatDateForFilename(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    // --- total + currency ---------------------------------------------------

    /** Total-line keywords in priority order. The first one that matches a
     *  line wins; receipts that print BOTH "SUBTOTAL" and "TOTAL" will pick
     *  TOTAL because SUBTOTAL doesn't appear in this list (subtotal is the
     *  amount before tax — corporate audit cares about the actual total). */
    private val TOTAL_KEYWORDS = listOf(
        "GRAND TOTAL",
        "TOTAL DUE",
        "AMOUNT DUE",
        "BALANCE DUE",
        "AMOUNT PAID",
        "OUT TOTAL",
        "INCL VAT",
        "INCL TAX",
        "TOTAL",
        "BALANCE",
        "AMOUNT",
    )

    private val CURRENCY_SYMBOL_TO_CODE = mapOf(
        "$" to "USD",
        "£" to "GBP",
        "€" to "EUR",
        "¥" to "JPY",
        "₹" to "INR",
        "₩" to "KRW",
        "₽" to "RUB",
        "₺" to "TRY",
    )

    private val CURRENCY_CODES = setOf(
        "USD", "GBP", "EUR", "JPY", "CAD", "AUD", "INR", "KRW", "CHF",
        "CNY", "HKD", "SGD", "NZD", "MXN", "BRL", "SEK", "NOK", "DKK",
        "PLN", "ZAR", "AED", "SAR", "TRY", "RUB",
    )

    /** Find the printed total. Walks lines bottom-up because totals
     *  conventionally print near the end of a receipt; the first label
     *  match wins. Returns null when no labeled total amount can be
     *  found — uncommon for proper receipts but possible for partial
     *  captures or hand-written notes. */
    fun detectTotal(text: String): Total? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null

        for (keyword in TOTAL_KEYWORDS) {
            // Pattern: keyword (with arbitrary punctuation/space) then
            // optionally a currency symbol or 3-letter code, then the
            // amount (1–6 integer digits, period or comma decimal,
            // 2 fractional digits).
            val pattern = Regex(
                "\\b" + Regex.escape(keyword) + "\\b" +
                    "[^\\d]{0,40}" +                       // up to 40 chars of separator/label noise
                    "([\\$£€¥₹₩₽₺]|[A-Z]{3})?\\s*" +       // optional currency before
                    "([\\$£€¥₹₩₽₺])?\\s*" +                // optional symbol immediately before number
                    "(\\d{1,6}[.,]\\d{2})\\b",             // amount
                RegexOption.IGNORE_CASE,
            )
            // Search bottom-up — totals are at the end of receipts.
            for (line in lines.asReversed()) {
                val m = pattern.find(line) ?: continue
                val firstToken = m.groupValues[1]
                val symbol2 = m.groupValues[2]
                val rawAmount = m.groupValues[3]

                val explicitSymbol = listOf(firstToken, symbol2)
                    .firstOrNull { it.isNotBlank() && it.length == 1 }
                    .orEmpty()
                val explicitCode = listOf(firstToken, symbol2)
                    .firstOrNull { it.length == 3 && it.uppercase() in CURRENCY_CODES }
                    ?.uppercase()

                val currency = when {
                    explicitCode != null -> explicitCode
                    explicitSymbol.isNotBlank() -> CURRENCY_SYMBOL_TO_CODE[explicitSymbol]
                    else -> detectCurrencyFromText(text)
                }
                val amount = rawAmount.replace(",", ".")
                return Total(amount = amount, currencyCode = currency)
            }
        }
        return null
    }

    /** Last-resort currency inference: scan the whole receipt for an ISO
     *  code or a currency symbol. Used when the total line itself doesn't
     *  carry one but the receipt header / footer does. */
    private fun detectCurrencyFromText(text: String): String? {
        val upper = text.uppercase()
        for (code in CURRENCY_CODES) {
            if (Regex("\\b$code\\b").containsMatchIn(upper)) return code
        }
        for ((sym, code) in CURRENCY_SYMBOL_TO_CODE) {
            if (sym in text) return code
        }
        return null
    }

    // --- meal detection -----------------------------------------------------

    // Singular and plural. A receipt with a "Server" line, a "Table 12"
    // marker, or a "Breakfast" category label is almost certainly a meal.
    private val MEAL_KEYWORDS = listOf(
        "restaurant", "restaurants",
        "tip", "tips",
        "gratuity", "gratuities",
        "meal", "meals",
        "breakfast", "breakfasts",
        "table", "tables",
        "server", "servers",
    )

    private fun detectMeal(text: String): Boolean {
        val lower = text.lowercase(Locale.US)
        return MEAL_KEYWORDS.any { keyword ->
            // Match whole words to avoid "participation" triggering on "tip".
            Regex("\\b$keyword\\b").containsMatchIn(lower)
        }
    }

    // --- date ---------------------------------------------------------------

    private enum class DateStyle { Iso, SlashFourY, SlashTwoY, MonthLead, DayLead }
    private data class DatePattern(val regex: Regex, val style: DateStyle)

    private val DATE_PATTERNS = listOf(
        DatePattern(Regex("\\b(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})\\b"), DateStyle.Iso),
        DatePattern(Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})\\b"), DateStyle.SlashFourY),
        DatePattern(Regex("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{2})\\b"), DateStyle.SlashTwoY),
        // Month-leading with optional ordinal suffix: DEC 13th 2024,
        // NOV 29th 25, JANUARY 3rd 2023, JAN 03 25, JAN-03-25 …
        // The month-name group accepts abbreviated (Jan/Sept/etc) or full
        // name via a greedy letter tail. Separator is space, comma, hyphen,
        // dot, or slash. Ordinal suffix (st/nd/rd/th) is optional and not
        // captured — we only care about the numeric day value.
        DatePattern(
            Regex(
                "\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?" +
                    "[\\s,./-]+(\\d{1,2})(?:st|nd|rd|th)?[\\s,./-]+(\\d{2,4})\\b",
                RegexOption.IGNORE_CASE,
            ),
            DateStyle.MonthLead,
        ),
        // Day-leading with optional ordinal suffix: 03 NOV 24, 04 DEC 2025,
        // 13th NOV 2024 …
        DatePattern(
            Regex(
                "\\b(\\d{1,2})(?:st|nd|rd|th)?[\\s,./-]+" +
                    "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?" +
                    "[\\s,./-]+(\\d{2,4})\\b",
                RegexOption.IGNORE_CASE,
            ),
            DateStyle.DayLead,
        ),
    )

    private val MONTHS: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    fun detectDateInText(text: String): LocalDate? = allDatesInText(text).firstOrNull()

    /** Returns *every* plausible date match in `text`, deduplicated. Used by
     *  the receipt validity test so we can enforce "exactly one date".
     *
     *  Future dates are filtered out — a receipt dated after today is
     *  almost always OCR noise (e.g. a phone number or account number
     *  mis-parsed as a date). The caller should look for another match
     *  on the same receipt before giving up. */
    fun allDatesInText(text: String): List<LocalDate> {
        val today = java.time.LocalDate.now()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val out = linkedSetOf<LocalDate>()
        for (line in lines) {
            for (pattern in DATE_PATTERNS) {
                // findAll (not find) so a single line with two dates —
                // "21-04-2026 Time: 12:07:57" followed by "DEC 13th 2024"
                // in a duplicate receipt — both get captured.
                for (match in pattern.regex.findAll(line)) {
                    val parsed = parseMatch(pattern, match) ?: continue
                    if (parsed.year !in 1990..2099) continue
                    if (parsed.isAfter(today)) continue
                    out += parsed
                }
            }
        }
        return out.toList()
    }

    private fun parseMatch(pattern: DatePattern, m: MatchResult): LocalDate? {
        val g = m.groupValues
        return try {
            when (pattern.style) {
                DateStyle.Iso -> LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
                DateStyle.SlashFourY -> {
                    val a = g[1].toInt(); val b = g[2].toInt(); val y = g[3].toInt()
                    val (month, day) = if (a > 12 && b <= 12) b to a else a to b
                    LocalDate.of(y, month, day)
                }
                DateStyle.SlashTwoY -> {
                    val a = g[1].toInt(); val b = g[2].toInt()
                    val y = expandYear(g[3].toInt())
                    val (month, day) = if (a > 12 && b <= 12) b to a else a to b
                    LocalDate.of(y, month, day)
                }
                DateStyle.MonthLead -> {
                    val month = MONTHS[g[1].lowercase(Locale.US).take(3)] ?: return null
                    LocalDate.of(expandYear(g[3].toInt()), month, g[2].toInt())
                }
                DateStyle.DayLead -> {
                    val month = MONTHS[g[2].lowercase(Locale.US).take(3)] ?: return null
                    LocalDate.of(expandYear(g[3].toInt()), month, g[1].toInt())
                }
            }
        } catch (_: Throwable) { null }
    }

    private fun expandYear(y: Int): Int = when {
        y >= 1000 -> y
        y < 70 -> 2000 + y
        else -> 1900 + y
    }
}
