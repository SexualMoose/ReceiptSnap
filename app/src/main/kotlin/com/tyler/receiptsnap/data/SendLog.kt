package com.tyler.receiptsnap.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Append-only audit log of every successful Coupa send. Used so the
 * user can:
 *
 *   - Confirm a receipt actually went out (the Sent badge in the gallery
 *     shows it left, the log row shows when, to whom, from which sender,
 *     and with what RFC 5322 Message-ID).
 *   - Match against potential mailer-daemon bounces by Message-ID later.
 *   - Hand the export to corporate audit when asked "prove you submitted
 *     this receipt by the deadline".
 *
 * Stored as a JSON array in SharedPreferences. We cap the in-memory list
 * at [MAX_ENTRIES] so devices that have processed thousands don't rewrite
 * a multi-MB pref blob on every send.
 */
class SendLog(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(
        receiptName: String,
        recipient: String,
        fromAccount: String,
        messageId: String?,
        smtpHost: String,
    ) {
        val entry = Entry(
            id = UUID.randomUUID().toString(),
            timestampMs = System.currentTimeMillis(),
            receiptName = receiptName,
            recipient = recipient,
            fromAccount = fromAccount,
            messageId = messageId,
            smtpHost = smtpHost,
        )
        val next = (_entries.value + entry).takeLast(MAX_ENTRIES)
        save(next)
        _entries.value = next
    }

    fun clear() {
        save(emptyList())
        _entries.value = emptyList()
    }

    /**
     * One sent receipt. [messageId] may be null if the SMTP server didn't
     * report one (rare but possible with some relays).
     */
    data class Entry(
        val id: String,
        val timestampMs: Long,
        val receiptName: String,
        val recipient: String,
        val fromAccount: String,
        val messageId: String?,
        val smtpHost: String,
    )

    // --- persistence --------------------------------------------------------

    private fun load(): List<Entry> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    id = o.optString("id", UUID.randomUUID().toString()),
                    timestampMs = o.optLong("ts", 0L),
                    receiptName = o.optString("name"),
                    recipient = o.optString("to"),
                    fromAccount = o.optString("from"),
                    messageId = o.optString("mid", "").takeIf { it.isNotBlank() },
                    smtpHost = o.optString("host"),
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "send log JSON parse failed; resetting", t)
            emptyList()
        }
    }

    private fun save(list: List<Entry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(JSONObject().apply {
                put("id", e.id)
                put("ts", e.timestampMs)
                put("name", e.receiptName)
                put("to", e.recipient)
                put("from", e.fromAccount)
                put("mid", e.messageId.orEmpty())
                put("host", e.smtpHost)
            })
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val TAG = "SendLog"
        const val PREF = "receipt_snap_send_log"
        const val KEY = "entries"
        const val MAX_ENTRIES = 2000
    }
}
