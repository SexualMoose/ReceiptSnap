package com.tyler.receiptsnap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tyler.receiptsnap.ReceiptSnapApp

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = (context.applicationContext as ReceiptSnapApp).settings

    val host by settings.companyHost.collectAsState()
    val email by settings.userEmail.collectAsState()
    val override by settings.walletOverride.collectAsState()
    val smtpHost by settings.smtpHost.collectAsState()
    val smtpPort by settings.smtpPort.collectAsState()
    val smtpPassword by settings.smtpPassword.collectAsState()
    val derived = if (override.isBlank())
        com.tyler.receiptsnap.data.SettingsStore.deriveCoupaAddress(email, host)
    else override

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "Settings",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium,
        )

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
                label = "Your work email",
                placeholder = "tyler.keller@psabdp.com",
            )
            Text(
                text = "Receipts will be emailed to:",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = derived.ifBlank { "(enter host and email first)" },
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
            DarkOutlinedField(
                value = smtpHost,
                onValueChange = settings::setSmtpHost,
                label = "SMTP host",
                placeholder = "smtp.office365.com",
            )
            DarkOutlinedField(
                value = smtpPort.toString(),
                onValueChange = { raw ->
                    raw.toIntOrNull()?.let { settings.setSmtpPort(it) }
                },
                label = "SMTP port",
                placeholder = "587",
                keyboardType = KeyboardType.Number,
            )
            DarkOutlinedField(
                value = smtpPassword,
                onValueChange = settings::setSmtpPassword,
                label = "SMTP password (or app password)",
                placeholder = "",
                isPassword = true,
            )
            Text(
                text = "For Microsoft 365 with MFA, create an app password at " +
                    "account.microsoft.com → Security → App passwords, and " +
                    "paste it here.",
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
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
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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

