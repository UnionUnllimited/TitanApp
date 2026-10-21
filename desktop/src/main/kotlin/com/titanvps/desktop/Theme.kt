package com.titanvps.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val Brand = Color(0xFF1A66FF)
val Green = Color(0xFF22C55E)
val Yellow = Color(0xFFF59E0B)
val Orange = Color(0xFFF97316)
val Red = Color(0xFFEF4444)
/** The logo's blue to violet (main buttons, the picked tab) and its cyan. */
val Violet = Color(0xFF5B3DF5)
val Cyan = Color(0xFF00C8FF)

/** Same colours as the iPhone app (Palette in TitanVPSApp.swift), light and dark. */
@Immutable
data class TitanColors(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    /** Bars, chips and pressed rows: a step from the surface. */
    val track: Color,
    val outline: Color,
    val green: Color,
    /** The Выкл | Вкл knob while off: raised above the track. */
    val knob: Color,
    val text: Color,
    val secondary: Color,
    val tertiary: Color,
)

private val DarkColors = TitanColors(
    dark = true,
    background = Color(0xFF0E131D), surface = Color(0xFF161C28), track = Color(0xFF1F2633), outline = Color(0xFF2C3444),
    green = Color(0xFF22C55E), knob = Color(0xFF343D4F),
    text = Color(0xFFE6EAF2), secondary = Color(0xFF8C96A8), tertiary = Color(0xFF5B6476),
)

private val LightColors = TitanColors(
    dark = false,
    background = Color(0xFFF2F4F8), surface = Color.White, track = Color(0xFFE8EBF1), outline = Color(0xFFD5DAE3),
    green = Color(0xFF12A150), knob = Color.White,
    text = Color(0xFF0F172A), secondary = Color(0xFF64748B), tertiary = Color(0xFF9AA4B5),
)

private val LocalTitanColors = staticCompositionLocalOf { DarkColors }

object Palette {
    val colors: TitanColors @Composable @ReadOnlyComposable get() = LocalTitanColors.current

    /** Main buttons, the picked tab: the logo's blue to violet. */
    val accent = Brush.linearGradient(listOf(Brand, Violet), start = Offset.Zero, end = Offset.Infinite)
    /** The logo's colours across a title. */
    val logo = Brush.horizontalGradient(listOf(Cyan, Brand, Violet))
    /** Вкл, switches: dark enough for white text on it. */
    val green = Brush.linearGradient(listOf(Color(0xFF16A34A), Color(0xFF15803D)), start = Offset.Zero, end = Offset.Infinite)
    val red = Brush.linearGradient(listOf(Color(0xFFED4554), Color(0xFF9E1F5C)), start = Offset.Zero, end = Offset.Infinite)
}

private val Dark = darkColorScheme(
    primary = Brand, onPrimary = Color.White,
    background = DarkColors.background, onBackground = DarkColors.text,
    surface = DarkColors.surface, onSurface = DarkColors.text,
    surfaceVariant = DarkColors.track, onSurfaceVariant = DarkColors.secondary,
    surfaceContainerHigh = DarkColors.surface, surfaceContainer = DarkColors.surface,
    outline = Color(0xFF3A4354), outlineVariant = DarkColors.outline,
    error = Red,
)

private val Light = lightColorScheme(
    primary = Brand, onPrimary = Color.White,
    background = LightColors.background, onBackground = LightColors.text,
    surface = LightColors.surface, onSurface = LightColors.text,
    surfaceVariant = LightColors.track, onSurfaceVariant = LightColors.secondary,
    surfaceContainerHigh = LightColors.surface, surfaceContainer = LightColors.surface,
    outline = Color(0xFFCBD2DC), outlineVariant = LightColors.outline,
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
    val dark = isDark(theme)
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = if (dark) Dark else Light) {
        CompositionLocalProvider(LocalTitanColors provides colors, LocalContentColor provides colors.text, content = content)
    }
}

/** Up to 500 ms green, up to 1500 orange, slower or timeout red. */
fun pingColor(ms: Long): Color = when {
    ms < 0 -> Red
    ms < 500 -> Green
    ms < 1500 -> Orange
    else -> Red
}
