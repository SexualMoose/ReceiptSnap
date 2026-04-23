package com.tyler.receiptsnap.processing

import android.util.Log
import java.io.Closeable
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.util.ByteArrayDataSource

/**
 * Sends receipts to Coupa via SMTP. Two usage modes:
 *
 *   Batch — [openConnection] once, reuse the resulting [Connection] for
 *   every message. This is the correct pattern for bulk sends because it
 *   avoids authenticating on every message: servers like Office 365 will
 *   return "545 too many login attempts" if we re-auth for each item.
 *
 *   One-shot — [send] opens a connection, sends one message, closes. Fine
 *   for a single send but wasteful for a queue.
 *
 * For Office 365 tenants that disable basic-auth SMTP entirely the user
 * must configure an app password (Microsoft Account → Security → App
 * passwords). Errors surface as human-readable [SendResult.Failure]s.
 */
object SmtpSender {

    private const val TAG = "SmtpSender"

    sealed interface SendResult {
        data object Success : SendResult
        data class Failure(val message: String, val cause: Throwable?) : SendResult
    }

    data class Config(
        val host: String,
        val port: Int,
        val fromEmail: String,
        val password: String,
    )

    /**
     * Reusable authenticated SMTP connection. Hold it for the duration of
     * a batch send, then [close] to drop the TCP+TLS connection.
     */
    class Connection internal constructor(
        private val session: Session,
        private val transport: Transport,
        private val fromEmail: String,
    ) : Closeable {
        /** Send a single message over the already-authenticated connection.
         *  No additional auth round-trip per call. */
        fun send(
            toEmail: String,
            subject: String,
            bodyText: String,
            attachment: File,
        ): SendResult {
            return try {
                val msg = buildMessage(session, fromEmail, toEmail, subject, bodyText, attachment)
                transport.sendMessage(msg, msg.allRecipients)
                Log.i(TAG, "Sent ${attachment.name} to $toEmail")
                SendResult.Success
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed", t)
                SendResult.Failure(friendlyMessage(t), t)
            }
        }

        override fun close() {
            runCatching { transport.close() }
        }
    }

    /**
     * Open an authenticated SMTP connection. Throws on connect/auth
     * failure so the caller can decide whether to abort the entire batch.
     */
    @Throws(IllegalArgumentException::class, Exception::class)
    fun openConnection(config: Config): Connection {
        if (config.host.isBlank()) throw IllegalArgumentException("SMTP host not configured")
        if (config.fromEmail.isBlank()) throw IllegalArgumentException("From email not configured")
        if (config.password.isBlank()) throw IllegalArgumentException("SMTP password not configured")

        val props = buildProps(config)
        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(config.fromEmail, config.password)
        })
        val transport = session.getTransport("smtp")
        transport.connect(config.host, config.port, config.fromEmail, config.password)
        Log.i(TAG, "Opened SMTP connection to ${config.host}:${config.port}")
        return Connection(session, transport, config.fromEmail)
    }

    /**
     * One-shot send — opens a connection, sends one message, closes. Use
     * [openConnection] when sending a batch to avoid repeated authentication.
     */
    fun send(
        config: Config,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachment: File,
    ): SendResult {
        if (toEmail.isBlank()) return SendResult.Failure("Recipient is empty", null)
        return try {
            openConnection(config).use { conn ->
                conn.send(toEmail, subject, bodyText, attachment)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "send (one-shot) failed", t)
            SendResult.Failure(friendlyMessage(t), t)
        }
    }

    // --- internals ----------------------------------------------------------

    private fun buildProps(config: Config): Properties = Properties().apply {
        put("mail.smtp.host", config.host)
        put("mail.smtp.port", config.port.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.starttls.enable", "true")
        put("mail.smtp.starttls.required", "true")
        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
        put("mail.smtp.connectiontimeout", "15000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.writetimeout", "30000")
        // Implicit TLS for 465 (SMTPS) — disable STARTTLS so we don't
        // double-wrap.
        if (config.port == 465) {
            put("mail.smtp.socketFactory.port", "465")
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            put("mail.smtp.starttls.enable", "false")
            put("mail.smtp.starttls.required", "false")
        }
    }

    private fun buildMessage(
        session: Session,
        fromEmail: String,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachment: File,
    ): MimeMessage {
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail))
            setRecipient(Message.RecipientType.TO, InternetAddress(toEmail))
            setSubject(subject)
            sentDate = java.util.Date()
        }

        val textPart = MimeBodyPart().apply { setText(bodyText) }
        val pdfBytes = attachment.readBytes()
        val pdfPart = MimeBodyPart().apply {
            dataHandler = DataHandler(ByteArrayDataSource(pdfBytes, "application/pdf"))
            fileName = attachment.name
        }

        val multipart = MimeMultipart().apply {
            addBodyPart(textPart)
            addBodyPart(pdfPart)
        }
        msg.setContent(multipart)
        msg.saveChanges()
        return msg
    }

    private fun friendlyMessage(t: Throwable): String {
        val detail = t.message ?: t.javaClass.simpleName
        return when {
            detail.contains("545", ignoreCase = true) ||
                detail.contains("too many", ignoreCase = true) ->
                "Server rejected repeated login. Wait a few minutes before retrying — app passwords can trigger this after many opens per hour."
            detail.contains("auth", ignoreCase = true) ||
                detail.contains("535", ignoreCase = true) ->
                "Authentication rejected. For Office 365 with MFA, create an app password at account.microsoft.com → Security → App passwords and paste it here."
            detail.contains("timeout", ignoreCase = true) ||
                detail.contains("connect", ignoreCase = true) ->
                "Couldn't reach the SMTP server. Check network and host/port settings."
            detail.contains("ssl", ignoreCase = true) ||
                detail.contains("tls", ignoreCase = true) ->
                "TLS handshake failed. Verify port (587 STARTTLS, 465 SMTPS)."
            else -> "SMTP error: $detail"
        }
    }
}
