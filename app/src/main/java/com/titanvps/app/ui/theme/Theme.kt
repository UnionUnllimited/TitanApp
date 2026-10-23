package com.titanvps.app.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.titanvps.app.data.ThemeMode
import com.titanvps.app.ui.kit.ToastHost
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow

// Brand colors (the TitanVPS "T" logo gradient: cyan → blue → violet, as on iOS).
val Cyan = Color(0xFF00C8FF)
val Brand = Color(0xFF1A66FF)
val Magenta = Color(0xFFB63CFF)
val Connected = Color(0xFF22C55E)
val Warning = Color(0xFFFF9500)
val Danger = Color(0xFFEF4444)
private val Violet = Color(0xFF5B3DF5)

val BrandGradient = Brush.linearGradient(listOf(Cyan, Brand, Magenta))

/** Main buttons, the picked tab and theme: the logo's blue to violet, top-left to bottom-right. */
val AccentGradient: Brush = Brush.linearGradient(listOf(Color(0xFF1A66FF), Color(0xFF5B3DF5)))

/** The logo's colours across a title. */
val LogoGradient: Brush = Brush.horizontalGradient(listOf(Color(0xFF00C8FF), Color(0xFF1A66FF), Color(0xFF5B3DF5)))

/** A switch that is on, the connected state. */
val GreenGradient: Brush = Brush.linearGradient(listOf(Color(0xFF16A34A), Color(0xFF15803D)))

/** The status card while the VPN is off. */
val HeroInactiveGradient: Brush = Brush.linearGradient(listOf(Color(0xFFED4554), Color(0xFF9E1F5C)))

/** Traffic or days running low. */
val WarnGradient: Brush = Brush.horizontalGradient(listOf(Color(0xFFFFCC00), Color(0xFFFF9500)))

/** Traffic or days nearly gone. */
val DangerGradient: Brush = Brush.horizontalGradient(listOf(Color(0xFFFF9500), Color(0xFFFF3B30)))

/** The app's colours beyond Material's scheme (iOS Palette), read with [Titan.palette]. */
@Immutable
data class TitanPalette(
    val primary: Color,
    val background: Color,
    val surface: Color,
    /** Bars' and pressed rows' background. */
    val track: Color,
    val outline: Color,
    val green: Color,
    val violet: Color,
    val cyan: Color,
    /** The Выкл | Вкл knob while off: raised above the track. */
    val knob: Color,
    val orange: Color,
    val red: Color,
    val yellow: Color,
    val gray: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val isDark: Boolean,
)

private val LightPalette = TitanPalette(
    primary = Color(0xFF1A66FF),
    background = Color(0xFFF2F4F8),
    surface = Color(0xFFFFFFFF),
    track = Color(0xFFE8EBF1),
    outline = Color(0xFFD5DAE3),
    green = Color(0xFF12A150),
    violet = Color(0xFF5B3DF5),
    cyan = Color(0xFF00C8FF),
    knob = Color(0xFFFFFFFF),
    orange = Color(0xFFFF9500),
    red = Color(0xFFFF3B30),
    yellow = Color(0xFFFFCC00),
    gray = Color(0xFF8E8E93),
    textSecondary = Color(0xFF6B7280),
    textTertiary = Color(0xFF111827).copy(alpha = 0.3f),
    isDark = false,
)

private val DarkPalette = TitanPalette(
    primary = Color(0xFF1A66FF),
    background = Color(0xFF0E131D),
    surface = Color(0xFF161C28),
    track = Color(0xFF1F2633),
    outline = Color(0xFF2C3444),
    green = Color(0xFF22C55E),
    violet = Color(0xFF5B3DF5),
    cyan = Color(0xFF00C8FF),
    knob = Color(0xFF343D4F),
    orange = Color(0xFFFF9F0A),
    red = Color(0xFFFF453A),
    yellow = Color(0xFFFFD60A),
    gray = Color(0xFF8E8E93),
    textSecondary = Color(0xFF8E97A8),
    textTertiary = Color(0xFFE8ECF4).copy(alpha = 0.3f),
    isDark = true,
)

val LocalTitanPalette = staticCompositionLocalOf { LightPalette }

object Titan {
    val palette: TitanPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalTitanPalette.current
}

private val DarkColors = darkColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Cyan,
    tertiary = Violet,
    background = Color(0xFF0E131D),
    onBackground = Color(0xFFE8ECF4),
    surface = Color(0xFF161C28),
    onSurface = Color(0xFFE8ECF4),
    surfaceVariant = Color(0xFF1F2633),
    onSurfaceVariant = Color(0xFF8E97A8),
    surfaceContainer = Color(0xFF131926),
    surfaceContainerLow = Color(0xFF161C28),
    surfaceContainerHigh = Color(0xFF1F2633),
    outline = Color(0xFF2C3444),
    outlineVariant = Color(0xFF262E3D),
    error = Color(0xFFFF453A),
)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    secondary = Cyan,
    tertiary = Violet,
    background = Color(0xFFF2F4F8),
    onBackground = Color(0xFF111827),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE8EBF1),
    onSurfaceVariant = Color(0xFF6B7280),
    surfaceContainer = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color(0xFFE8EBF1),
    outline = Color(0xFFD5DAE3),
    outlineVariant = Color(0xFFE2E6EC),
    error = Color(0xFFFF3B30),
)

/** Text everywhere is a notch smaller than the system default (the design's base size). */
const val TEXT_BASE = 0.88f

/** The phone's text size grows the app's text up to this much (iOS Dynamic Type: 1.25×). */
const val MAX_FONT_SCALE = 1.25f

/**
 * A font size that never grows with the phone's text size: iOS sizes of 24 and up (titles,
 * big numbers), so that the design holds. Smaller sizes use plain .sp, capped by [TitanTheme].
 */
@Composable
@ReadOnlyComposable
fun fixedSp(size: Float): TextUnit = (size * TEXT_BASE / LocalDensity.current.fontScale).sp

/** SwiftUI's spring(response:dampingFraction:) as a Compose spring. */
fun <T> iosSpring(response: Float, damping: Float): SpringSpec<T> =
    spring(dampingRatio = damping, stiffness = ((2 * PI / response).pow(2)).toFloat())

/** Animations are off in the phone's settings (Специальные возможности → Удалить анимацию). */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

/** Ping color that stays readable on both themes. */
@Composable
@ReadOnlyComposable
fun pingColor(ms: Long): Color {
    val palette = Titan.palette
    return when {
        ms < 0 -> palette.red
        ms < 500 -> palette.green
        ms < 1500 -> palette.orange
        else -> palette.red
    }
}

@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/**
 * The app's text size: the system one capped at [MAX_FONT_SCALE], on the [TEXT_BASE] base.
 * Read from the configuration, not the current density, so applying it twice changes nothing.
 */
@Composable
@ReadOnlyComposable
private fun titanDensity(): Density {
    val density = LocalDensity.current
    val system = LocalConfiguration.current.fontScale
    return Density(density.density, min(system, MAX_FONT_SCALE) * TEXT_BASE)
}

/**
 * Dialogs, bottom sheets and menus are windows of their own with the system's text size:
 * wrap their content in this to get the app's (as [TitanTheme] does for the screen).
 */
@Composable
fun ProvideTitanTextScale(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides titanDensity(), content = content)
}

@Composable
fun TitanTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    overlayToasts: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = isDark(mode)
    val colors = if (dark) DarkColors else LightColors
    CompositionLocalProvider(
        LocalTitanPalette provides if (dark) DarkPalette else LightPalette,
        LocalDensity provides titanDensity(),
    ) {
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
                // Min constraints passed on: the screen gets the whole window, as without the Box.
                Box(Modifier.fillMaxSize(), propagateMinConstraints = true) {
                    content()
                    if (overlayToasts) ToastHost()
                }
            }
        }
    }
}
