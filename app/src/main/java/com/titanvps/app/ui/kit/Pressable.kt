package com.titanvps.app.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import com.titanvps.app.ui.theme.iosSpring

/**
 * Any custom button: shrinks a little and dims while pressed, no ripple (iOS PressableStyle).
 * Disabled: half transparent, no clicks. [interactionSource]: to read the press elsewhere.
 */
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    scale: Float = 0.96f,
    onLongClick: (() -> Unit)? = null,
    role: Role? = Role.Button,
    onClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val down = pressed && enabled
    val shrink by animateFloatAsState(
        targetValue = if (down) scale else 1f,
        animationSpec = iosSpring(0.25f, 0.7f),
        label = "pressScale",
    )
    val fade by animateFloatAsState(
        targetValue = if (!enabled) 0.5f else if (down) 0.88f else 1f,
        animationSpec = iosSpring(0.25f, 0.7f),
        label = "pressAlpha",
    )
    this
        .graphicsLayer {
            scaleX = shrink
            scaleY = shrink
            alpha = fade.coerceIn(0f, 1f)
        }
        .combinedClickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onLongClick = onLongClick,
            onClick = onClick,
        )
}
