package com.tyler.receiptsnap.processing

import android.util.Log
import java.io.File
import java.util.Properties
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.activation.DataHandler
import javax.mail.util.ByteArrayDataSource

/**
 * Sends one receipt at a time via SMTP (STARTTLS, basic auth). No email
 * client is launched — the PDF goes directly from the app. The user
 * configures the SMTP host, port, and password once in Settings.
 *
 * For Office 365 tenants that disable basic-auth SMTP, the user must create
 * an app password (Microsoft account → Security → app passwords). Errors are
 * bubbled as [SendResult.Failure] with a human-readable message.
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
     * Blocking send — callers MUST run on an IO dispatcher. Blocks until the
     * SMTP transaction completes (a few seconds on good networks).
     */
    fun send(
        config: Config,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachment: File,
    ): SendResult {
        if (config.host.isBlank()) return SendResult.Failure("SMTP host not configured", null)
        if (config.fromEmail.isBlank()) return SendResult.Failure("From email not configured", null)
        if (config.password.isBlank()) return SendResult.Failure("SMTP password not configured", null)
        if (toEmail.isBlank()) return SendResult.Failure("Coupa recipient address is empty", null)

        val props = Properties().apply {
            put("mail.smtp.host", config.host)
            put("mail.smtp.port", config.port.toString())
            put("mail.smtp.auth", "true")
            // STARTTLS: required by Office 365, Gmail, most providers on 587.
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
            put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "30000")
            put("mail.smtp.writetimeout", "30000")
            // SMTPS on 465 — when the user overrides port to 465, flip to
            // implicit-TLS instead of STARTTLS.
            if (config.port == 465) {
                put("mail.smtp.socketFactory.port", "465")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
                put("mail.smtp.starttls.enable", "false")
                put("mail.smtp.starttls.required", "false")
            }
        }

        return try {
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication =
                    PasswordAuthentication(config.fromEmail, config.password)
            })

            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(config.fromEmail))
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

            Transport.send(msg)
            Log.i(TAG, "Sent ${attachment.name} to $toEmail via ${config.host}:${config.port}")
            SendResult.Success
        } catch (t: Throwable) {
            val detail = t.message ?: t.javaClass.simpleName
            Log.e(TAG, "SMTP send failed: $detail", t)
            val friendly = when {
                detail.contains("auth", ignoreCase = true) ||
                    detail.contains("535", ignoreCase = true) ->
                    "Authentication rejected. If your org uses Office 365 with MFA, " +
                        "you need an app password — create one at account.microsoft.com → Security → App passwords."
                detail.contains("timeout", ignoreCase = true) ||
                    detail.contains("connect", ignoreCase = true) ->
                    "Couldn't reach ${config.host}:${config.port}. Check network and SMTP host setting."
                detail.contains("ssl", ignoreCase = true) ||
                    detail.contains("tls", ignoreCase = true) ->
                    "TLS handshake failed. Verify port (587 STARTTLS, 465 SMTPS)."
                else -> "SMTP error: $detail"
            }
            SendResult.Failure(friendly, t)
        }
    }
}
