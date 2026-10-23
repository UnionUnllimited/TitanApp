package com.titanvps.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.iosSpring
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.launch

/**
 * A server row that can be swiped (iOS SwipeActions): right for the favourite (shown on the
 * left), left to ping it (shown on the right). Let go past the mark and it happens; the row
 * always slides back. Only a clearly sideways move holds the row, anything else is the list
 * scrolling. TalkBack gets the same two as actions: [swipeActions] on the row's clickable.
 */
@Composable
internal fun SwipeRow(
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onPing: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val favorite by rememberUpdatedState(onFavorite)
    val ping by rememberUpdatedState(onPing)
    val favoriteTitle = swipeFavoriteTitle(isFavorite)
    val favoriteIcon = if (isFavorite) Icons.Filled.StarBorder else Icons.Filled.Star

    Box(modifier) {
        // What the swipe uncovers, on the side it opens (drawn behind the row).
        SwipeReveal(
            offset = { offset.value },
            favorite = SwipeSide(favoriteIcon, favoriteTitle, palette.orange),
            ping = SwipeSide(Icons.Filled.Speed, "Пинг", palette.primary),
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                // Before the move: the finger is read where the row was, not where it is drawn.
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val decideAt = DECIDE_AT.toPx()
                    val mark = MARK.toPx()
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var horizontal: Boolean? = null
                        var armed = false
                        var dx = 0f
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    if (horizontal == true) {
                                        change.consume()
                                        when {
                                            dx >= mark -> { haptics.success(); favorite() }
                                            dx <= -mark -> { haptics.success(); ping() }
                                        }
                                    }
                                    break
                                }
                                val d = change.position - down.position
                                if (horizontal == null) {
                                    // An up or down move is the list's, at once (before it scrolls).
                                    if (abs(d.y) >= slop && abs(d.x) <= abs(d.y) * 2) {
                                        horizontal = false
                                    } else if (d.getDistance() >= decideAt) {
                                        horizontal = abs(d.x) > abs(d.y) * 2
                                    }
                                }
                                if (horizontal == false) break
                                if (horizontal == true) {
                                    change.consume()
                                    dx = d.x
                                    val now = abs(dx) >= mark
                                    if (now && !armed) haptics.arm()
                                    armed = now
                                    val shown = rubberBand(dx, mark)
                                    scope.launch { offset.snapTo(shown) }
                                }
                            }
                        } finally {
                            if (horizontal == true) scope.launch { offset.animateTo(0f, iosSpring(0.32f, 0.8f)) }
                        }
                    }
                }
                .graphicsLayer { translationX = offset.value },
        ) {
            content()
        }
    }
}

/**
 * The swipes as TalkBack actions. On the row's own clickable node: a wrapper around it isn't
 * focused by TalkBack, so its actions would never be offered.
 */
internal fun Modifier.swipeActions(isFavorite: Boolean, onFavorite: () -> Unit, onPing: () -> Unit): Modifier =
    semantics {
        customActions = listOf(
            CustomAccessibilityAction(swipeFavoriteTitle(isFavorite)) { onFavorite(); true },
            CustomAccessibilityAction("Пинг") { onPing(); true },
        )
    }

private fun swipeFavoriteTitle(isFavorite: Boolean) = if (isFavorite) "Убрать" else "В избранное"

/** What one side of a swipe shows and does. */
private class SwipeSide(val icon: ImageVector, val title: String, val color: Color)

/** Up to the mark it follows the finger; past it, reluctantly. */
private fun rubberBand(dx: Float, mark: Float): Float {
    val far = abs(dx)
    val shown = if (far <= mark) far else mark + (far - mark) * 0.35f
    return if (dx >= 0) shown else -shown
}

/**
 * A rounded block of the action's colour as wide as the gap (and a little under the row), its
 * icon and title in a 76 dp column at the outer edge; full colour once far enough.
 */
@Composable
private fun SwipeReveal(offset: () -> Float, favorite: SwipeSide, ping: SwipeSide, modifier: Modifier) {
    val dx = offset()
    if (dx == 0f) return
    val side = if (dx > 0) favorite else ping
    val onLeft = dx > 0
    val width = abs(dx)
    val density = LocalDensity.current
    val markPx = with(density) { MARK.toPx() }
    val armed = width >= markPx
    val iconScale by animateFloatAsState(if (armed) 1.15f else 0.9f, iosSpring(0.25f, 0.7f), label = "swipeIcon")
    val corner = with(density) { 16.dp.toPx() }
    val extra = with(density) { 24.dp.toPx() }
    Box(
        modifier
            .clearAndSetSemantics {}
            .drawBehind {
                val w = min(width + extra, size.width)
                drawRoundRect(
                    color = side.color.copy(alpha = if (armed) 1f else 0.55f),
                    topLeft = Offset(if (onLeft) 0f else size.width - w, 0f),
                    size = Size(w, size.height),
                    cornerRadius = CornerRadius(corner, corner),
                )
            },
        contentAlignment = if (onLeft) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .width(76.dp)
                .fillMaxHeight()
                .graphicsLayer { alpha = min(width / markPx, 1f) },
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                side.icon,
                null,
                Modifier
                    .size(18.dp)
                    .graphicsLayer {
                        scaleX = iconScale
                        scaleY = iconScale
                    },
                tint = Color.White,
            )
            Text(side.title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** How far before the swipe counts as one (and acts on release). */
private val MARK = 84.dp

/** Moved this far before the direction is decided. */
private val DECIDE_AT = 20.dp
