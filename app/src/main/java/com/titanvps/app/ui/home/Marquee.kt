package com.titanvps.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.titanvps.app.ui.theme.rememberReduceMotion
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * A one-line name (iOS MarqueeText). It never widens its row: when it doesn't fit it waits,
 * scrolls to its end, waits, comes back and starts over; one that fits stays still. Animations
 * off: cut with an ellipsis.
 */
@Composable
internal fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit,
    fontWeight: FontWeight,
    color: Color = Color.Unspecified,
) {
    if (rememberReduceMotion()) {
        Text(
            text,
            modifier,
            color = color,
            fontSize = fontSize,
            fontWeight = fontWeight,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
        return
    }
    val offset = remember { Animatable(0f) }
    var boxWidth by remember { mutableIntStateOf(0) }
    var textWidth by remember { mutableIntStateOf(0) }
    val speed = with(LocalDensity.current) { SPEED.toPx() }
    LaunchedEffect(text, boxWidth, textWidth) {
        offset.snapTo(0f)
        val distance = textWidth - boxWidth
        if (boxWidth <= 0 || distance <= 1) return@LaunchedEffect
        val scrollMs = (distance / speed * 1000).roundToInt().coerceAtLeast(1)
        while (true) {
            delay(2000)
            offset.animateTo(-distance.toFloat(), tween(scrollMs, easing = LinearEasing))
            delay(2500)
            offset.animateTo(0f, tween(400, easing = FastOutSlowInEasing))
            delay(400)
        }
    }
    Layout(
        content = {
            Text(
                text,
                Modifier.onSizeChanged { textWidth = it.width },
                color = color,
                fontSize = fontSize,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
            )
        },
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { boxWidth = it.width },
    ) { measurables, constraints ->
        // The text as wide as it is; the box no wider than it may be.
        val line = measurables.first().measure(Constraints(maxHeight = constraints.maxHeight))
        val width = line.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = line.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            // Read here: the scroll only moves the text, nothing is recomposed.
            line.placeRelative(offset.value.roundToInt(), (height - line.height) / 2)
        }
    }
}

/** iOS: 30 points a second. */
private val SPEED = 30.dp
