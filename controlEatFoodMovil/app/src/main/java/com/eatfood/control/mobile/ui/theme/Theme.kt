package com.eatfood.control.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta alineada al frontend web (tema oscuro slate).
val Bg = Color(0xFF0F172A)
val Surface = Color(0xFF1E293B)
val SurfaceVariant = Color(0xFF334155)
val OnSurface = Color(0xFFE2E8F0)
val Muted = Color(0xFF94A3B8)
val Primary = Color(0xFF38BDF8)
val OnPrimary = Color(0xFF04263A)
val Success = Color(0xFF16A34A)
val ErrorRed = Color(0xFFDC2626)
val Warning = Color(0xFFF59E0B)

private val DarkColors = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    secondary = Primary,
    background = Bg,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = Muted,
    error = ErrorRed
)

@Composable
fun EatFoodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
