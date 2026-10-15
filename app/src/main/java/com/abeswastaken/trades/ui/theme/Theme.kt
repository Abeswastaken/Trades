package com.abeswastaken.trades.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF3DDC97)
private val GreenDark = Color(0xFF138A5B)
private val Red = Color(0xFFFF6B6B)
private val RedDark = Color(0xFFC62828)

private val Dark = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF173D2E),
    onPrimaryContainer = Color(0xFFB9F5D8),
    secondary = Color(0xFF8AB4F8),
    background = Color(0xFF0F1420),
    onBackground = Color(0xFFE6E9EF),
    surface = Color(0xFF0F1420),
    onSurface = Color(0xFFE6E9EF),
    surfaceVariant = Color(0xFF1C2333),
    onSurfaceVariant = Color(0xFFA9B1C2),
    surfaceContainer = Color(0xFF161C2A),
    surfaceContainerHigh = Color(0xFF1C2333),
    outline = Color(0xFF3A4458),
    error = Red,
)

private val Light = lightColorScheme(
    primary = GreenDark,
    primaryContainer = Color(0xFFCDEFE0),
    onPrimaryContainer = Color(0xFF00281A),
    secondary = Color(0xFF1A73E8),
    error = RedDark,
)

@Composable
fun TradesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

/** Green for gains, red for losses. */
@Composable
@ReadOnlyComposable
fun pnlColor(v: Double): Color = when {
    v > 0.005 -> if (isSystemInDarkTheme()) Green else GreenDark
    v < -0.005 -> if (isSystemInDarkTheme()) Red else RedDark
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
