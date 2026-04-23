package com.tyler.receiptsnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tyler.receiptsnap.ReceiptSnapApp
import com.tyler.receiptsnap.data.SettingsStore

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = (context.applicationContext as ReceiptSnapApp).settings

    val host by settings.companyHost.collectAsState()
    val email by settings.userEmail.collectAsState()
    val sender by settings.senderEmail.collectAsState()
    val override by settings.walletOverride.collectAsState()
    val smtpHost by settings.smtpHost.collectAsState()
    val smtpPort by settings.smtpPort.collectAsState()
    val smtpPassword by settings.smtpPassword.collectAsState()

    val derivedWallet = if (override.isBlank())
        SettingsStore.deriveCoupaAddress(email, host)
    else override

    var confirmRestore by remember { mutableStateOf(false) }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Restore defaults?") },
            text = {
                Text(
                    "Every setting — including the SMTP password — will be " +
                        "reset to its default value."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    settings.restoreDefaults()
                }) { Text("Restore", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF141414),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.8f),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                text = "Settings",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { confirmRestore = true },
            ) {
                Text("Restore defaults", color = Color.White)
            }
        }

        Section(title = "Coupa") {
            DarkOutlinedField(
                value = host,
                onValueChange = settings::setCompanyHost,
                label = "Company host",
                placeholder = "bdpinternational.coupahost.com",
            )
            DarkOutlinedField(
                value = email,
                onValueChange = settings::setUserEmail,
                label = "Your work email (Coupa identity)",
                placeholder = "tyler.keller@psabdp.com",
            )
            Text(
                text = "Receipts will be routed to:",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = derivedWallet.ifBlank { "(enter host and work email first)" },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            DarkOutlinedField(
                value = override,
                onValueChange = settings::setWalletOverride,
                label = "Override wallet address (optional)",
                placeholder = "",
            )
            Text(
                text = "Only set this if Coupa's expected address differs from the derived one.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section(title = "Outgoing Email (SMTP)") {
            AccountSwitcher(settings)

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Editing active account:",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            DarkOutlinedField(
                value = sender,
                onValueChange = settings::setSenderEmail,
                label = "Sender email (SMTP From + login)",
                placeholder = email.ifBlank { "you@example.com" },
            )
            DarkOutlinedField(
                value = smtpHost,
                onValueChange = settings::setSmtpHost,
                label = "SMTP host",
                placeholder = "smtp.office365.com",
            )
            DarkOutlinedField(
                value = smtpPort.toString(),
                onValueChange = { raw -> raw.toIntOrNull()?.let(settings::setSmtpPort) },
                label = "SMTP port",
                placeholder = "587",
                keyboardType = KeyboardType.Number,
            )
            DarkOutlinedField(
                value = smtpPassword,
                onValueChange = settings::setSmtpPassword,
                label = "SMTP password",
                placeholder = "",
                isPassword = true,
            )
            GmailHelpCard()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        settings.setSmtpHost("smtp.gmail.com")
                        settings.setSmtpPort(587)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Gmail defaults", color = MaterialTheme.colorScheme.primary) }
                OutlinedButton(
                    onClick = {
                        settings.setSmtpHost("smtp.office365.com")
                        settings.setSmtpPort(587)
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("O365 defaults", color = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

/**
 * List of saved SMTP accounts with radio-style select for the active one,
 * plus Add and Delete controls. The currently-active account's credentials
 * are what the send loop uses, so switching is the mechanism the user has
 * for rotating off a provider that's hitting a daily cap.
 */
@Composable
private fun AccountSwitcher(settings: com.tyler.receiptsnap.data.SettingsStore) {
    val accounts by settings.accounts.collectAsState()
    val activeId by settings.activeAccountId.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (accounts.isEmpty()) {
            Text(
                text = "No saved accounts yet — fill the fields below to create one.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        for (account in accounts) {
            val isActive = account.id == activeId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        else Color(0xFF0A0A0A)
                    )
                    .clickable { settings.setActiveAccount(account.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = isActive,
                    onClick = { settings.setActiveAccount(account.id) },
                    colors = androidx.compose.material3.RadioButtonDefaults.colors(
                        selectedColor = MaterialTheme.colorScheme.primary,
                        unselectedColor = Color.White.copy(alpha = 0.4f),
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        text = account.hostAndPort +
                            if (account.password.isBlank()) " · no password set" else "",
                        color = Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                androidx.compose.material3.IconButton(
                    onClick = { settings.removeAccount(account.id) },
                ) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove account",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        OutlinedButton(
            onClick = {
                // "Add new" creates a blank account and makes it active so
                // the edit fields immediately point at it. User just fills
                // them in.
                val fresh = com.tyler.receiptsnap.data.SmtpAccount(
                    host = "smtp.office365.com",
                    port = 587,
                )
                settings.upsertAccount(fresh)
                settings.setActiveAccount(fresh.id)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("+ Add account", color = MaterialTheme.colorScheme.primary)
        }

        if (accounts.size > 1) {
            Text(
                text = "Tap a row to switch the active sender. Helpful when one " +
                    "mailbox hits its daily SMTP limit — just switch to the next.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun GmailHelpCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0A0A0A))
            .padding(12.dp),
    ) {
        Text(
            text = "Using Gmail?",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(
            text = "1. Enable 2-step verification at myaccount.google.com.\n" +
                "2. Visit myaccount.google.com/apppasswords and create an app " +
                "password (any name — e.g. \"ReceiptSnap\").\n" +
                "3. Paste the 16-character password above. Your regular Gmail " +
                "password won't work.",
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        content()
    }
}

@Composable
private fun DarkOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.35f)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (isPassword) KeyboardType.Password else keyboardType,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedContainerColor = Color(0xFF0A0A0A),
            unfocusedContainerColor = Color(0xFF0A0A0A),
            focusedLabelColor = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = Color.White.copy(alpha = 0.55f),
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}
