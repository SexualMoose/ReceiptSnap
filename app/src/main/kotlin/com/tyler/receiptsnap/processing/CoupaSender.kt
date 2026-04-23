package com.tyler.receiptsnap.processing

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Builds an `ACTION_SEND` intent that email apps (Gmail, Outlook, etc.) will
 * handle — pre-filled with the Coupa wallet recipient and a single PDF
 * attachment. The user reviews and taps Send in their mail client, which
 * naturally comes from their account so Coupa attributes the receipt
 * correctly.
 */
object CoupaSender {

    fun buildIntent(
        context: Context,
        pdfFile: File,
        recipient: String,
        subject: String,
    ): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            pdfFile,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, "Receipt attached (via ReceiptSnap).")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
