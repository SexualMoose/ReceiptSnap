package com.tyler.receiptsnap.processing

import java.util.Locale

/**
 * Pulls a short location label (city, state, or country) out of the OCR
 * text of a receipt — but only when the text actually contains a
 * *verifiable* address. The goal is zero filename noise: we'd rather
 * return null than guess.
 *
 * Verification is "the line matches a well-defined postal-address regex
 * for the country." No generic digit-proximity heuristics, no arbitrary
 * country-name scans — those picked up transaction IDs, totals, and
 * merchant marketing copy as if they were addresses.
 */
object AddressParser {

    /** US: "City, ST 12345" or "City, ST 12345-6789". City may be multi-word. */
    private val US_CITY_STATE_ZIP = Regex(
        "\\b([A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]+){0,3})" +
            ",\\s*([A-Z]{2})\\s+(\\d{5}(?:-\\d{4})?)\\b"
    )

    /** Canada: "City, PR A1B 2C3" (postcodes have a forbidden-letter set). */
    private val CA_CITY_PROV_CODE = Regex(
        "\\b([A-Z][A-Za-z.'-]+(?:\\s+[A-Z][A-Za-z.'-]+){0,3})" +
            ",?\\s*(AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)" +
            "\\s+([A-CEGHJ-NPR-TVXY]\\d[A-CEGHJ-NPR-TV-Z]\\s?\\d[A-CEGHJ-NPR-TV-Z]\\d)\\b"
    )

    /** UK postcode: AA9A 9AA / A9A 9AA / A9 9AA / A99 9AA / AA9 9AA / AA99 9AA.
     *  The preceding word(s) on the same line are typically the city. */
    private val UK_POSTCODE = Regex(
        "\\b([A-PR-UWYZ][A-HK-Y]?\\d[A-Z\\d]?\\s+\\d[ABD-HJLNP-UW-Z]{2})\\b",
        RegexOption.IGNORE_CASE,
    )

    /** States used as a last-resort when city name fails plausibility. */
    private val US_STATE_NAMES = mapOf(
        "AL" to "Alabama", "AK" to "Alaska", "AZ" to "Arizona", "AR" to "Arkansas",
        "CA" to "California", "CO" to "Colorado", "CT" to "Connecticut", "DE" to "Delaware",
        "FL" to "Florida", "GA" to "Georgia", "HI" to "Hawaii", "ID" to "Idaho",
        "IL" to "Illinois", "IN" to "Indiana", "IA" to "Iowa", "KS" to "Kansas",
        "KY" to "Kentucky", "LA" to "Louisiana", "ME" to "Maine", "MD" to "Maryland",
        "MA" to "Massachusetts", "MI" to "Michigan", "MN" to "Minnesota", "MS" to "Mississippi",
        "MO" to "Missouri", "MT" to "Montana", "NE" to "Nebraska", "NV" to "Nevada",
        "NH" to "NewHampshire", "NJ" to "NewJersey", "NM" to "NewMexico", "NY" to "NewYork",
        "NC" to "NorthCarolina", "ND" to "NorthDakota", "OH" to "Ohio", "OK" to "Oklahoma",
        "OR" to "Oregon", "PA" to "Pennsylvania", "RI" to "RhodeIsland", "SC" to "SouthCarolina",
        "SD" to "SouthDakota", "TN" to "Tennessee", "TX" to "Texas", "UT" to "Utah",
        "VT" to "Vermont", "VA" to "Virginia", "WA" to "Washington", "WV" to "WestVirginia",
        "WI" to "Wisconsin", "WY" to "Wyoming", "DC" to "DC",
    )

    fun extractLocation(text: String): String? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()

        for (line in lines) {
            US_CITY_STATE_ZIP.find(line)?.let { m ->
                val city = titleCase(m.groupValues[1])
                if (isPlausibleName(city)) return city
                val stateCode = m.groupValues[2]
                return US_STATE_NAMES[stateCode] ?: stateCode
            }
            CA_CITY_PROV_CODE.find(line)?.let { m ->
                val city = titleCase(m.groupValues[1])
                if (isPlausibleName(city)) return city
                return m.groupValues[2]
            }
        }

        // UK: infer city from the run of capitalized words immediately before
        // the postcode. Don't bubble up a UK postcode alone — a raw postcode
        // in a filename would be noise.
        for (line in lines) {
            val m = UK_POSTCODE.find(line) ?: continue
            val before = line.substring(0, m.range.first).trimEnd(',', ' ', '\t')
            val words = before.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
            val trailing = words.takeLastWhile {
                it.first().isUpperCase() && it.any { c -> c.isLetter() } && it.length >= 3
            }.takeLast(2)
            if (trailing.isEmpty()) continue
            val city = trailing.joinToString(" ")
            if (isPlausibleName(city)) return titleCase(city)
        }

        return null
    }

    /** Reject tokens that pattern-match "city" shape but are obviously retail
     *  vocabulary, since we scrape city candidates from regex Group 1 and
     *  OCR-noisy receipts can produce things like "SUBTOTAL, NY 10001" after
     *  punctuation drops. Cities also don't contain digits. */
    private fun isPlausibleName(name: String): Boolean {
        if (name.length !in 2..40) return false
        if (name.any { it.isDigit() }) return false
        val lower = name.lowercase(Locale.US)
        val blocked = setOf(
            "total", "subtotal", "tax", "cash", "change", "visa", "debit",
            "credit", "mastercard", "amex", "store", "receipt", "invoice",
            "thank", "welcome", "order", "item", "phone", "fax", "auth",
            "merchant", "cardholder", "sold", "approved", "payment", "pending",
            "reference", "terminal", "transaction", "account", "balance",
        )
        if (blocked.any { it in lower }) return false
        return name.any { it.isLetter() }
    }

    private fun titleCase(s: String): String =
        s.trim().split(Regex("\\s+")).joinToString(" ") { word ->
            word.replaceFirstChar { it.titlecase(Locale.US) }
        }
}
