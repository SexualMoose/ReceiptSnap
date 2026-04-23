package com.tyler.receiptsnap.processing

import java.util.Locale

/**
 * Extracts a short location label (city / state / country) from the OCR text
 * of a receipt. Uses a multi-signal scoring approach rather than a single
 * strict regex so it handles:
 *   - US formats like "Seattle, WA 98101" (with or without the +4)
 *   - Canadian "Toronto, ON M5V 3A8"
 *   - UK "London SW1A 1AA"
 *   - European "20121 Milano" / "75006 Paris"
 *   - Country-trailed blocks ending with "ITALIA" / "France" / "USA"
 *   - Multi-line centered address blocks with street-suffix anchors
 *     (STREET / ROAD / AVE / VIA / RUE / STRASSE / BOULEVARD / etc.)
 *   - Common OCR glitches (missing spaces, 0/O confusions in postal slots)
 *
 * Output is filename-safe ASCII. Returns null when no candidate scores
 * above threshold — silence is preferred over a wrong label.
 */
object AddressParser {

    private const val MIN_ACCEPT_SCORE = 4
    private const val MIN_MARGIN = 1   // over #2 candidate
    private const val MAX_LABEL_LEN = 24

    fun extractLocation(text: String): String? {
        val rawLines = text.lineSequence().map { it.trim() }.toList()
        if (rawLines.isEmpty()) return null

        // Normalize for matching while keeping originals for casing.
        val lines = rawLines.mapIndexed { i, raw -> Line(i, raw, normalizeForMatch(raw)) }

        // Restrict to the address block. Receipts usually have the address in
        // lines 2–10; matches farther down are almost always noise.
        val window = lines.take(findBlockEnd(lines)).drop(0)

        val candidates = mutableListOf<Candidate>()
        candidates += postalAnchoredCandidates(window)
        candidates += streetAnchoredCandidates(window)
        candidates += countryOnlyCandidates(window)

        val sorted = candidates.sortedByDescending { it.score }
        val best = sorted.firstOrNull() ?: return null
        if (best.score < MIN_ACCEPT_SCORE) return null
        val second = sorted.getOrNull(1)
        if (second != null && best.score - second.score < MIN_MARGIN &&
            !sameLabel(best.label, second.label)
        ) return null

        return sanitize(best.label)
    }

    // --- internals ----------------------------------------------------------

    private data class Line(val idx: Int, val original: String, val normalized: String)
    private data class Candidate(val label: String, val score: Int)

    /** Where does the address block end? First line that looks like totals,
     *  a date, a phone number, or an order identifier terminates it. */
    private fun findBlockEnd(lines: List<Line>): Int {
        val maxExamine = minOf(12, lines.size)
        for (i in 0 until maxExamine) {
            val n = lines[i].normalized
            if (n.isBlank()) continue
            if (i >= 2 && isBlockTerminator(n)) return i
        }
        return maxExamine
    }

    private fun isBlockTerminator(line: String): Boolean {
        val lower = line.lowercase(Locale.US)
        if (TERMINATOR_TOKENS.any { it in lower }) return true
        if (PHONE_REGEX.containsMatchIn(line)) return true
        if (ReceiptParser.detectDateInText(line) != null) return true
        if (line.contains("$") || line.contains("€") || line.contains("£")) return true
        return false
    }

    private val PHONE_REGEX = Regex("\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}")
    private val TERMINATOR_TOKENS = setOf(
        "total", "subtotal", "tax", "cash", "change", "order", "cashier",
        "receipt", "invoice", "server", "table", "balance", "due",
        "tel:", "phone:", "fax:",
    )

    // Tier A: postal-anchored ------------------------------------------------

    /** US: zip of 5 (+4) preceded by two capital letters (state). OCR glitches
     *  tolerated: missing comma, no space between state and zip. */
    private val US_POSTAL = Regex(
        "\\b([A-Z]{2})\\s*(\\d{5}(?:[-\\s]\\d{4})?)\\b"
    )

    /** Canadian A1A 1A1. */
    private val CA_POSTAL = Regex(
        "\\b([A-CEGHJ-NPR-TVXY]\\d[A-CEGHJ-NPR-TV-Z])\\s*(\\d[A-CEGHJ-NPR-TV-Z]\\d)\\b"
    )

    /** UK postcode. */
    private val UK_POSTAL = Regex(
        "\\b([A-PR-UWYZ][A-HK-Y]?\\d[A-Z\\d]?)\\s+(\\d[ABD-HJLNP-UW-Z]{2})\\b",
        RegexOption.IGNORE_CASE,
    )

    /** EU-numeric: 4–5 digit code with a capitalized word right next to it. */
    private val EU_POSTAL_LEADING = Regex("\\b(\\d{4,5})\\s+([A-ZÀ-Ý][A-Za-zÀ-ÿ'-]+(?:\\s+[A-ZÀ-Ý][A-Za-zÀ-ÿ'-]+){0,2})\\b")
    private val EU_POSTAL_TRAILING = Regex("\\b([A-ZÀ-Ý][A-Za-zÀ-ÿ'-]+(?:\\s+[A-ZÀ-Ý][A-Za-zÀ-ÿ'-]+){0,2})\\s+(\\d{4,5})\\b")

    private val US_STATES = setOf(
        "AL","AK","AZ","AR","CA","CO","CT","DE","FL","GA","HI","ID","IL","IN",
        "IA","KS","KY","LA","ME","MD","MA","MI","MN","MS","MO","MT","NE","NV",
        "NH","NJ","NM","NY","NC","ND","OH","OK","OR","PA","RI","SC","SD","TN",
        "TX","UT","VT","VA","WA","WV","WI","WY","DC",
    )

    private val CA_PROVINCES = setOf(
        "AB","BC","MB","NB","NL","NS","NT","NU","ON","PE","QC","SK","YT",
    )

    private fun postalAnchoredCandidates(window: List<Line>): List<Candidate> {
        val out = mutableListOf<Candidate>()

        for (i in window.indices) {
            val line = window[i]
            val src = line.normalized

            // US: "City, ST 12345" or "City ST 12345"
            US_POSTAL.find(src)?.let { m ->
                val state = m.groupValues[1]
                if (state in US_STATES) {
                    val before = src.substring(0, m.range.first).trimEnd(',', ' ', '\t')
                    val cityToken = tailPlaceWords(before)
                    val sameLine = isPlausibleCityToken(cityToken)
                    val prevLineCity = (i - 1).takeIf { it >= 0 }?.let { tailPlaceWords(window[it].normalized) }

                    if (sameLine && cityToken != null) {
                        out += Candidate(cityToken, score = 6)  // postal+same-line
                    } else if (prevLineCity != null && isPlausibleCityToken(prevLineCity)) {
                        out += Candidate(prevLineCity, score = 5)  // postal on next line
                    } else {
                        out += Candidate(state, score = 4)  // fall back to state
                    }
                    return@let
                }
            }

            // Canada
            CA_POSTAL.find(src)?.let { m ->
                val before = src.substring(0, m.range.first).trimEnd(',', ' ', '\t')
                // Before the postcode there is usually "City, PR"
                val tokens = before.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
                val provCandidate = tokens.lastOrNull()?.uppercase(Locale.US)
                if (provCandidate in CA_PROVINCES) {
                    val cityTokens = tokens.dropLast(1)
                    val city = trailingCapitalizedRun(cityTokens)
                    if (city != null && isPlausibleCityToken(city)) {
                        out += Candidate(city, 6)
                    } else {
                        out += Candidate(provCandidate!!, 4)
                    }
                } else {
                    val prevCity = (i - 1).takeIf { it >= 0 }?.let {
                        tailPlaceWords(window[it].normalized)
                    }
                    if (prevCity != null && isPlausibleCityToken(prevCity)) {
                        out += Candidate(prevCity, 5)
                    }
                }
            }

            // UK — city is the trailing capitalized run on same line before the postcode
            UK_POSTAL.find(src)?.let { m ->
                val before = src.substring(0, m.range.first).trimEnd(',', ' ', '\t')
                val city = tailPlaceWords(before)
                if (city != null && isPlausibleCityToken(city)) {
                    out += Candidate(city, 6)
                } else {
                    val prevCity = (i - 1).takeIf { it >= 0 }?.let {
                        tailPlaceWords(window[it].normalized)
                    }
                    if (prevCity != null && isPlausibleCityToken(prevCity)) {
                        out += Candidate(prevCity, 5)
                    }
                }
            }

            // EU-numeric: postcode leading or trailing
            EU_POSTAL_LEADING.find(src)?.let { m ->
                val city = m.groupValues[2]
                if (isPlausibleCityToken(city)) out += Candidate(city, 5)
            }
            EU_POSTAL_TRAILING.find(src)?.let { m ->
                val city = m.groupValues[1]
                if (isPlausibleCityToken(city)) out += Candidate(city, 5)
            }
        }
        return out
    }

    // Tier B: street-suffix anchored ----------------------------------------

    private val STREET_SUFFIXES = setOf(
        "street", "st", "road", "rd", "avenue", "ave", "av",
        "boulevard", "blvd", "bd", "drive", "dr", "lane", "ln",
        "way", "court", "ct", "place", "pl", "square", "sq",
        "highway", "hwy", "parkway", "pkwy", "terrace", "ter",
        "suite", "ste", "floor", "fl",
        "via", "viale", "piazza", "corso",
        "strasse", "straße", "str",
        "rue", "avenue", "boulevard",
        "calle", "avenida", "plaza",
    )

    private fun streetAnchoredCandidates(window: List<Line>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        for (i in window.indices) {
            val lower = window[i].normalized.lowercase(Locale.US)
            val tokens = lower.split(Regex("[\\s,.]+")).filter { it.isNotBlank() }
            val hasStreet = tokens.any { it in STREET_SUFFIXES } ||
                Regex("^\\d{1,5}\\s+[A-Za-z]").containsMatchIn(window[i].normalized)
            if (!hasStreet) continue

            // The city is typically 1–2 lines below the street line.
            for (delta in 1..2) {
                val next = window.getOrNull(i + delta) ?: break
                if (next.normalized.isBlank()) continue
                val city = tailPlaceWords(next.normalized)
                if (city != null && isPlausibleCityToken(city)) {
                    out += Candidate(city, 4 - (delta - 1))
                }
            }
        }
        return out
    }

    // Tier C: country words --------------------------------------------------

    private val COUNTRY_ALIASES: Map<String, String> = mapOf(
        "usa" to "USA", "u.s.a." to "USA", "united states" to "USA",
        "canada" to "Canada", "uk" to "UK", "united kingdom" to "UK",
        "great britain" to "UK",
        "deutschland" to "Germany", "germany" to "Germany",
        "italia" to "Italy", "italy" to "Italy",
        "france" to "France",
        "españa" to "Spain", "espana" to "Spain", "spain" to "Spain",
        "portugal" to "Portugal",
        "nederland" to "Netherlands", "netherlands" to "Netherlands",
        "belgië" to "Belgium", "belgium" to "Belgium",
        "schweiz" to "Switzerland", "suisse" to "Switzerland", "switzerland" to "Switzerland",
        "österreich" to "Austria", "austria" to "Austria",
        "ireland" to "Ireland", "éire" to "Ireland",
        "australia" to "Australia", "new zealand" to "NewZealand",
        "japan" to "Japan", "日本" to "Japan",
        "mexico" to "Mexico", "méxico" to "Mexico",
        "brasil" to "Brazil", "brazil" to "Brazil",
    )

    private fun countryOnlyCandidates(window: List<Line>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val joinedLower = window.joinToString(" ") { it.normalized.lowercase(Locale.US) }
        for ((alias, canonical) in COUNTRY_ALIASES) {
            if (Regex("\\b${Regex.escape(alias)}\\b").containsMatchIn(joinedLower)) {
                // Country alone is a weak label — score low so city/state win.
                out += Candidate(canonical, 3)
                break
            }
        }
        return out
    }

    // --- token helpers ------------------------------------------------------

    /** Extracts the run of capitalized place-like words at the END of a line
     *  (useful for "123 Main St Seattle" style or "SEATTLE" standalone). */
    private fun tailPlaceWords(line: String): String? {
        if (line.isBlank()) return null
        val cleaned = line.trimEnd(',', '.', ' ', '\t')
        val words = cleaned.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        // Take 1-3 trailing words that look like a place name.
        val trailing = words.takeLastWhile { looksLikePlaceWord(it) }.takeLast(3)
        if (trailing.isEmpty()) return null
        return trailing.joinToString(" ")
    }

    /** Combine a known-good list of tokens into a trailing city name. */
    private fun trailingCapitalizedRun(tokens: List<String>): String? {
        val trailing = tokens.takeLastWhile { looksLikePlaceWord(it) }.takeLast(3)
        if (trailing.isEmpty()) return null
        return trailing.joinToString(" ")
    }

    private fun looksLikePlaceWord(word: String): Boolean {
        if (word.length < 2 || word.length > 20) return false
        if (word.any { it.isDigit() }) return false
        if (!word[0].isUpperCase() && !word[0].isLetter()) return false
        val lower = word.lowercase(Locale.US)
        if (lower in STREET_SUFFIXES) return false  // exclude "Street", "Ave", etc.
        if (lower in NON_PLACE_WORDS) return false
        return word.any { it.isLetter() }
    }

    private val NON_PLACE_WORDS = setOf(
        "store", "shop", "restaurant", "cafe", "café",
        "receipt", "invoice", "order", "total", "subtotal",
        "cashier", "server", "host", "table", "check",
        "thank", "thanks", "welcome", "visit", "tel", "phone", "fax",
        "suite", "ste", "floor", "unit", "apt", "apartment", "room",
        "merchant", "cardholder", "auth", "approved", "ref", "reference",
        "date", "time", "item", "qty", "amount", "balance", "due",
        "change", "cash", "visa", "debit", "credit", "mc", "amex",
    )

    private fun isPlausibleCityToken(candidate: String?): Boolean {
        if (candidate == null) return false
        if (candidate.length !in 2..40) return false
        if (candidate.any { it.isDigit() }) return false
        val lower = candidate.lowercase(Locale.US)
        if (lower in NON_PLACE_WORDS) return false
        if (lower.split(Regex("\\s+")).any { it in NON_PLACE_WORDS }) return false
        return candidate.any { it.isLetter() }
    }

    // --- OCR normalization --------------------------------------------------

    /** Light normalization: collapse whitespace and pad punctuation so the
     *  pattern matchers don't have to reason about OCR's spacing quirks. */
    private fun normalizeForMatch(raw: String): String {
        if (raw.isBlank()) return raw
        var s = raw.replace('\u00A0', ' ')
        s = s.replace(Regex("([,.])(?=\\S)"), "$1 ")
        s = s.replace(Regex("(?<=\\S)([,.])"), " $1")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s
    }

    // --- output sanitization ------------------------------------------------

    private fun sameLabel(a: String, b: String): Boolean =
        sanitize(a).equals(sanitize(b), ignoreCase = true)

    private fun sanitize(raw: String): String {
        val ascii = raw
            .replace(Regex("[àáâãäå]"), "a").replace(Regex("[ÀÁÂÃÄÅ]"), "A")
            .replace("æ", "ae").replace("Æ", "Ae")
            .replace(Regex("[èéêë]"), "e").replace(Regex("[ÈÉÊË]"), "E")
            .replace(Regex("[ìíîï]"), "i").replace(Regex("[ÌÍÎÏ]"), "I")
            .replace(Regex("[òóôõö]"), "o").replace(Regex("[ÒÓÔÕÖ]"), "O")
            .replace("ø", "o").replace("Ø", "O")
            .replace(Regex("[ùúûü]"), "u").replace(Regex("[ÙÚÛÜ]"), "U")
            .replace("ñ", "n").replace("Ñ", "N")
            .replace("ß", "ss")
            .replace(Regex("[^\\p{ASCII}]"), "")
        val trimmed = ascii.take(MAX_LABEL_LEN).trim()
        val parts = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        return parts.joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
    }
}
