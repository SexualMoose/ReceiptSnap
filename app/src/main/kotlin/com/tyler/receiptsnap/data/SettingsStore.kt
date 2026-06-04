package com.tyler.receiptsnap.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/**
 * App settings — Coupa host + work email (for receipt-address derivation)
 * plus a list of saved outbound SMTP accounts with one flagged active. The
 * send loop always uses the active account's credentials; the user can
 * switch via the Settings screen whenever one account bumps up against
 * its provider's send limits.
 *
 * Backwards compatibility: an older build stored a single SMTP account in
 * separate pref keys. On first launch of this version we migrate those
 * into an SmtpAccount entry so nothing is lost.
 */
class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // --- Coupa identity -----------------------------------------------------

    private val _companyHost = MutableStateFlow(prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST)
    val companyHost: StateFlow<String> = _companyHost.asStateFlow()

    private val _userEmail = MutableStateFlow(prefs.getString(KEY_EMAIL, DEFAULT_EMAIL) ?: DEFAULT_EMAIL)
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _walletOverride = MutableStateFlow(prefs.getString(KEY_OVERRIDE, "") ?: "")
    val walletOverride: StateFlow<String> = _walletOverride.asStateFlow()

    // --- Account list -------------------------------------------------------

    private val _accounts = MutableStateFlow(loadAccountsWithMigration())
    val accounts: StateFlow<List<SmtpAccount>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow(
        prefs.getString(KEY_ACTIVE_ID, null) ?: _accounts.value.firstOrNull()?.id
    )
    val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    // Derived per-field flows still exist because downstream code (send
    // loop, PDF maker, SmtpSender) was written against them. They always
    // reflect the currently-active account.
    private val _smtpHost = MutableStateFlow(activeAccountOrNull()?.host ?: DEFAULT_SMTP_HOST)
    val smtpHost: StateFlow<String> = _smtpHost.asStateFlow()

    private val _smtpPort = MutableStateFlow(activeAccountOrNull()?.port ?: DEFAULT_SMTP_PORT)
    val smtpPort: StateFlow<Int> = _smtpPort.asStateFlow()

    private val _smtpPassword = MutableStateFlow(activeAccountOrNull()?.password ?: "")
    val smtpPassword: StateFlow<String> = _smtpPassword.asStateFlow()

    private val _senderEmail = MutableStateFlow(activeAccountOrNull()?.email ?: "")
    val senderEmail: StateFlow<String> = _senderEmail.asStateFlow()

    /** When true, a send failure that looks like a rate/quota limit on the
     *  active account auto-switches to the next saved account and
     *  continues the remaining queue. Default on because it's useful
     *  only when multiple accounts exist; with one account there's no
     *  failover to attempt and the flag is a no-op. */
    private val _failoverEnabled = MutableStateFlow(prefs.getBoolean(KEY_FAILOVER, true))
    val failoverEnabled: StateFlow<Boolean> = _failoverEnabled.asStateFlow()

    fun setFailoverEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_FAILOVER, v).apply()
        _failoverEnabled.value = v
    }

    init {
        // Make sure the mirrored flows match the initial active account.
        syncMirrorsFromActive()
        // If we migrated but no KEY_ACTIVE_ID was stored, persist the
        // freshly-selected active ID so next launch skips the fallback.
        if (prefs.getString(KEY_ACTIVE_ID, null) == null && _activeAccountId.value != null) {
            prefs.edit().putString(KEY_ACTIVE_ID, _activeAccountId.value).apply()
        }
    }

    // --- Coupa-identity mutators --------------------------------------------

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

    /** Derived (or overridden) wallet address to send receipts to. */
    fun currentWalletEmail(): String {
        val override = _walletOverride.value.trim()
        if (override.isNotBlank()) return override
        return deriveCoupaAddress(_userEmail.value, _companyHost.value)
    }

    /** SMTP From / login — pulled from the active saved account. */
    fun currentSenderEmail(): String =
        activeAccountOrNull()?.email?.takeIf { it.isNotBlank() } ?: _userEmail.value.trim()

    // --- Account list API ---------------------------------------------------

    fun activeAccountOrNull(): SmtpAccount? {
        val id = _activeAccountId.value
        val list = _accounts.value
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /** Add (or update by id) an account. Newly-added accounts become
     *  active when there was no active account before. */
    fun upsertAccount(account: SmtpAccount) {
        val list = _accounts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == account.id }
        if (idx >= 0) list[idx] = account else list += account
        _accounts.value = list
        persistAccounts()
        if (_activeAccountId.value == null) setActiveAccount(account.id)
        else if (_activeAccountId.value == account.id) syncMirrorsFromActive()
    }

    fun removeAccount(id: String) {
        val list = _accounts.value.filterNot { it.id == id }
        _accounts.value = list
        persistAccounts()
        if (_activeAccountId.value == id) {
            val next = list.firstOrNull()?.id
            _activeAccountId.value = next
            prefs.edit().putString(KEY_ACTIVE_ID, next).apply()
            syncMirrorsFromActive()
        }
    }

    fun setActiveAccount(id: String) {
        if (_accounts.value.none { it.id == id }) return
        _activeAccountId.value = id
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
        syncMirrorsFromActive()
    }

    // --- Per-field mutators (operate on the active account) ----------------

    fun setSmtpHost(value: String) = mutateActive { it.copy(host = value.trim()) }
    fun setSmtpPort(value: Int) = mutateActive { it.copy(port = value) }
    fun setSmtpPassword(value: String) = mutateActive { it.copy(password = value) }
    fun setSenderEmail(value: String) = mutateActive { it.copy(email = value.trim()) }

    private inline fun mutateActive(transform: (SmtpAccount) -> SmtpAccount) {
        val active = activeAccountOrNull()
        if (active != null) {
            upsertAccount(transform(active))
        } else {
            // No accounts yet — create one from a clean defaults object and
            // apply the mutation. Lets the user build up their first
            // account by filling fields one at a time.
            val fresh = transform(
                SmtpAccount(
                    email = _userEmail.value.trim(),
                    password = "",
                    host = DEFAULT_SMTP_HOST,
                    port = DEFAULT_SMTP_PORT,
                )
            )
            upsertAccount(fresh)
        }
    }

    fun restoreDefaults() {
        prefs.edit().clear().apply()
        _companyHost.value = DEFAULT_HOST
        _userEmail.value = DEFAULT_EMAIL
        _walletOverride.value = ""
        _accounts.value = emptyList()
        _activeAccountId.value = null
        _failoverEnabled.value = true
        syncMirrorsFromActive()
    }

    // --- Internals ----------------------------------------------------------

    private fun syncMirrorsFromActive() {
        val a = activeAccountOrNull()
        _smtpHost.value = a?.host ?: DEFAULT_SMTP_HOST
        _smtpPort.value = a?.port ?: DEFAULT_SMTP_PORT
        _smtpPassword.value = a?.password ?: ""
        _senderEmail.value = a?.email ?: ""
    }

    private fun persistAccounts() {
        val arr = JSONArray()
        for (a in _accounts.value) {
            arr.put(JSONObject().apply {
                put("id", a.id)
                put("email", a.email)
                put("password", a.password)
                put("host", a.host)
                put("port", a.port)
            })
        }
        prefs.edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    /** Load accounts from the JSON pref, falling back to a one-shot
     *  migration from the legacy single-account pref keys. */
    private fun loadAccountsWithMigration(): List<SmtpAccount> {
        val json = prefs.getString(KEY_ACCOUNTS, null)
        if (!json.isNullOrBlank()) {
            runCatching { parseAccounts(json) }
                .onSuccess { return it }
                .onFailure { Log.e(TAG, "accounts JSON parse failed; falling back", it) }
        }
        // Legacy single-account prefs — migrate into one SmtpAccount if
        // they held anything useful.
        val legacyEmail = prefs.getString("sender_email", "") ?: ""
        val legacyPassword = prefs.getString("smtp_password", "") ?: ""
        val legacyHost = prefs.getString("smtp_host", DEFAULT_SMTP_HOST) ?: DEFAULT_SMTP_HOST
        val legacyPort = prefs.getInt("smtp_port", DEFAULT_SMTP_PORT)
        val candidateEmail = legacyEmail.ifBlank { prefs.getString(KEY_EMAIL, DEFAULT_EMAIL) ?: DEFAULT_EMAIL }
        if (legacyPassword.isBlank() && legacyEmail.isBlank()) return emptyList()
        val migrated = SmtpAccount(
            id = UUID.randomUUID().toString(),
            email = candidateEmail,
            password = legacyPassword,
            host = legacyHost,
            port = legacyPort,
        )
        prefs.edit()
            .putString(KEY_ACCOUNTS, JSONArray().apply { put(toJson(migrated)) }.toString())
            .putString(KEY_ACTIVE_ID, migrated.id)
            .apply()
        Log.i(TAG, "Migrated legacy SMTP prefs into SmtpAccount ${migrated.id}")
        return listOf(migrated)
    }

    private fun parseAccounts(json: String): List<SmtpAccount> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SmtpAccount(
                id = o.optString("id", UUID.randomUUID().toString()),
                email = o.optString("email", ""),
                password = o.optString("password", ""),
                host = o.optString("host", DEFAULT_SMTP_HOST),
                port = o.optInt("port", DEFAULT_SMTP_PORT),
            )
        }
    }

    private fun toJson(a: SmtpAccount): JSONObject = JSONObject().apply {
        put("id", a.id); put("email", a.email); put("password", a.password)
        put("host", a.host); put("port", a.port)
    }

    companion object {
        private const val TAG = "SettingsStore"

        private const val PREF_NAME = "receipt_snap_settings"
        private const val KEY_HOST = "company_host"
        private const val KEY_EMAIL = "user_email"
        private const val KEY_OVERRIDE = "coupa_wallet_override"
        private const val KEY_ACCOUNTS = "smtp_accounts_json"
        private const val KEY_ACTIVE_ID = "smtp_active_account_id"
        private const val KEY_FAILOVER = "smtp_failover_enabled"

        private const val DEFAULT_HOST = ""
        private const val DEFAULT_EMAIL = ""
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
