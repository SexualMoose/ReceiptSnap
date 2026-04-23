package com.tyler.receiptsnap.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * App settings — the company's Coupa host and the user's work email.
 * Persisted to SharedPreferences. Each field is exposed as a StateFlow so
 * Compose screens recompose when the user edits a field elsewhere.
 *
 * The Coupa wallet ingest address is derived by convention:
 *   "{FirstName}{LastName}@{instance}.coupa-expenses.com"
 * where `instance` is the first dotted segment of the host and the name is
 * built from the local-part of the user email (split on . _ -).
 * The user can override the full address via [walletOverride] when their
 * deployment doesn't follow the convention.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private val _companyHost = MutableStateFlow(prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
    val companyHost: StateFlow<String> = _companyHost.asStateFlow()

    private val _userEmail = MutableStateFlow(prefs.getString(KEY_EMAIL, DEFAULT_EMAIL) ?: DEFAULT_EMAIL)
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _walletOverride = MutableStateFlow(prefs.getString(KEY_OVERRIDE, "") ?: "")
    val walletOverride: StateFlow<String> = _walletOverride.asStateFlow()

    private val _smtpHost = MutableStateFlow(prefs.getString(KEY_SMTP_HOST, DEFAULT_SMTP_HOST) ?: DEFAULT_SMTP_HOST)
    val smtpHost: StateFlow<String> = _smtpHost.asStateFlow()

    private val _smtpPort = MutableStateFlow(prefs.getInt(KEY_SMTP_PORT, DEFAULT_SMTP_PORT))
    val smtpPort: StateFlow<Int> = _smtpPort.asStateFlow()

    private val _smtpPassword = MutableStateFlow(prefs.getString(KEY_SMTP_PASSWORD, "") ?: "")
    val smtpPassword: StateFlow<String> = _smtpPassword.asStateFlow()

    /** Derived (or overridden) wallet address to send receipts to. */
    fun currentWalletEmail(): String {
        val override = _walletOverride.value.trim()
        if (override.isNotBlank()) return override
        return deriveCoupaAddress(_userEmail.value, _companyHost.value)
    }

    fun setSmtpHost(value: String) {
        val v = value.trim()
        prefs.edit().putString(KEY_SMTP_HOST, v).apply()
        _smtpHost.value = v
    }

    fun setSmtpPort(value: Int) {
        prefs.edit().putInt(KEY_SMTP_PORT, value).apply()
        _smtpPort.value = value
    }

    fun setSmtpPassword(value: String) {
        // Not trimmed — some passwords legitimately contain leading/trailing
        // whitespace, and app passwords sometimes include spaces.
        prefs.edit().putString(KEY_SMTP_PASSWORD, value).apply()
        _smtpPassword.value = value
    }

    fun setCompanyHost(value: String) {
        val v = value.trim()
        prefs.edit().putString(KEY_HOST, v).apply()
        _companyHost.value = v
    }

    fun setUserEmail(value: String) {
        val v = value.trim()
        prefs.edit().putString(KEY_EMAIL, v).apply()
        _userEmail.value = v
    }

    fun setWalletOverride(value: String) {
        val v = value.trim()
        prefs.edit().putString(KEY_OVERRIDE, v).apply()
        _walletOverride.value = v
    }

    companion object {
        private const val PREF_NAME = "receipt_snap_settings"
        private const val KEY_HOST = "company_host"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_OVERRIDE = "coupa_wallet_override"
        private const val KEY_SMTP_HOST = "smtp_host"
        private const val KEY_SMTP_PORT = "smtp_port"
        private const val KEY_SMTP_PASSWORD = "smtp_password"

        // Pre-seeded so the app sends correctly on first run without a detour
        // through Settings. User can change anytime.
        private const val DEFAULT_HOST = "bdpinternational.coupahost.com"
        private const val DEFAULT_EMAIL = "Tyler.Keller@psabdp.com"

        // Office 365 is BDP's likely mail provider. 587 is the STARTTLS
        // submission port. If the tenant has disabled basic-auth SMTP the
        // user will need an app password or a different host.
        private const val DEFAULT_SMTP_HOST = "smtp.office365.com"
        private const val DEFAULT_SMTP_PORT = 587

        /**
         * Coupa's receipt-ingest address encodes the sender's full email
         * (local+domain concatenated, @ removed) in the local-part so Coupa
         * can attribute the receipt regardless of the SMTP From header.
         * Example: tyler.keller@psabdp.com + bdpinternational.coupahost.com
         *       → tyler.kellerpsabdp.com@bdpinternational.coupa-expenses.com
         */
        fun deriveCoupaAddress(email: String, host: String): String {
            val e = email.trim().lowercase(Locale.US)
            val h = host.trim().lowercase(Locale.US)
            if (e.isBlank() || h.isBlank() || !e.contains("@")) return ""
            val local = e.substringBefore("@")
            val domain = e.substringAfter("@")
            if (local.isBlank() || domain.isBlank()) return ""
            val instance = h.removePrefix("https://").removePrefix("http://")
                .substringBefore("/").substringBefore(".").trim()
            if (instance.isBlank()) return ""
            return "$local$domain@$instance.coupa-expenses.com"
        }
    }
}
