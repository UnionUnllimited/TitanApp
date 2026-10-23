package com.titanvps.app.ui.kit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.iosSpring
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ToastKind { SUCCESS, INFO, WARNING }

/** [id] is also when it was shown (System.nanoTime, unique). */
data class ToastData(val text: String, val kind: ToastKind, val id: Long)

/**
 * Short notes at the top of the screen that go away by themselves (iOS Toast). Shown from
 * anywhere, any thread; a newer one replaces the one on screen. [ToastHost] (in TitanTheme)
 * shows them while a screen is open; one older than a few seconds by then is dropped, so a
 * note from the background doesn't turn up when the app is opened later.
 */
object Toasts {
    private val state = MutableStateFlow<ToastData?>(null)
    private val lastId = AtomicLong()

    val current: StateFlow<ToastData?> = state.asStateFlow()

    fun show(text: String, kind: ToastKind = ToastKind.INFO) {
        val id = lastId.updateAndGet { maxOf(it + 1, System.nanoTime()) }
        state.value = ToastData(text, kind, id)
    }

    fun dismiss(id: Long) {
        state.update { if (it?.id == id) null else it }
    }

    /** Shown while no screen was open (a service, a widget): too late to show now. */
    internal fun isStale(toast: ToastData): Boolean = System.nanoTime() - toast.id > STALE_NS

    private const val STALE_NS = 5_000_000_000L
}

/** The current toast over the screen; doesn't take touches outside the toast itself. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val toast by Toasts.current.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    // Only a screen in front shows it (Main under Личный кабинет stays quiet).
    val active = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val haptics = rememberHaptics()
    // Kept while it slides out.
    var shown by remember { mutableStateOf<ToastData?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(toast, active) {
        val t = toast
        if (t == null || !active) {
            visible = false
            return@LaunchedEffect
        }
        if (Toasts.isStale(t)) {
            Toasts.dismiss(t.id)
            return@LaunchedEffect
        }
        if (t.id != shown?.id) {
            when (t.kind) {
                ToastKind.SUCCESS -> haptics.success()
                ToastKind.WARNING -> haptics.warning()
                ToastKind.INFO -> Unit
            }
        }
        shown = t
        visible = true
        delay(if (t.kind == ToastKind.WARNING) 4000L else 2600L)
        Toasts.dismiss(t.id)
    }

    Box(modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(iosSpring(0.4f, 0.85f)) { -it } + fadeIn(tween(200)),
            exit = slideOutVertically(iosSpring(0.4f, 0.85f)) { -it } + fadeOut(tween(200)),
        ) {
            shown?.let { t -> ToastBubble(t) { Toasts.dismiss(t.id) } }
        }
    }
}

@Composable
private fun ToastBubble(toast: ToastData, onTap: () -> Unit) {
    val palette = Titan.palette
    val (icon, color) = when (toast.kind) {
        ToastKind.SUCCESS -> Icons.Filled.CheckCircle to palette.green
        ToastKind.INFO -> Icons.Filled.Info to palette.primary
        ToastKind.WARNING -> Icons.Filled.Warning to palette.orange
    }
    val shape = RoundedCornerShape(20.dp)
    val shadow = Color.Black.copy(alpha = 0.18f)
    Row(
        Modifier
            .padding(start = 20.dp, end = 20.dp, top = 6.dp)
            .widthIn(max = 560.dp)
            .shadow(16.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.92f))
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Скрыть",
                onClick = onTap,
            )
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(18.dp), tint = color)
        Text(toast.text, color = MaterialTheme.colorScheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
