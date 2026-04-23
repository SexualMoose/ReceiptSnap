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
        val rawText: String,
    )

    suspend fun parse(bitmap: Bitmap): Info {
        val text = runCatching { recognize(bitmap) }.getOrDefault("")
        return Info(
            date = detectDateInText(text),
            location = AddressParser.extractLocation(text),
            isMeal = detectMeal(text),
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
        DatePattern(
            Regex("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{2,4})\\b", RegexOption.IGNORE_CASE),
            DateStyle.MonthLead,
        ),
        DatePattern(
            Regex("\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{2,4})\\b", RegexOption.IGNORE_CASE),
            DateStyle.DayLead,
        ),
    )

    private val MONTHS: Map<String, Int> = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    fun detectDateInText(text: String): LocalDate? = allDatesInText(text).firstOrNull()

    /** Returns *every* plausible date match in `text`, deduplicated. Used by
     *  the receipt validity test so we can enforce "exactly one date". */
    fun allDatesInText(text: String): List<LocalDate> {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val out = linkedSetOf<LocalDate>()
        for (line in lines) {
            for (pattern in DATE_PATTERNS) {
                val m = pattern.regex.find(line) ?: continue
                val parsed = parseMatch(pattern, m) ?: continue
                if (parsed.year in 1990..2099) out += parsed
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
