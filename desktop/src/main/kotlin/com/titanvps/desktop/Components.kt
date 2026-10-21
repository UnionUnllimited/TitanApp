package com.titanvps.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/* The iPhone app's look on Windows (Components.swift): buttons, switches, rows, toasts. */

/** Digits of equal width: timers, pings, prices. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

// ------------------------------------------------------------------ press feedback

/**
 * A custom button: the hand cursor, shrinks a little while pressed, no ripple. [hovered]
 * and [pressed] are reported for the caller's own highlight.
 */
fun Modifier.press(
    enabled: Boolean = true,
    scale: Float = 0.96f,
    onHover: ((Boolean) -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(hovered) { onHover?.invoke(hovered) }
    val s by animateFloatAsState(if (pressed && enabled) scale else 1f, spring(dampingRatio = 0.7f, stiffness = 700f))
    graphicsLayer { scaleX = s; scaleY = s; alpha = if (pressed && enabled) 0.88f else 1f }
        .hoverable(interaction, enabled)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
}

/** A row in a card: highlighted under the mouse and while pressed, like a list row. */
fun Modifier.rowClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val track = Palette.colors.track
    val bg by animateColorAsState(
        when {
            !enabled -> Color.Transparent
            pressed -> track
            hovered -> track.copy(alpha = 0.6f)
            else -> track.copy(alpha = 0f)
        },
        tween(120),
    )
    background(bg)
        .hoverable(interaction, enabled)
        .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
}

// ------------------------------------------------------------------ buttons

/** The main action on a screen: the brand gradient, white text, a soft glow. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    height: Dp = 52.dp,
) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = height)
            .shadow(if (enabled) 10.dp else 0.dp, shape, ambientColor = Brand.copy(alpha = 0.35f), spotColor = Brand.copy(alpha = 0.45f))
            .clip(shape)
            .background(Palette.accent)
            .drawWithContent { drawContent(); if (hovered && enabled) drawRect(Color.White.copy(alpha = 0.08f)) }
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .press(enabled && !busy, 0.97f, onHover = { hovered = it }, onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (busy) {
                Spinner(18.dp, Color.White)
                busyText?.let { Spacer(Modifier.width(10.dp)); Text(it, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            } else {
                icon?.let { Icon(it, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
                Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A second action next to the main one: tinted background, coloured text. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color = Brand,
    height: Dp = 46.dp,
) {
    var hovered by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = height).clip(shape)
            .background(tint.copy(alpha = if (hovered && enabled) 0.18f else 0.12f))
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .press(enabled, 0.97f, onHover = { hovered = it }, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = tint); Spacer(Modifier.width(8.dp)) }
        Text(text, color = tint, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A round icon button (refresh, close…) tinted in the brand colour. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    spinning: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 40.dp,
    tint: Color = Brand,
) {
    var hovered by remember { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(CircleShape)
            .background(tint.copy(alpha = if (hovered && enabled) 0.2f else 0.12f))
            .press(enabled && !spinning, 0.9f, onHover = { hovered = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SpinningIcon(icon, description, spinning, Modifier.size(size * 0.45f), tint)
    }
}

/** An icon that keeps turning while something loads. */
@Composable
fun SpinningIcon(icon: ImageVector, description: String?, spinning: Boolean, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    if (spinning) {
        val turn by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
        Icon(icon, description, modifier.rotate(turn), tint = tint)
    } else {
        Icon(icon, description, modifier, tint = tint)
    }
}

@Composable
fun Spinner(size: Dp = 18.dp, color: Color = Brand, stroke: Dp = 2.dp) {
    CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = stroke, trackColor = Color.Transparent)
}

/** One line that shrinks (down to [minSize]) instead of being cut, as minimumScaleFactor on iPhone. */
@Composable
fun FitText(text: String, color: Color, maxSize: TextUnit, minSize: TextUnit, fontWeight: FontWeight, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text, modifier, style = TextStyle(color = color, fontWeight = fontWeight, fontSize = maxSize),
        maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = minSize, maxFontSize = maxSize, stepSize = 0.5.sp),
    )
}

// ------------------------------------------------------------------ switch

/** Автоподключение and other switches: a green capsule with a mark on the knob. */
@Composable
fun TitanSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = Palette.colors
    val x by animateDpAsState(if (checked) 25.dp else 3.dp, spring(dampingRatio = 0.75f, stiffness = 600f))
    Box(
        Modifier.width(54.dp).height(32.dp).clip(CircleShape)
            .background(if (checked) Palette.green else SolidColor(c.outline))
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .press(enabled, 0.94f) { onChange(!checked) },
    ) {
        Box(
            Modifier.offset(x = x, y = 3.dp).size(26.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (checked) Icons.Default.Check else Icons.Default.Close, null, Modifier.size(13.dp),
                tint = if (checked) Color(0xFF15803D) else Color.Gray,
            )
        }
    }
}

// ------------------------------------------------------------------ cards and rows

/** A white (dark: slate) rounded card; clipped, so a hovered row keeps the corners. */
@Composable
fun TitanCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Palette.colors.surface),
        content = content,
    )
}

/** A white symbol on a coloured rounded square, as in iOS Settings. */
@Composable
fun IconTile(icon: ImageVector, color: Color, size: Dp = 32.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), color))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(size * 0.55f), tint = Color.White)
    }
}

@Composable
fun SettingsRow(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    chevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.rowClick(onClick = onClick) else Modifier)
            .heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, color)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor)
            subtitle?.let { Text(it, fontSize = 12.sp, color = Palette.colors.secondary, lineHeight = 16.sp) }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = Palette.colors.tertiary)
        }
    }
}

/** A switch row: icon tile, title and what it does, the switch on the right. */
@Composable
fun ToggleRow(icon: ImageVector, color: Color, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(icon, color, title, subtitle, onClick = { onChange(!checked) }) {
        TitanSwitch(checked, onChange)
    }
}

/** "ОФОРМЛЕНИЕ" above a card. */
@Composable
fun SectionTitle(text: String) {
    Text(
        text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
        color = Palette.colors.secondary, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp),
    )
}

/** The divider between rows of a card, under the text (not the icon). */
@Composable
fun RowDivider(inset: Dp = 62.dp) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Palette.colors.outline.copy(alpha = 0.6f))
}

/** "● Подписка активна" in a tinted capsule. */
@Composable
fun StatusPill(text: String, color: Color, onColor: Boolean = false) {
    Row(
        Modifier.clip(CircleShape).background(if (onColor) Color.White.copy(alpha = 0.2f) else color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (onColor) Color.White else color))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (onColor) Color.White else color)
    }
}

// ------------------------------------------------------------------ progress

/** Траффик and such: a rounded bar with a gradient. */
@Composable
fun GradientBar(value: Float, brush: Brush = Palette.accent, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(value.coerceIn(0f, 1f), tween(400))
    BoxWithConstraints(modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Palette.colors.track)) {
        if (v > 0f) Box(Modifier.width((maxWidth * v).coerceAtLeast(8.dp)).height(8.dp).clip(CircleShape).background(brush))
    }
}

/** How much of a ping run is done, as a ring. */
@Composable
fun ProgressRing(progress: Float, color: Color = Color.White, size: Dp = 17.dp) {
    val p by animateFloatAsState(progress.coerceIn(0.04f, 1f), tween(300))
    Canvas(Modifier.size(size)) {
        val w = 2.5.dp.toPx()
        drawCircle(color.copy(alpha = 0.3f), radius = (this.size.minDimension - w) / 2, style = Stroke(w))
        drawArc(color, -90f, 360f * p, false, topLeft = Offset(w / 2, w / 2),
            size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** A light band running across the content (a shine on something busy). */
fun Modifier.shimmer(active: Boolean, tint: Color = Color.White, durationMs: Int = 1600): Modifier = composed {
    if (!active) return@composed this
    val t by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(durationMs, easing = LinearEasing)))
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = size.width
            val x = (t * 1.9f - 0.45f) * w
            drawRect(
                Brush.horizontalGradient(listOf(tint.copy(alpha = 0f), tint.copy(alpha = 0.55f * tint.alpha), tint.copy(alpha = 0f)), startX = x, endX = x + w * 0.45f),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** A short shake left and right each time [trigger] grows (a refused click). */
fun Modifier.shake(trigger: Int): Modifier = composed {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger > 0) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(400, easing = LinearEasing))
        }
    }
    graphicsLayer { translationX = (7.dp.toPx() * sin(anim.value * PI * 4)).toFloat() }
}

// ------------------------------------------------------------------ toast

/** A short note at the top of the window that goes away by itself. */
data class Toast(val text: String, val kind: Kind = Kind.INFO, val id: Long = System.nanoTime()) {
    enum class Kind { SUCCESS, INFO, WARNING }
}

@Composable
fun ToastHost(toast: Toast?, onDismiss: (Long) -> Unit, modifier: Modifier = Modifier) {
    // The last one stays while it slides away.
    var shown by remember { mutableStateOf(toast) }
    if (toast != null) shown = toast
    LaunchedEffect(toast?.id) {
        val t = toast ?: return@LaunchedEffect
        delay(if (t.kind == Toast.Kind.WARNING) 4500 else 2800)
        onDismiss(t.id)
    }
    AnimatedVisibility(
        toast != null, modifier,
        enter = slideInVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        shown?.let { t -> ToastView(t) { onDismiss(t.id) } }
    }
}

@Composable
private fun ToastView(toast: Toast, onClick: () -> Unit) {
    val c = Palette.colors
    val (icon, color) = when (toast.kind) {
        Toast.Kind.SUCCESS -> Icons.Default.CheckCircle to c.green
        Toast.Kind.INFO -> Icons.Default.Info to Brand
        Toast.Kind.WARNING -> Icons.Default.Warning to Orange
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.padding(top = 10.dp, start = 20.dp, end = 20.dp).widthIn(max = 560.dp)
            .shadow(16.dp, shape, ambientColor = Color.Black.copy(alpha = 0.25f), spotColor = Color.Black.copy(alpha = 0.3f))
            .clip(shape).background(c.surface).border(1.dp, color.copy(alpha = 0.35f), shape)
            .press(scale = 0.98f, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(toast.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
    }
}

// ------------------------------------------------------------------ backdrops

/** Slowly drifting light in the logo's colours (key entry). */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier) {
    val c = Palette.colors
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { time.floatValue = (it - start) / 1e9f }
            delay(33)
        }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = time.floatValue + 40f
        val w = size.width
        val h = size.height
        val s = minOf(w, h)
        val strength = if (c.dark) 0.42f else 0.26f
        drawRect(c.background)
        fun blob(color: Color, diameter: Float, dx: Float, dy: Float) {
            val center = Offset(w / 2 + dx, h / 2 + dy)
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = strength), color.copy(alpha = 0f)), center, diameter / 2),
                diameter / 2, center,
            )
        }
        blob(Cyan, s * 1.1f, w * (0.08f * sin(t / 5) - 0.28f), h * (0.05f * cos(t / 6) - 0.3f))
        blob(Brand, s * 1.25f, w * (0.07f * cos(t / 7) + 0.3f), h * (0.06f * sin(t / 4.5f) - 0.04f))
        blob(Violet, s * 1.15f, w * (0.09f * sin(t / 6.5f) - 0.12f), h * (0.05f * cos(t / 5.5f) + 0.34f))
    }
}

/** The logo with a soft glow behind it that slowly breathes. */
@Composable
fun GlowingLogo(size: Dp) {
    val breath by rememberInfiniteTransition().animateFloat(
        -1f, 1f, infiniteRepeatable(tween(2200, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
    )
    Box(Modifier.size(size * 1.6f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2 * (1 + 0.06f * breath)
            drawCircle(
                Brush.radialGradient(
                    0f to Brand.copy(alpha = 0.5f), 0.55f to Violet.copy(alpha = 0.22f), 1f to Color.Transparent,
                    center = center, radius = r,
                ),
                r, alpha = 0.85f + 0.15f * breath,
            )
        }
        Image(painterResource("logo.png"), null, Modifier.size(size))
    }
}

/** "Titan VPS" in the logo's gradient. */
@Composable
fun LogoTitle(fontSize: TextUnit, modifier: Modifier = Modifier) {
    Text("Titan VPS", modifier, style = TextStyle(brush = Palette.logo, fontSize = fontSize, fontWeight = FontWeight.ExtraBold))
}

// ------------------------------------------------------------------ tabs

/** Two or three tabs in a capsule; the picked one slides under its title. */
@Composable
fun SegmentedTabs(items: List<Pair<String, ImageVector>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = Palette.colors
    BoxWithConstraints(
        modifier.fillMaxWidth().clip(CircleShape).background(c.surface)
            .border(1.dp, c.outline.copy(alpha = 0.6f), CircleShape).padding(4.dp),
    ) {
        val w = maxWidth / items.size
        val x by animateDpAsState(w * selected, spring(dampingRatio = 0.82f, stiffness = 500f))
        Box(
            Modifier.offset(x = x).width(w).height(42.dp)
                .shadow(6.dp, CircleShape, ambientColor = Brand.copy(alpha = 0.35f), spotColor = Brand.copy(alpha = 0.4f))
                .clip(CircleShape).background(Palette.accent),
        )
        Row {
            items.forEachIndexed { i, (title, icon) ->
                val on = i == selected
                val color by animateColorAsState(if (on) Color.White else c.secondary)
                Row(
                    Modifier.weight(1f).height(42.dp).clip(CircleShape).press(scale = 0.97f) { if (!on) onSelect(i) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, Modifier.size(18.dp), tint = color)
                    Spacer(Modifier.width(7.dp))
                    Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ dialogs

/**
 * Our own dialog instead of the stock one: a rounded card over a dim backdrop, an icon
 * tile, the title, the text, and the buttons (the main one in the gradient).
 */
@Composable
fun TitanDialog(
    onDismiss: () -> Unit,
    title: String,
    text: String? = null,
    icon: ImageVector? = null,
    iconColor: Color = Brand,
    width: Dp = 420.dp,
    dismissible: Boolean = true,
    /** Drawn above the title instead of the icon tile (О приложении: the logo). */
    header: (@Composable ColumnScope.() -> Unit)? = null,
    buttons: (@Composable ColumnScope.() -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = dismissible, usePlatformDefaultWidth = false),
    ) {
        val c = Palette.colors
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.padding(24.dp).widthIn(max = width).fillMaxWidth()
                .shadow(24.dp, shape, ambientColor = Color.Black.copy(alpha = 0.3f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape).background(c.surface).border(1.dp, c.outline.copy(alpha = 0.6f), shape)
                .verticalScroll(rememberScrollState()).padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CompositionLocalProvider(LocalContentColor provides c.text) {
                header?.invoke(this)
                if (header == null) icon?.let {
                    IconTile(it, iconColor, 52.dp)
                    Spacer(Modifier.height(14.dp))
                }
                Text(title, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                text?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center, lineHeight = 19.sp)
                }
                content?.let {
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.fillMaxWidth(), content = it)
                }
                buttons?.let {
                    Spacer(Modifier.height(20.dp))
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = it)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ servers

/** The flag image in a circle (Windows can't draw flag emoji); АВТО gets a bolt. */
@Composable
fun FlagCircle(server: Server, size: Dp) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    FlagCircle(ServerGroups.countryCode(flag), "авто" in name.lowercase(), size)
}

@Composable
fun FlagCircle(code: String?, auto: Boolean, size: Dp) {
    val flagRes = code?.lowercase()?.let { "flags/$it.svg" }
        ?.takeIf { Thread.currentThread().contextClassLoader.getResource(it) != null }
    Box(Modifier.size(size).clip(CircleShape).background(Palette.colors.track), contentAlignment = Alignment.Center) {
        when {
            flagRes != null -> Image(painterResource(flagRes), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            code != null -> Text(code.uppercase(), fontSize = (size.value * 0.32f).sp, fontWeight = FontWeight.Bold, color = Brand)
            auto -> Icon(Icons.Default.Bolt, null, Modifier.size(size * 0.55f), tint = Brand)
            else -> Icon(Icons.Outlined.Dns, null, Modifier.size(size * 0.5f), tint = Palette.colors.secondary)
        }
    }
}

// ------------------------------------------------------------------ formatting

object Fmt {
    private val ru = Locale("ru")

    /** "2.4 МБ/с". */
    fun speed(bytesPerSecond: Double): String {
        val b = bytesPerSecond.coerceAtLeast(0.0)
        return when {
            b >= 1_048_576 -> "%.1f МБ/с".format(b / 1_048_576)
            b >= 1024 -> "%.0f КБ/с".format(b / 1024)
            else -> "%.0f Б/с".format(b)
        }
    }

    /** "13 июля 2027"; "Бессрочно" for no expiry. */
    fun date(unix: Long): String =
        if (unix <= 0) "Бессрочно" else SimpleDateFormat("d MMMM yyyy", ru).format(Date(unix * 1000))

    /** 1 день, 2 дня, 5 дней. */
    fun plural(n: Long, one: String, few: String, many: String): String = "$n " + when {
        n % 100 in 11..14 -> many
        n % 10 == 1L -> one
        n % 10 in 2..4 -> few
        else -> many
    }

    /** "только что", "5 минут назад". */
    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val min = (now - millis).coerceAtLeast(0) / 60_000
        return when {
            min < 1 -> "только что"
            min < 60 -> plural(min, "минуту", "минуты", "минут") + " назад"
            min < 60 * 24 -> plural(min / 60, "час", "часа", "часов") + " назад"
            else -> plural(min / 60 / 24, "день", "дня", "дней") + " назад"
        }
    }

    /** "Финляндия" from "FI". */
    fun country(code: String): String =
        Locale("", code.uppercase()).getDisplayCountry(ru).takeIf { it.isNotBlank() && !it.equals(code, true) } ?: code.uppercase()
}
