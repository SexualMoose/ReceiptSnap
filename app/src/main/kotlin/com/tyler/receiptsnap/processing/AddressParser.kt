package com.tyler.receiptsnap.processing

import java.util.Locale

/**
 * Pulls a short, filesystem-safe location label (a city when we can find one,
 * else a country) out of the raw OCR text of a receipt. Uses the postal code
 * anchors that appear on nearly every printed receipt to avoid hallucinating
 * a "city" from merchant names or menu items.
 *
 * Strategy:
 *   1. Scan every line for a postal-code pattern. Postal codes are a
 *      high-precision anchor that lets us locate the address block reliably.
 *   2. When a US/CA/UK postal pattern is found, pull the city out of the
 *      address line using format-specific regexes.
 *   3. Fall back to a country name if no postal match is found but a country
 *      appears in the text.
 */
object AddressParser {

    fun extractLocation(text: String): String? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        extractCity(lines)?.let { return it }
        return extractCountry(lines)
    }

    // --- cities anchored on postal codes ------------------------------------

    /** US: "Seattle, WA 98101" or "New York, NY 10001-2345" */
    private val US_CITY = Regex(
        "\\b([A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]+){0,3}),\\s*([A-Z]{2})\\s+\\d{5}(?:-\\d{4})?\\b"
    )

    /** Canada: "Toronto, ON M5V 3A8" */
    private val CA_CITY = Regex(
        "\\b([A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]+){0,3}),?\\s*([A-Z]{2})\\s+[A-Z]\\d[A-Z]\\s*\\d[A-Z]\\d\\b"
    )

    /** UK postal patterns like "SW1A 1AA" or "EC1A 1BB". We then look at the
     *  preceding token(s) on the same line for the locality. */
    private val UK_POSTCODE = Regex(
        "\\b([A-PR-UWYZ][A-HK-Y]?\\d[A-Z\\d]?\\s*\\d[ABD-HJLNP-UW-Z]{2})\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Generic 5–6 digit postal code (DE, FR, etc.) — used only as a hint to
     *  find the address line, not to localize to a specific format. */
    private val GENERIC_POSTAL = Regex("\\b\\d{4,6}\\b")

    private fun extractCity(lines: List<String>): String? {
        // Pass 1: try country-specific patterns on each line.
        for (line in lines) {
            US_CITY.find(line)?.let { m ->
                val city = m.groupValues[1].trim()
                if (isPlausibleCityName(city)) return city
            }
            CA_CITY.find(line)?.let { m ->
                val city = m.groupValues[1].trim()
                if (isPlausibleCityName(city)) return city
            }
        }

        // Pass 2: UK postcode anchors. The line usually reads "…, London SW1A 1AA".
        for (line in lines) {
            val m = UK_POSTCODE.find(line) ?: continue
            val before = line.substring(0, m.range.first).trimEnd(',', ' ', '\t')
            val words = before.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
            // Take the trailing 1-2 capitalized tokens as the city/town.
            val trailing = words.takeLast(2).filter {
                it.first().isUpperCase() && it.any { c -> c.isLetter() }
            }
            val candidate = trailing.joinToString(" ").trim()
            if (candidate.isNotBlank() && isPlausibleCityName(candidate)) return candidate
        }

        // Pass 3: generic postal anchor. The *previous* line of the address
        // block typically holds the city (and possibly a country). Keep this
        // conservative: only use when the following line is clearly a postal
        // code line with few other tokens.
        for (i in lines.indices) {
            val line = lines[i]
            if (!GENERIC_POSTAL.containsMatchIn(line)) continue
            if (line.count { it.isDigit() } < 4) continue
            // Look both on this line (after the postal) and the prior line.
            val priorCandidate = if (i > 0) tailCapitalized(lines[i - 1]) else null
            val thisLineCandidate = tailCapitalized(line)
            val pick = priorCandidate ?: thisLineCandidate
            if (pick != null && isPlausibleCityName(pick)) return pick
        }
        return null
    }

    /** Return the trailing run of capitalized words from a line (e.g. the
     *  "Berlin" in "10115 Berlin"), or null if none look like a place name. */
    private fun tailCapitalized(line: String): String? {
        val words = line.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        val trailing = words.takeLastWhile {
            it.first().isUpperCase() && it.any { c -> c.isLetter() } && it.length >= 3
        }
        if (trailing.isEmpty()) return null
        return trailing.joinToString(" ")
    }

    private fun isPlausibleCityName(name: String): Boolean {
        if (name.length !in 2..40) return false
        // Reject anything that is mostly uppercase-shouty and stuffed with
        // retail vocabulary we don't want leaking into filenames.
        val blocked = setOf(
            "total", "subtotal", "tax", "cash", "change", "visa", "debit",
            "credit", "mastercard", "amex", "store", "receipt", "invoice",
            "thank", "welcome", "order", "item", "phone", "fax",
        )
        val lower = name.lowercase(Locale.US)
        if (blocked.any { it in lower }) return false
        if (name.count { it.isDigit() } > name.length / 3) return false
        return name.any { it.isLetter() }
    }

    // --- country fallback ---------------------------------------------------

    private val COUNTRIES = listOf(
        "USA", "United States", "Canada", "United Kingdom", "UK", "Mexico",
        "Germany", "France", "Italy", "Spain", "Netherlands", "Belgium",
        "Switzerland", "Austria", "Ireland", "Australia", "New Zealand",
        "Japan", "China", "Korea", "Singapore", "India", "Brazil", "Argentina",
        "Chile", "Colombia", "Peru", "Denmark", "Sweden", "Norway", "Finland",
        "Poland", "Portugal", "Greece", "Turkey", "Israel", "UAE",
    )

    private fun extractCountry(lines: List<String>): String? {
        val haystack = lines.joinToString(" ")
        // Word-boundary search, longest-match-wins to prefer "United States"
        // over "USA" when both are present.
        return COUNTRIES
            .sortedByDescending { it.length }
            .firstOrNull { name ->
                Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
                    .containsMatchIn(haystack)
            }
            ?.let { normalizeCountry(it) }
    }

    private fun normalizeCountry(name: String): String = when (name.lowercase(Locale.US)) {
        "usa", "united states" -> "USA"
        "uk", "united kingdom" -> "UK"
        "uae" -> "UAE"
        else -> name.split(" ").joinToString(" ") {
            it.replaceFirstChar { c -> c.titlecase(Locale.US) }
        }
    }
}
