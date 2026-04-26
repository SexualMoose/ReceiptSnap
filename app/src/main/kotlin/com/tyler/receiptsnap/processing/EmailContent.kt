package com.tyler.receiptsnap.processing

/**
 * Builds the email Subject and Body for a Coupa-bound receipt. Many Coupa
 * tenants parse subject/body for amount and date hints; even tenants that
 * don't, the structured body makes the receipt findable in the user's
 * Sent folder. Inputs come from the receipt's filename (which already
 * encodes date / location / currency+amount / meal flag) so this works
 * uniformly across the gallery, folder-upload, and passthrough flows.
 */
object EmailContent {

    private val DATE_PART_RE = Regex("\\b\\d{4}-\\d{2}-\\d{2}\\b")
    private val AMOUNT_PART_RE = Regex("\\b([A-Z]{3})(\\d+(?:\\.\\d{2})?)\\b")

    data class Parts(
        val date: String?,
        val location: String?,
        val currency: String?,
        val amount: String?,
        val meal: Boolean,
    )

    fun parse(baseName: String): Parts {
        val tokens = baseName.split('_').filter { it.isNotBlank() }
        val date = tokens.firstOrNull { it.matches(DATE_PART_RE) }
        val amountToken = tokens.firstOrNull { AMOUNT_PART_RE.matches(it) }
        val (currency, amount) = if (amountToken != null) {
            val m = AMOUNT_PART_RE.matchEntire(amountToken)!!
            m.groupValues[1] to m.groupValues[2]
        } else null to null
        val meal = "meal" in tokens
        // What's left is the location — could be one or more tokens.
        val locationTokens = tokens.filter {
            it != date && it != amountToken && it != "meal" &&
                !it.matches(Regex("\\d+"))         // bare counters from " - N" fallbacks
        }
        val location = locationTokens.joinToString(" ")
            .takeIf { it.isNotBlank() && it != "receipt" }
        return Parts(date, location, currency, amount, meal)
    }

    /** Subject: human-readable space-separated form so it reads well in the
     *  user's mail client and Coupa's expense entry list. */
    fun subjectFor(baseName: String): String {
        val p = parse(baseName)
        val pieces = buildList {
            p.date?.let { add(it) }
            p.location?.let { add(it) }
            if (p.currency != null && p.amount != null) add("${p.currency} ${p.amount}")
            if (p.meal) add("meal")
        }
        return pieces.joinToString(" ").ifBlank { baseName }
    }

    /** Body: structured key/value lines. Coupa's email parser picks these
     *  up on tenants that have it enabled; tenants that don't simply see
     *  a tidy summary alongside the attachment. */
    fun bodyFor(baseName: String): String {
        val p = parse(baseName)
        return buildString {
            appendLine("Receipt details:")
            p.date?.let { appendLine("Date: $it") }
            p.location?.let { appendLine("Location: $it") }
            if (p.currency != null && p.amount != null) {
                appendLine("Total: ${p.currency} ${p.amount}")
            }
            if (p.meal) appendLine("Category: Meal")
            appendLine()
            appendLine("Sent from ReceiptSnap.")
        }
    }
}
