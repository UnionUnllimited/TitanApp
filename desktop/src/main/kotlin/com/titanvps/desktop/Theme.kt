package com.titanvps.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF1A66FF)
val Green = Color(0xFF22C55E)
val Yellow = Color(0xFFF59E0B)
val Red = Color(0xFFEF4444)

private val Dark = darkColorScheme(
    primary = Brand, onPrimary = Color.White,
    background = Color(0xFF0E131D), onBackground = Color(0xFFE6EAF2),
    surface = Color(0xFF161C28), onSurface = Color(0xFFE6EAF2),
    surfaceVariant = Color(0xFF1F2633), onSurfaceVariant = Color(0xFF8C96A8),
    outline = Color(0xFF3A4354), outlineVariant = Color(0xFF2C3444),
    error = Red,
)

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    background = Color(0xFFF2F4F8), onBackground = Color(0xFF0F172A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE8EBF1), onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD2DC), outlineVariant = Color(0xFFE2E6EC),
    error = Color(0xFFDC2626),
)

@Composable
fun isDark(theme: String) = when (theme) {
    "dark" -> true
    "light" -> false
    else -> isSystemInDarkTheme()
}

@Composable
fun TitanTheme(theme: String, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(theme)) Dark else Light, content = content)
}

/** Up to 500 ms green, up to 1500 yellow, slower or timeout red. */
fun pingColor(ms: Long): Color = when {
    ms < 0 -> Red
    ms < 500 -> Green
    ms < 1500 -> Yellow
    else -> Red
}
