package com.titanvps.app.ui.kit

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.iosSpring
import kotlin.math.roundToInt

data class SegmentItem(val title: String, val icon: ImageVector? = null)

/**
 * Two or three tabs; the picked one slides under its title (not a stock picker).
 * [tiles] false: a capsule of tabs (Личный кабинет). True: tiles with the icon above the
 * title in a card (Системная / Светлая / Тёмная).
 */
@Composable
fun SegmentedTabs(
    items: List<SegmentItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    tiles: Boolean = false,
) {
    if (items.isEmpty()) return
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val count = items.size
    val picked = selectedIndex.coerceIn(0, count - 1)
    val spacing = if (tiles) 6.dp else 4.dp
    val shape: Shape = if (tiles) RoundedCornerShape(14.dp) else CircleShape
    // The picked one's place as a fractional index: drawn where the tabs actually are.
    val position by animateFloatAsState(
        picked.toFloat(),
        if (tiles) iosSpring(0.35f, 0.8f) else iosSpring(0.35f, 0.82f),
        label = "segment",
    )
    // Not clipped: the picked one's glow may spill over the edge.
    val container = if (tiles) {
        Modifier
            .background(palette.surface, RoundedCornerShape(20.dp))
            .padding(6.dp)
    } else {
        Modifier
            .background(palette.surface, CircleShape)
            .border(1.dp, palette.outline.copy(alpha = 0.6f), CircleShape)
            .padding(4.dp)
    }
    Box(modifier.fillMaxWidth().then(container)) {
        SegmentIndicator(count, { position }, spacing, shape, tiles, Modifier.matchParentSize())
        Row(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items.forEachIndexed { index, item ->
                val on = index == picked
                val color by animateColorAsState(
                    if (on) Color.White else palette.textSecondary,
                    tween(200),
                    label = "segmentText",
                )
                val select = {
                    if (index != picked) {
                        haptics.selection()
                        onSelect(index)
                    }
                }
                Box(
                    Modifier
                        .weight(1f)
                        .pressable(onClick = select, scale = if (tiles) 0.95f else 1f, role = Role.Tab)
                        .semantics { selected = on }
                        .heightIn(min = if (tiles) 64.dp else 42.dp)
                        .padding(horizontal = 6.dp, vertical = if (tiles) 8.dp else 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (tiles) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (item.icon != null) {
                                Icon(item.icon, null, Modifier.size(18.dp), tint = color)
                                Spacer(Modifier.height(6.dp))
                            }
                            SegmentTitle(item.title, color, 13)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.icon != null) {
                                Icon(item.icon, null, Modifier.size(16.dp), tint = color)
                                Spacer(Modifier.width(6.dp))
                            }
                            SegmentTitle(item.title, color, 15)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentTitle(text: String, color: Color, size: Int) {
    Text(
        text,
        color = color,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The gradient under the picked tab, as wide as one tab, at the fractional [position]. */
@Composable
private fun SegmentIndicator(count: Int, position: () -> Float, spacing: Dp, shape: Shape, tiles: Boolean, modifier: Modifier) {
    val glow = Titan.palette.primary.copy(alpha = 0.35f)
    Box(
        modifier
            .layout { measurable, constraints ->
                val gap = spacing.roundToPx()
                val width = ((constraints.maxWidth - gap * (count - 1)) / count).coerceAtLeast(0)
                val placeable = measurable.measure(Constraints.fixed(width, constraints.maxHeight))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // Read here: the slide only moves the indicator, nothing is recomposed.
                    placeable.placeRelative(((width + gap) * position()).roundToInt(), 0)
                }
            }
            .shadow(if (tiles) 4.dp else 8.dp, shape, clip = false, ambientColor = glow, spotColor = glow)
            .background(AccentGradient, shape),
    )
}
