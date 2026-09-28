package com.titanvps.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// Colors taken from the TitanVPS "T" logo gradient.
val Cyan = Color(0xFF00D6FC)
val Brand = Color(0xFF3D6BFF)
val Magenta = Color(0xFFB63CFF)
val Connected = Color(0xFF22C55E)
val Danger = Color(0xFFEF4444)

val BrandGradient = Brush.linearGradient(listOf(Cyan, Brand, Magenta))

private val Colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Cyan,
    tertiary = Magenta,
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF141A29),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1C2336),
    onSurfaceVariant = Color(0xFF9AA3B8),
    surfaceContainerLow = Color(0xFF141A29),
    error = Danger,
)

@Composable
fun TitanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
