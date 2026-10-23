package com.titanvps.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.Titan

/** The main action on a screen: the brand gradient, white text, a soft glow. Full width. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyText: String? = null,
    minHeight: Dp = 54.dp,
) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(16.dp)
    val glow = palette.primary.copy(alpha = 0.35f)
    Box(
        modifier
            .fillMaxWidth()
            // Busy: looks the same, taps do nothing.
            .pressable(onClick = { if (!busy) onClick() }, enabled = enabled, scale = 0.97f)
            .then(if (enabled) Modifier.shadow(12.dp, shape, clip = false, ambientColor = glow, spotColor = glow) else Modifier)
            .clip(shape)
            .background(AccentGradient)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape)
            .heightIn(min = minHeight)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            } else if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = Color.White)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (busy) busyText ?: text else text,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A second action next to the main one: tinted background, coloured text. Full width. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Color.Unspecified,
    enabled: Boolean = true,
    minHeight: Dp = 50.dp,
) {
    val color = tint.takeOrElse { Titan.palette.primary }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        color.copy(alpha = if (pressed && enabled) 0.2f else 0.12f),
        tween(150),
        label = "secondaryPress",
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, enabled = enabled, scale = 0.97f, interactionSource = interaction)
            .clip(shape)
            .background(background)
            .heightIn(min = minHeight)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(18.dp), tint = color)
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

/** A round icon button (refresh, support…) on a tinted disc; [spinning] while it loads. */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    spinning: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    background: Color = Color.Unspecified,
) {
    val color = tint.takeOrElse { Titan.palette.primary }
    // The disc follows the icon's colour (the primary one by default).
    val disc = background.takeOrElse { color.copy(alpha = 0.12f) }
    Box(
        modifier
            .pressable(onClick = onClick, enabled = enabled, scale = 0.9f)
            .size(size)
            .clip(CircleShape)
            .background(disc),
        contentAlignment = Alignment.Center,
    ) {
        SpinningIcon(icon, spinning, Modifier.size(size * 0.4f), tint = color, contentDescription = contentDescription)
    }
}

/** The square buttons of a screen's header (Поддержка, Тема): a raised surface tile. */
@Composable
fun SquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(14.dp)
    val shadow = Color.Black.copy(alpha = 0.06f)
    Box(
        modifier
            .pressable(onClick = onClick, scale = 0.9f)
            .size(48.dp)
            .shadow(3.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.outline.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(19.dp), tint = palette.primary)
    }
}

/** An icon that keeps turning while something loads: one turn per 0.9 s, then it settles upright. */
@Composable
fun SpinningIcon(
    icon: ImageVector,
    spinning: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    contentDescription: String? = null,
) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) {
                val from = angle.value % 360f
                angle.snapTo(from)
                angle.animateTo(360f, tween(((360f - from) / 360f * TURN_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
                angle.snapTo(0f)
            }
        } else if (angle.value != 0f) {
            // Finish the turn at the same speed instead of jumping back.
            val from = angle.value % 360f
            angle.snapTo(from)
            angle.animateTo(360f, tween(((360f - from) / 360f * TURN_MS).toInt().coerceAtLeast(1), easing = LinearEasing))
            angle.snapTo(0f)
        }
    }
    Icon(
        icon,
        contentDescription,
        modifier.graphicsLayer { rotationZ = angle.value },
        tint = tint.takeOrElse { LocalContentColor.current },
    )
}

private const val TURN_MS = 900f
