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

    /** Derived (or overridden) wallet address to send receipts to. */
    fun currentWalletEmail(): String {
        val override = _walletOverride.value.trim()
        if (override.isNotBlank()) return override
        return deriveCoupaAddress(_userEmail.value, _companyHost.value)
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

        // Pre-seeded so the app sends correctly on first run without a detour
        // through Settings. User can change anytime.
        private const val DEFAULT_HOST = "bdpinternational.coupahost.com"
        private const val DEFAULT_EMAIL = "tyler.keller@psabdp.com"

        fun deriveCoupaAddress(email: String, host: String): String {
            val e = email.trim(); val h = host.trim()
            if (e.isBlank() || h.isBlank() || !e.contains("@")) return ""
            val local = e.substringBefore("@")
            val name = local
                .split('.', '_', '-', ' ')
                .filter { it.isNotBlank() }
                .joinToString("") { part ->
                    part.replaceFirstChar { it.titlecase(Locale.US) }
                }
            if (name.isBlank()) return ""
            val instance = h.removePrefix("https://").removePrefix("http://")
                .substringBefore("/").substringBefore(".").trim()
            if (instance.isBlank()) return ""
            return "$name@$instance.coupa-expenses.com"
        }
    }
}
