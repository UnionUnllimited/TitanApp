package com.titanvps.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.titanvps.app.data.ThemeMode

// Brand colors (the TitanVPS "T" logo gradient + the blue from the mockups).
val Cyan = Color(0xFF00D6FC)
val Brand = Color(0xFF1F6BFF)
val Magenta = Color(0xFFB63CFF)
val Connected = Color(0xFF22C55E)
val Warning = Color(0xFFF59E0B)
val Danger = Color(0xFFEF4444)

val BrandGradient = Brush.linearGradient(listOf(Cyan, Brand, Magenta))

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Cyan,
    tertiary = Magenta,
    background = Color(0xFF0E131D),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF161C28),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1E2533),
    onSurfaceVariant = Color(0xFF8E97A8),
    surfaceContainer = Color(0xFF131926),
    surfaceContainerLow = Color(0xFF161C28),
    surfaceContainerHigh = Color(0xFF1E2533),
    outline = Color(0xFF2A3242),
    outlineVariant = Color(0xFF232A38),
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1A66FF),
    onPrimary = Color.White,
    secondary = Color(0xFF0891B2),
    tertiary = Color(0xFF9333EA),
    background = Color(0xFFF2F4F8),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF1F6),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFEEF1F6),
    outline = Color(0xFFE1E5EC),
    outlineVariant = Color(0xFFE8EBF0),
    error = Color(0xFFDC2626),
)

/** Ping color that stays readable on both themes. */
@Composable
@ReadOnlyComposable
fun pingColor(ms: Long): Color = when {
    ms < 0 -> MaterialTheme.colorScheme.error
    ms < 500 -> if (MaterialTheme.colorScheme.background == LightColors.background) Color(0xFF16A34A) else Connected
    ms < 1500 -> Warning
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

@Composable
fun TitanTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) DarkColors else LightColors, content = content)
}
