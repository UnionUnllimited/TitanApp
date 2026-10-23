package com.titanvps.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.rememberReduceMotion
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** SwiftUI's .easeOut. */
private val EaseOutCurve = CubicBezierEasing(0f, 0f, 0.58f, 1f)

private fun Float.clampedFraction(): Float = if (isNaN()) 0f else coerceIn(0f, 1f)

/** Траффик and such: a rounded bar; the fill keeps a visible minimum while above zero. */
@Composable
fun GradientBar(value: Float, brush: Brush, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    val fill by animateFloatAsState(value.clampedFraction(), tween(400, easing = EaseOutCurve), label = "bar")
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Titan.palette.track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .layout { measurable, constraints ->
                    val full = constraints.maxWidth
                    val v = fill
                    val width = if (v <= 0f) 0 else max((full * v).roundToInt(), min(8.dp.roundToPx(), full))
                    val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                    layout(width, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .background(brush, CircleShape),
        )
    }
}

/** How much of a ping run is done, as a ring from 12 o'clock (a sliver even at zero). */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    strokeWidth: Dp = 2.5.dp,
) {
    val sweep by animateFloatAsState(max(progress.clampedFraction(), 0.04f), tween(300, easing = EaseOutCurve), label = "ring")
    Canvas(modifier) {
        val stroke = strokeWidth.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arc = Size(size.width - stroke, size.height - stroke)
        drawArc(color.copy(alpha = 0.3f), 0f, 360f, false, topLeft, arc, style = Stroke(stroke))
        drawArc(color, -90f, 360f * sweep, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/**
 * A light band running across the content (a shine on something busy). It tints only the
 * content's own pixels, within [shape]. Off when not [enabled] or animations are off.
 * Put it before the modifiers that draw what should shine (background, text).
 */
fun Modifier.shimmer(
    enabled: Boolean,
    color: Color,
    durationMillis: Int = 1400,
    shape: Shape = RectangleShape,
): Modifier = composed {
    val reduceMotion = rememberReduceMotion()
    if (!enabled || reduceMotion) return@composed Modifier
    val t = remember { Animatable(0f) }
    LaunchedEffect(durationMillis) {
        while (true) {
            t.snapTo(0f)
            t.animateTo(1f, tween(durationMillis.coerceAtLeast(1), easing = LinearEasing))
        }
    }
    Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            val x = (t.value * 1.9f - 0.45f) * w
            val band = Brush.horizontalGradient(
                listOf(color.copy(alpha = 0f), color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
                startX = x,
                endX = x + w * 0.45f,
            )
            drawOutline(shape.createOutline(size, layoutDirection, this), band, blendMode = BlendMode.SrcAtop)
        }
}

/** A short shake left and right (a refused tap): [shake] from a composition's coroutine. */
class ShakeController {
    internal val progress = Animatable(0f)

    suspend fun shake() {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(400, easing = LinearEasing))
    }
}

@Composable
fun rememberShakeController(): ShakeController = remember { ShakeController() }

fun Modifier.shake(controller: ShakeController): Modifier = graphicsLayer {
    translationX = 7.dp.toPx() * sin(controller.progress.value * 4 * PI.toFloat())
}

/** A placeholder while something loads: a surface block with a running shine. */
@Composable
fun Skeleton(modifier: Modifier, shape: Shape = RoundedCornerShape(20.dp)) {
    val palette = Titan.palette
    Box(modifier.shimmer(true, palette.outline, 1300, shape).background(palette.surface, shape))
}
