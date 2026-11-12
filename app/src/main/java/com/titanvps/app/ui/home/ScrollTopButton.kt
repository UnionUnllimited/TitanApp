package com.titanvps.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.theme.Titan

/**
 * When the «↑» over the server list shows: from about a screen down, gone again above half of
 * one (so at the top), not flickering in between. Plain numbers, so it is tested on its own.
 */
internal object ScrollTop {
    const val SHOW_AT = 1f
    const val HIDE_BELOW = 0.5f

    /**
     * How far the list is scrolled, px: the items above the screen taken as tall as the ones on
     * it ([averageItem], with the gap between them). Exact at the top.
     */
    fun scrolled(firstIndex: Int, firstOffset: Int, averageItem: Float): Float = firstIndex * averageItem + firstOffset

    /** [shown]: whether it shows now; [viewport]: the list's height, px. */
    fun visible(shown: Boolean, scrolled: Float, viewport: Int): Boolean {
        if (viewport <= 0) return false
        return if (shown) scrolled > viewport * HIDE_BELOW else scrolled >= viewport * SHOW_AT
    }
}

/** Whether the «↑» shows for [list], following its scroll. */
@Composable
internal fun rememberScrollTopVisible(list: LazyListState): Boolean {
    var shown by remember(list) { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow {
            val info = list.layoutInfo
            val items = info.visibleItemsInfo
            val average = if (items.isEmpty()) 0f else items.sumOf { it.size }.toFloat() / items.size + info.mainAxisItemSpacing
            ScrollTop.scrolled(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, average) to info.viewportSize.height
        }.collect { (scrolled, viewport) -> shown = ScrollTop.visible(shown, scrolled, viewport) }
    }
    return shown
}

/** Room the list leaves under its last row for the «↑», so it covers none of the row's controls. */
internal val ScrollTopRoom = 60.dp

/** The round «↑» over the list (Наверх): fades in and out, the theme's card colours. */
@Composable
internal fun ScrollTopButton(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = Titan.palette
    val shadow = Color.Black.copy(alpha = 0.12f)
    AnimatedVisibility(visible, modifier, enter = fadeIn(tween(200)), exit = fadeOut(tween(200)), label = "scrollTop") {
        // 44 drawn, a 48 dp touch.
        Box(
            Modifier
                .pressable(onClick = onClick, scale = 0.9f)
                .semantics { contentDescription = "Наверх" }
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(44.dp)
                    .shadow(6.dp, CircleShape, clip = false, ambientColor = shadow, spotColor = shadow)
                    .clip(CircleShape)
                    .background(palette.surface)
                    .border(1.dp, palette.outline.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.ArrowUpward, null, Modifier.size(20.dp), tint = palette.primary)
            }
        }
    }
}
