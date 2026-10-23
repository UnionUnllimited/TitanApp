package com.titanvps.app.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.kit.rememberShakeController
import com.titanvps.app.ui.kit.shake
import com.titanvps.app.ui.kit.shimmer
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.iosSpring
import com.titanvps.app.ui.theme.rememberReduceMotion
import com.titanvps.app.vpn.VpnState
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the slider is, from the VPN's state. */
private enum class SliderPhase { OFF, CONNECTING, ON, DISCONNECTING }

private fun phaseOf(state: VpnState): SliderPhase = when (state) {
    VpnState.Connecting -> SliderPhase.CONNECTING
    is VpnState.Connected -> SliderPhase.ON
    VpnState.Disconnecting -> SliderPhase.DISCONNECTING
    else -> SliderPhase.OFF
}

/** The knob's place and what the gesture knows; nothing here but [maxX] and [pending] recomposes. */
private class SliderModel(initial: VpnState) {
    val knobX = Animatable(0f)
    val nudge = Animatable(0f)
    /** How far the knob travels (half the inner width), px; 0 before the first layout. */
    var maxX by mutableFloatStateOf(0f)
    /** The side just picked, held until the VPN's state follows (or for a moment at most). */
    var pending by mutableStateOf<Boolean?>(null)
    /** The width the knob was last put in place for (it snaps there, no slide). */
    var placedFor = -1f
    var dragging = false
    var lastState: VpnState = initial

    /** 0 at Выкл … 1 at Вкл, also while dragged; [isOn]'s side before the first layout. */
    fun progress(isOn: Boolean): Float =
        if (placedFor <= 0f || maxX <= 0f) (if (isOn) 1f else 0f) else (knobX.value / maxX).coerceIn(0f, 1f)
}

/**
 * Выкл | Вкл as one slider (iOS PowerSlider): the knob is dragged across, or a side tapped.
 * Off: a red knob on a translucent red track; on: green; while it connects or goes off: the
 * brand gradient, with a slow shine and a breathing glow. [onToggle] returns false when it
 * can't be done now: the knob goes back with a shake. No ping lock here: on Android pings
 * never pause the VPN.
 */
@Composable
internal fun PowerSlider(state: VpnState, onToggle: (Boolean) -> Boolean, modifier: Modifier = Modifier) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val shaker = rememberShakeController()
    val reduceMotion = rememberReduceMotion()
    val density = LocalDensity.current
    val model = remember { SliderModel(state) }

    val phase = phaseOf(state)
    val busy = phase == SliderPhase.CONNECTING || phase == SliderPhase.DISCONNECTING
    val isOn = model.pending ?: (phase == SliderPhase.ON || phase == SliderPhase.CONNECTING)
    val latestState by rememberUpdatedState(state)
    val toggle by rememberUpdatedState(onToggle)
    val isOnNow = { model.pending ?: phaseOf(latestState).let { it == SliderPhase.ON || it == SliderPhase.CONNECTING } }
    val isOnState = rememberUpdatedState(isOn)
    val progress = { model.progress(isOnState.value) }
    val lit by remember { derivedStateOf { model.progress(isOnState.value) > 0.5f } }

    // Brand blue while it connects or goes off, faded in and out.
    val busyAmount = animateFloatAsState(if (busy) 1f else 0f, tween(250), label = "sliderBusy")
    val breath = remember { Animatable(1f) }
    val sweep = remember { Animatable(0f) }
    val moving = busy && !reduceMotion
    LaunchedEffect(moving) {
        if (!moving) {
            breath.animateTo(1f, tween(300))
            return@LaunchedEffect
        }
        // One breath in about 2.5 s, between half and full strength.
        while (true) {
            breath.animateTo(0.5f, tween(1250, easing = FastOutSlowInEasing))
            breath.animateTo(1f, tween(1250, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(moving) {
        sweep.snapTo(0f)
        if (!moving) return@LaunchedEffect
        while (true) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, tween(1200, easing = LinearEasing))
        }
    }

    // The knob follows the state: in place at once the first time, then sliding.
    LaunchedEffect(isOn, model.maxX) {
        val maxX = model.maxX
        if (maxX <= 0f) return@LaunchedEffect
        val target = if (isOn) maxX else 0f
        if (model.placedFor != maxX) {
            model.knobX.snapTo(target)
            model.placedFor = maxX
            return@LaunchedEffect
        }
        if (!model.dragging) model.knobX.animateTo(target, iosSpring(0.38f, 0.78f))
    }
    LaunchedEffect(phase) { model.pending = null }
    LaunchedEffect(model.pending) {
        if (model.pending == null) return@LaunchedEffect
        delay(1500)
        model.pending = null
    }
    // A connect that failed: the knob is back at Выкл, with a shake.
    LaunchedEffect(state) {
        val before = model.lastState
        model.lastState = state
        if (state is VpnState.Error && before !is VpnState.Error) {
            model.pending = null
            haptics.error()
            shaker.shake()
        }
    }

    fun refuse() {
        haptics.error()
        scope.launch { shaker.shake() }
    }

    fun commit(on: Boolean) {
        if (toggle(on)) {
            haptics.medium()
            model.pending = on
        } else {
            refuse()
            val back = if (isOnNow()) model.maxX else 0f
            scope.launch { model.knobX.animateTo(back, iosSpring(0.35f, 0.8f)) }
        }
    }

    /** A tap on the knob's own side: it leans toward the other one, to show it can be dragged. */
    fun hint() {
        haptics.soft()
        val toward = with(density) { LEAN.toPx() } * if (isOnNow()) -1f else 1f
        scope.launch {
            launch { model.nudge.animateTo(toward, iosSpring(0.2f, 0.5f)) }
            delay(180)
            model.nudge.animateTo(0f, iosSpring(0.35f, 0.55f))
        }
    }

    fun tapped(right: Boolean) {
        if (right == isOnNow()) hint() else commit(right)
    }

    val green = Color(0xFF22C55E)
    val red = Color(0xFFEF4444)
    val blue = palette.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(HEIGHT)
            .shake(shaker)
            .onSizeChanged { model.maxX = ((it.width - 2 * with(density) { INSET.toPx() }) / 2f).coerceAtLeast(1f) }
            .drawWithCache {
                val radius = size.height / 2
                val capsule = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius, radius)) }
                val border = 1.5.dp.toPx()
                onDrawBehind {
                    val p = progress()
                    val b = busyAmount.value
                    // A soft light round the slider (outside it only: the track stays see-through).
                    val glowColor = lerp(lerp(red, Color(0xFF16A34A), p), blue, b)
                    val glowStrength = mix(mix(0.25f, 0.3f, p), 0.4f * breath.value, b)
                    val glowRadius = mix(mix(16.dp.toPx(), 24.dp.toPx(), p), 20.dp.toPx(), b)
                    clipPath(capsule, ClipOp.Difference) {
                        capsuleGlow(Offset.Zero, size, glowColor, glowStrength, glowRadius)
                    }
                    // The track: a translucent tint of the state's colour, a little deeper to one side.
                    val left = lerp(lerp(red.copy(alpha = 0.2f), green.copy(alpha = 0.13f), p), blue.copy(alpha = 0.12f), b)
                    val right = lerp(lerp(red.copy(alpha = 0.12f), green.copy(alpha = 0.2f), p), blue.copy(alpha = 0.12f), b)
                    drawRoundRect(
                        brush = Brush.horizontalGradient(listOf(left, right)),
                        cornerRadius = CornerRadius(radius, radius),
                    )
                    if (moving) {
                        clipPath(capsule) {
                            val x = (sweep.value * 1.9f - 0.45f) * size.width
                            drawRect(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, blue.copy(alpha = 0.35f), Color.Transparent),
                                    startX = x,
                                    endX = x + size.width * 0.45f,
                                ),
                            )
                        }
                    }
                    val edge = lerp(lerp(red.copy(alpha = 0.5f), green.copy(alpha = 0.4f), p), blue.copy(alpha = 0.55f), b)
                    drawRoundRect(
                        color = edge,
                        topLeft = Offset(border / 2, border / 2),
                        size = Size(size.width - border, size.height - border),
                        cornerRadius = CornerRadius(radius - border / 2, radius - border / 2),
                        style = Stroke(border),
                    )
                }
            }
            .pointerInput(Unit) {
                val decideAt = DECIDE_AT.toPx()
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var horizontal: Boolean? = null
                    var moved = false
                    var wasOn = false
                    var base = 0f
                    var x = 0f
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                if (horizontal == true) {
                                    change.consume()
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    model.dragging = false
                                    // Where it was let go, and where a flick would have thrown it.
                                    val thrown = x + tracker.calculateVelocity().x * 0.2f
                                    val target = (x + thrown) / 2 > model.maxX / 2
                                    if (target != wasOn) {
                                        commit(target)
                                    } else {
                                        val back = if (wasOn) model.maxX else 0f
                                        scope.launch { model.knobX.animateTo(back, iosSpring(0.35f, 0.8f)) }
                                    }
                                } else if (horizontal == null && !moved) {
                                    change.consume()
                                    tapped(down.position.x > size.width / 2f)
                                }
                                break
                            }
                            val d = change.position - down.position
                            if (d.getDistance() > slop) moved = true
                            if (horizontal == null) {
                                // An up or down move is the list's, at once (before it scrolls).
                                if (abs(d.y) >= slop && abs(d.x) <= abs(d.y) * 1.5f) {
                                    horizontal = false
                                } else if (d.getDistance() >= decideAt) {
                                    horizontal = abs(d.x) > abs(d.y) * 1.5f
                                    if (horizontal == true) {
                                        model.dragging = true
                                        wasOn = isOnNow()
                                        base = if (wasOn) model.maxX else 0f
                                        x = base
                                    }
                                }
                            }
                            if (horizontal == false) break
                            if (horizontal == true) {
                                // Held: the list doesn't scroll meanwhile.
                                change.consume()
                                tracker.addPosition(change.uptimeMillis, change.position)
                                val next = (base + d.x).coerceIn(0f, model.maxX)
                                // A tick when the knob passes the middle.
                                if ((next > model.maxX / 2) != (x > model.maxX / 2)) haptics.selection()
                                x = next
                                scope.launch { model.knobX.animateTo(next, iosSpring(0.18f, 0.86f)) }
                            }
                        }
                    } finally {
                        model.dragging = false
                    }
                }
            }
            .clearAndSetSemantics {
                role = Role.Switch
                contentDescription = "VPN"
                stateDescription = if (isOn) "включён" else "выключен"
                onClick {
                    commit(!isOnNow())
                    true
                }
            }
            .padding(INSET),
    ) {
        SideLabels(progress, showHint = phase == SliderPhase.OFF)
        Knob(
            phase = phase,
            lit = lit,
            offset = { model.progress(isOnState.value) * model.maxX + model.nudge.value },
            progress = progress,
            busyAmount = { busyAmount.value },
        )
    }
}

/** What the free half of the track says: where the knob would go. */
@Composable
private fun SideLabels(progress: () -> Float, showHint: Boolean) {
    val palette = Titan.palette
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).graphicsLayer { alpha = progress() }, contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(20.dp), tint = palette.textSecondary)
                Text("Выкл", color = palette.textSecondary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f).graphicsLayer { alpha = 1f - progress() }, contentAlignment = Alignment.Center) {
            Row(
                Modifier.shimmer(showHint, palette.primary, 2000),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Вкл", color = palette.textSecondary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = palette.textSecondary)
            }
        }
    }
}

/**
 * The knob: red off, green on, the brand gradient while it connects or goes off, each with
 * its own soft glow; white symbol and text on all of them.
 */
@Composable
private fun Knob(
    phase: SliderPhase,
    lit: Boolean,
    offset: () -> Float,
    progress: () -> Float,
    busyAmount: () -> Float,
) {
    Box(
        Modifier
            .fillMaxWidth(0.5f)
            .fillMaxHeight()
            .graphicsLayer { translationX = offset() }
            .drawBehind {
                val p = progress()
                val b = busyAmount()
                val radius = size.height / 2
                val corner = CornerRadius(radius, radius)
                val glow = lerp(lerp(KnobRed[1], KnobGreen[1], p), KnobBlue, b)
                capsuleGlow(Offset(0f, 3.dp.toPx()), size, glow, 0.45f, 10.dp.toPx())
                drawRoundRect(brush = Brush.verticalGradient(KnobRed), cornerRadius = corner)
                if (p > 0f) drawRoundRect(brush = Brush.verticalGradient(KnobGreen), cornerRadius = corner, alpha = p)
                if (b > 0f) drawRoundRect(brush = AccentGradient, cornerRadius = corner, alpha = b)
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            when {
                phase == SliderPhase.CONNECTING || phase == SliderPhase.DISCONNECTING -> {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    KnobText(if (phase == SliderPhase.CONNECTING) "Подключение" else "Отключение")
                }
                lit -> {
                    Icon(Icons.Filled.VerifiedUser, null, Modifier.size(22.dp), tint = Color.White)
                    KnobText("Вкл")
                }
                else -> {
                    Icon(Icons.Filled.PowerSettingsNew, null, Modifier.size(22.dp), tint = Color.White)
                    KnobText("Выкл")
                }
            }
        }
    }
}

/** One line on the knob; shrinks (down to 0.6) rather than cut when the knob is narrow. */
@Composable
private fun RowScope.KnobText(text: String) {
    var scale by remember(text) { mutableFloatStateOf(1f) }
    Text(
        text,
        Modifier.weight(1f, fill = false),
        color = Color.White,
        fontSize = 22.sp * scale,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow && scale > 0.6f) scale = (scale - 0.08f).coerceAtLeast(0.6f) },
    )
}

/**
 * A soft light round a capsule at [topLeft] of [size]: bands from the outside in, each a
 * little stronger, so it fades out over [radius] (a blur that works on every Android version).
 */
private fun DrawScope.capsuleGlow(topLeft: Offset, size: Size, color: Color, strength: Float, radius: Float) {
    if (strength <= 0f || radius <= 0f) return
    val steps = 10
    val step = radius / steps
    var outer = 0f
    for (i in steps downTo 1) {
        val spread = i * step
        val t = 1f - (i - 0.5f) / steps
        // What this band must add for the light to read `strength · t²` here.
        val target = (strength * t * t).coerceIn(0f, 0.99f)
        val alpha = (1f - (1f - target) / (1f - outer)).coerceIn(0f, 1f)
        outer = target
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(topLeft.x - spread, topLeft.y - spread),
            size = Size(size.width + spread * 2, size.height + spread * 2),
            cornerRadius = CornerRadius(size.height / 2 + spread, size.height / 2 + spread),
        )
    }
}

private fun mix(from: Float, to: Float, t: Float): Float = from + (to - from) * t

private val KnobRed = listOf(Color(0xFFEF4444), Color(0xFFDC2626), Color(0xFFC81E1E))
private val KnobGreen = listOf(Color(0xFF1AA84E), Color(0xFF16A34A), Color(0xFF15913F))
private val KnobBlue = Color(0xFF1A66FF)

private val HEIGHT = 76.dp
private val INSET = 6.dp
private val LEAN = 18.dp

/** A drag's direction is decided once it moved this far. */
private val DECIDE_AT = 16.dp
