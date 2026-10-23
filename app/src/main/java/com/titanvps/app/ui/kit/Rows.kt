package com.titanvps.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.ui.theme.Titan

/** A card on the background: rows inside keep its rounded corners when pressed. */
@Composable
fun TitanCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    stroke: Color = Color.Unspecified,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(Titan.palette.surface)
                .then(if (stroke.isSpecified) Modifier.border(1.dp, stroke, shape) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** A white symbol on a coloured rounded square, as in iOS Settings. */
@Composable
fun IconTile(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), color))),
        contentAlignment = Alignment.Center,
    ) {
        // 0.56 of the tile: a Material icon's mark is ~0.8 of its box (iOS: a 0.47 symbol).
        Icon(icon, null, Modifier.size(size * 0.56f), tint = Color.White)
    }
}

/** "ОФОРМЛЕНИЕ" above a card. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier.padding(start = 6.dp, top = 14.dp, bottom = 6.dp).semantics { heading() },
        color = Titan.palette.textSecondary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    )
}

/** The divider between rows of a card, under the text (not the icon). */
@Composable
fun RowDivider(modifier: Modifier = Modifier, inset: Dp = 62.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = inset)
            .height(0.5.dp)
            .background(Titan.palette.outline.copy(alpha = 0.7f)),
    )
}

/**
 * A row of a settings card: icon tile, title and subtitle, [trailing] content, a chevron.
 * [onClick] null: not pressable (a value or a toggle of its own on the right).
 */
@Composable
fun SettingsRow(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleColor: Color = Color.Unspecified,
    showChevron: Boolean = onClick != null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val click = if (onClick != null) {
        Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    SettingsRowLayout(
        modifier.then(click).background(pressedRowColor(interaction)),
        vertical = 10.dp,
        title = title,
        icon = icon,
        iconColor = iconColor,
        subtitle = subtitle,
        titleColor = titleColor,
    ) {
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (showChevron) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                Modifier.size(18.dp),
                tint = Titan.palette.textTertiary,
            )
        }
    }
}

/** A settings row with a switch: the whole row toggles it. */
@Composable
fun SettingsToggleRow(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val haptics = rememberHaptics()
    val interaction = remember { MutableInteractionSource() }
    SettingsRowLayout(
        modifier
            .toggleable(
                value = checked,
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Switch,
            ) {
                haptics.toggle(it)
                onCheckedChange(it)
            }
            .background(pressedRowColor(interaction))
            .alpha(if (enabled) 1f else 0.5f),
        vertical = 12.dp,
        title = title,
        icon = icon,
        iconColor = iconColor,
        subtitle = subtitle,
        titleColor = Color.Unspecified,
    ) {
        Spacer(Modifier.width(12.dp))
        TitanToggle(checked, null)
    }
}

/** A pressed row is highlighted, like a list row (no ripple). */
@Composable
private fun pressedRowColor(interaction: MutableInteractionSource): Color {
    val pressed by interaction.collectIsPressedAsState()
    val track = Titan.palette.track
    val color by animateColorAsState(if (pressed) track else track.copy(alpha = 0f), tween(150), label = "rowPress")
    return color
}

@Composable
private fun SettingsRowLayout(
    modifier: Modifier,
    vertical: Dp,
    title: String,
    icon: ImageVector,
    iconColor: Color,
    subtitle: String?,
    titleColor: Color,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 58.dp)
            .padding(horizontal = 16.dp, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon, iconColor)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = titleColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) Text(subtitle, color = Titan.palette.textSecondary, fontSize = 12.sp)
        }
        trailing()
    }
}
