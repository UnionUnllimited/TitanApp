package com.titanvps.app.ui.kit

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.titanvps.app.ui.theme.GreenGradient
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.iosSpring

/**
 * Автоподключение and other switches: a green capsule with a mark on the knob (iOS
 * TitanToggleStyle). [onCheckedChange] null: only drawn, the row around it toggles.
 */
@Composable
fun TitanToggle(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val knob by animateDpAsState(if (checked) KNOB_TRAVEL else 0.dp, iosSpring(0.3f, 0.75f), label = "toggleKnob")
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(200), label = "toggleFill")
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            role = Role.Switch,
        ) {
            haptics.toggle(it)
            onCheckedChange(it)
        }
    } else {
        Modifier
    }
    Box(
        modifier
            .then(toggle)
            .alpha(if (enabled) 1f else 0.5f)
            .size(54.dp, 32.dp)
            .background(palette.outline, CircleShape)
            .background(GreenGradient, CircleShape, alpha = on)
            .padding(3.dp),
    ) {
        val shadow = Color.Black.copy(alpha = 0.2f)
        Box(
            Modifier
                .offset(x = knob)
                .size(26.dp)
                .shadow(3.dp, CircleShape, clip = false, ambientColor = shadow, spotColor = shadow)
                .background(Color.White, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // A little larger than iOS's 10 pt: Material icons have a margin round the mark.
            Icon(
                if (checked) Icons.Filled.Check else Icons.Filled.Close,
                null,
                Modifier.size(12.dp),
                tint = if (checked) palette.green else palette.gray,
            )
        }
    }
}

/** 54 wide, 3 padding each side, 26 knob. */
private val KNOB_TRAVEL = 22.dp
