package com.titanvps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.ui.kit.TitanToggle
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.Titan

/** Rounded card used by all screens (surface on the background, as in the mockups). */
@Composable
internal fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(shape = RoundedCornerShape(20.dp), color = Titan.palette.surface, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (title != null) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
            }
            content()
        }
    }
}

/** Row with optional icon, value on the right and a chevron. */
@Composable
internal fun NavRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    titleColor: Color = Color.Unspecified,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(14.dp))
            }
            Text(title, Modifier.weight(1f), color = titleColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (value != null) {
                Text(
                    value,
                    Modifier.padding(start = 8.dp).weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = Titan.palette.textTertiary)
        }
        if (divider) HorizontalDivider(thickness = 0.5.dp, color = Titan.palette.outline.copy(alpha = 0.7f))
    }
}

/** A setting with a switch: the whole row toggles it. */
@Composable
internal fun ToggleRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .toggleable(value = checked, role = Role.Switch) {
                    haptics.toggle(it)
                    onChange(it)
                }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            TitanToggle(checked = checked, onCheckedChange = null)
        }
        if (divider) HorizontalDivider(thickness = 0.5.dp, color = Titan.palette.outline.copy(alpha = 0.7f))
    }
}

/** Small tinted banner with an info icon. */
@Composable
internal fun InfoBanner(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * A screen's bar, as the iPhone's inline one (and the cabinet's): the title centred in 17
 * semibold, a round «‹» ([onBack]: a screen opened from another) or «✕» ([onClose]: one that
 * stands on its own, as iOS's sheets) on the left, [action] (a round button) on the right.
 * The title keeps clear of both sides' buttons and ends in «…» when it doesn't fit.
 */
@Composable
internal fun TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    action: @Composable () -> Unit = {},
) {
    val palette = Titan.palette
    Layout(
        content = {
            Box {
                when {
                    onClose != null -> BarCircle(Icons.Filled.Close, "Закрыть", palette.textSecondary, palette.track, 13.dp, onClose)
                    onBack != null -> BarCircle(TitanIcons.Back, "Назад", palette.primary, palette.primary.copy(alpha = 0.12f), 16.dp, onBack)
                }
            }
            Text(
                title,
                Modifier.semantics { heading() },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box { action() }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = measurables[0].measure(loose)
        val end = measurables[2].measure(loose)
        // Centred on the bar: as much room on each side as the wider button takes.
        val side = maxOf(start.width, end.width)
        val gap = if (side > 0) 8.dp.roundToPx() else 0
        val text = measurables[1].measure(loose.copy(maxWidth = (constraints.maxWidth - 2 * (side + gap)).coerceAtLeast(0)))
        val width = constraints.maxWidth
        val height = maxOf(constraints.minHeight, start.height, end.height, text.height)
        layout(width, height) {
            start.placeRelative(0, (height - start.height) / 2)
            text.placeRelative((width - text.width) / 2, (height - text.height) / 2)
            end.placeRelative(width - end.width, (height - end.height) / 2)
        }
    }
}

/** A round button of [TopBar]: a glyph on a disc, 32 dp as the cabinet's. */
@Composable
private fun BarCircle(icon: ImageVector, contentDescription: String, tint: Color, disc: Color, glyph: Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .pressable(onClick = onClick, scale = 0.9f)
            .size(32.dp)
            .background(disc, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, Modifier.size(glyph), tint = tint)
    }
}
