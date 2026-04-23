package com.tyler.receiptsnap.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Remembers which saved-receipt URIs have already been sent to Coupa. Keyed
 * by the MediaStore content-URI string; that's stable across app sessions
 * for a given file (until the user deletes or moves it). Stored as a plain
 * string set in SharedPreferences.
 *
 * The LibraryScreen reads the set to draw a small corner checkmark on any
 * tile that's been sent, so the user doesn't accidentally forward the same
 * receipt twice.
 */
class SentTracker(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private val _sent = MutableStateFlow(
        prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    )
    val sent: StateFlow<Set<String>> = _sent.asStateFlow()

    fun isSent(uri: Uri): Boolean = uri.toString() in _sent.value

    fun markSent(uri: Uri) {
        val next = _sent.value + uri.toString()
        prefs.edit().putStringSet(KEY, next).apply()
        _sent.value = next
    }

    /** Remove entries the user has since deleted from MediaStore. Called
     *  opportunistically from LibraryScreen after it loads the current set
     *  of items so the tracker doesn't grow unboundedly. */
    fun retain(validUris: Set<String>) {
        val next = _sent.value.intersect(validUris)
        if (next.size == _sent.value.size) return
        prefs.edit().putStringSet(KEY, next).apply()
        _sent.value = next
    }

    private companion object {
        const val PREF = "receipt_snap_sent"
        const val KEY = "sent_uris"
    }
}
