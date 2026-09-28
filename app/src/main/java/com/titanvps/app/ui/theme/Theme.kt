package com.titanvps.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF3D7BFF)
val Connected = Color(0xFF22C55E)
val Danger = Color(0xFFEF4444)

private val Colors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    background = Color(0xFF0B0F1A),
    onBackground = Color(0xFFE6E9F2),
    surface = Color(0xFF141A29),
    onSurface = Color(0xFFE6E9F2),
    surfaceVariant = Color(0xFF1C2336),
    onSurfaceVariant = Color(0xFF9AA3B8),
    error = Danger,
)

@Composable
fun TitanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
