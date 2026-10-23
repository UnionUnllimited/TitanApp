package com.titanvps.app.ui.kit

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.titanvps.app.R
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.rememberReduceMotion
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/**
 * Seconds since the screen appeared, ~30 times a second; stays at 0 when animations are
 * off. Read it while drawing: only the drawing repeats, nothing is recomposed.
 */
@Composable
private fun rememberSeconds(): MutableFloatState {
    val seconds = remember { mutableFloatStateOf(0f) }
    val reduceMotion = rememberReduceMotion()
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> seconds.floatValue = ((now - start) / 1_000_000_000.0 % 3600.0).toFloat() }
                delay(33)
            }
        }
    }
    return seconds
}

/** A backdrop with slowly drifting light in the logo's colours (splash, key entry). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val palette = Titan.palette
    val seconds = rememberSeconds()
    val strength = if (palette.isDark) 0.42f else 0.26f
    Canvas(modifier.fillMaxSize()) {
        drawRect(palette.background)
        val t = seconds.floatValue
        val w = size.width
        val h = size.height
        auroraBlob(palette.cyan, w * 1.1f, Offset(w * (0.08f * sin(t / 5f) - 0.28f), h * (0.05f * cos(t / 6f) - 0.3f)), strength)
        auroraBlob(palette.primary, w * 1.25f, Offset(w * (0.07f * cos(t / 7f) + 0.3f), h * (0.06f * sin(t / 4.5f) - 0.04f)), strength)
        auroraBlob(palette.violet, w * 1.15f, Offset(w * (0.09f * sin(t / 6.5f) - 0.12f), h * (0.05f * cos(t / 5.5f) + 0.34f)), strength)
    }
}

/** A soft round light of [diameter], moved by [shift] from the middle. */
private fun DrawScope.auroraBlob(color: Color, diameter: Float, shift: Offset, strength: Float) {
    val at = center + shift
    val radius = diameter / 2
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), center = at, radius = radius),
        radius = radius,
        center = at,
    )
}

/** The logo with a soft glow behind it that slowly breathes. Takes 1.1 × [size] of room. */
@Composable
fun GlowingLogo(size: Dp, modifier: Modifier = Modifier) {
    val palette = Titan.palette
    val seconds = rememberSeconds()
    Box(modifier.size(size * 1.1f), contentAlignment = Alignment.Center) {
        // Larger than its room, as on iOS: the glow spills round the logo.
        Canvas(
            Modifier
                .requiredSize(size * 1.6f)
                .graphicsLayer {
                    val breath = sin(seconds.floatValue * 1.4f)
                    scaleX = 1 + 0.06f * breath
                    scaleY = 1 + 0.06f * breath
                    alpha = (0.85f + 0.15f * breath).coerceIn(0f, 1f)
                },
        ) {
            drawCircle(
                Brush.radialGradient(
                    listOf(palette.primary.copy(alpha = 0.5f), palette.violet.copy(alpha = 0.22f), Color.Transparent),
                    center = center,
                    radius = (size * 0.78f).toPx(),
                ),
            )
        }
        val logo = painterResource(R.drawable.logo)
        // The logo's shadow: a blurred blue copy under it (blur needs Android 12).
        if (Build.VERSION.SDK_INT >= 31) {
            Image(
                logo,
                null,
                Modifier
                    .size(size)
                    .graphicsLayer {
                        translationY = (size * 0.05f).toPx()
                        val blur = (size * 0.14f).toPx()
                        renderEffect = BlurEffect(blur, blur, TileMode.Decal)
                    },
                colorFilter = ColorFilter.tint(palette.primary.copy(alpha = 0.45f)),
            )
        }
        Image(logo, null, Modifier.size(size))
    }
}
