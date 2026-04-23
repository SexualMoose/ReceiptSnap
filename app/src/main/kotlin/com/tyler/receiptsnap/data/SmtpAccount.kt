package com.tyler.receiptsnap.data

import java.util.UUID

/**
 * One saved outbound SMTP account. The user can keep several of these in
 * Settings and switch the active one — useful when approaching a single
 * account's daily send limit (Gmail's 500-per-day, Office 365's recipient
 * caps, etc.) without losing receipts mid-batch.
 *
 * Stored in SharedPreferences as a JSON array inside [SettingsStore].
 */
data class SmtpAccount(
    val id: String = UUID.randomUUID().toString(),
    val email: String = "",
    val password: String = "",
    val host: String = "smtp.office365.com",
    val port: Int = 587,
) {
    /** Short one-line description used in list tiles. */
    val label: String
        get() = if (email.isBlank()) "(no email)" else email

    val hostAndPort: String get() = "$host:$port"
}
