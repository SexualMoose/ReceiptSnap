package com.tyler.receiptsnap.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val OledBlack = Color(0xFF000000)
private val Accent = Color(0xFF00E5A0)
private val AccentDim = Color(0xFF00B382)
private val Surface = Color(0xFF0A0A0A)
private val SurfaceHigh = Color(0xFF141414)
private val OnSurface = Color(0xFFE8E8E8)
private val OnSurfaceVariant = Color(0xFFB0B0B0)
private val Error = Color(0xFFFF5C5C)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = OledBlack,
    primaryContainer = AccentDim,
    onPrimaryContainer = OledBlack,
    secondary = AccentDim,
    background = OledBlack,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = OnSurfaceVariant,
    error = Error,
    onError = OledBlack,
)

private val LightColors = lightColorScheme(
    primary = AccentDim,
    onPrimary = Color.White,
)

@Composable
fun ReceiptSnapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = OledBlack.toArgb()
            window.navigationBarColor = OledBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
