package com.titanvps.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.titanvps.app.core.ConnectionCheck
import com.titanvps.app.core.TrafficMeter
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Fmt
import com.titanvps.app.ui.kit.CountryFlagCircle
import com.titanvps.app.ui.kit.FlagCircle
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp
import com.titanvps.app.ui.theme.iosSpring
import com.titanvps.app.ui.theme.pingColor
import com.titanvps.app.vpn.VpnState
import kotlin.math.max
import kotlinx.coroutines.delay

/** Digits of equal width (timers, speeds, ms that change in place). */
@Composable
@ReadOnlyComposable
internal fun tabularStyle(): TextStyle = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")

/**
 * Under the slider: Не подключено / Подключаемся… / Подключено, the time connected and the
 * speed, the server, and Проверить соединение. Behind it while connected: the last minute of
 * download as a faint curve. [bypassOnWifi]: the chosen server is a bypass one and the phone
 * is on Wi-Fi (shown while off).
 */
@Composable
internal fun StatusBlock(
    state: VpnState,
    serverName: String?,
    ping: Long?,
    bypassOnWifi: Boolean,
    check: ConnectionCheck.State,
    techMode: Boolean,
    onCheck: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Titan.palette
    val connected = state is VpnState.Connected
    val history = remember { SpeedHistory() }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Speed only while connected and Главная is in front; going off starts it over.
    LaunchedEffect(connected, lifecycleOwner) {
        history.clear()
        if (!connected) return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            TrafficMeter.samples().collect { history.add(it) }
        }
    }
    Box(modifier.fillMaxWidth()) {
        if (connected) SpeedBackdrop(history, Modifier.matchParentSize())
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(iosSpring(0.4f, 0.85f))
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusTitle(state)
            if (state is VpnState.Connected) {
                ConnectionTimer(state.since)
                if (history.sampled) SpeedLine(history)
            }
            ServerLine(serverName, ping)
            if (state is VpnState.Error) {
                Text(state.message, color = palette.red, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            if (bypassOnWifi && (state is VpnState.Disconnected || state is VpnState.Error)) {
                Text(
                    "Обходы работают только через мобильный интернет",
                    color = palette.orange,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
            AnimatedVisibility(
                visible = connected,
                enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.9f),
                exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.9f),
            ) {
                ConnectionPill(check, techMode, onCheck, Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun StatusTitle(state: VpnState) {
    val palette = Titan.palette
    val plain = MaterialTheme.colorScheme.onSurface
    val (title, target) = when (state) {
        VpnState.Connecting -> "Подключаемся…" to palette.primary
        is VpnState.Connected -> "Подключено" to palette.green
        VpnState.Disconnecting -> "Отключаемся…" to plain
        else -> "Не подключено" to plain
    }
    val color by animateColorAsState(target, tween(250), label = "statusColor")
    Crossfade(title, animationSpec = tween(250), label = "statusTitle") { text ->
        Text(
            text,
            color = color,
            fontSize = fixedSp(26f),
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 00:12:34 since the connection came up, ticking on the second (only this redraws). */
@Composable
private fun ConnectionTimer(since: Long) {
    val elapsed by produceState((System.currentTimeMillis() - since).coerceAtLeast(0), since) {
        while (true) {
            val ms = (System.currentTimeMillis() - since).coerceAtLeast(0)
            value = ms
            delay(1000 - ms % 1000)
        }
    }
    Text(
        Fmt.timer(elapsed),
        color = Titan.palette.textSecondary,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        style = tabularStyle(),
    )
}

/** The flag, the name and its ping, on one line. */
@Composable
private fun ServerLine(serverName: String?, ping: Long?) {
    val palette = Titan.palette
    Row(
        Modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (serverName != null) {
            FlagCircle(serverName, 20.dp)
            Text(
                Flags.nameWithoutFlag(serverName) + (ping?.takeIf { it >= 0 }?.let { " · $it мс" } ?: ""),
                Modifier.weight(1f, fill = false),
                color = palette.textSecondary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("Выберите сервер", color = palette.textSecondary, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * The last minute of speed. Read only by [SpeedLine] and [SpeedBackdrop]: the second-by-second
 * updates redraw those two, not the screen.
 */
private class SpeedHistory {
    /** Download, bytes a second, oldest first. */
    val down = mutableStateListOf<Float>()
    var downNow by mutableDoubleStateOf(0.0)
    var upNow by mutableDoubleStateOf(0.0)
    /** A sample came: this phone has per-app counters (else the speed stays hidden). */
    var sampled by mutableStateOf(false)
    private var lastAt = 0L

    fun add(sample: TrafficMeter.Sample) {
        // A long gap (Главная was in the background): the curve starts over.
        if (lastAt != 0L && sample.atMillis - lastAt > 3000) down.clear()
        lastAt = sample.atMillis
        downNow = sample.down
        upNow = sample.up
        sampled = true
        down.add(sample.down.toFloat())
        while (down.size > WINDOW) down.removeAt(0)
    }

    fun clear() {
        down.clear()
        downNow = 0.0
        upNow = 0.0
        sampled = false
        lastAt = 0L
    }
}

/** Under the timer: the speed now, quietly. */
@Composable
private fun SpeedLine(history: SpeedHistory) {
    val down = Fmt.speed(history.downNow)
    val up = Fmt.speed(history.upNow)
    Text(
        "↓ $down    ↑ $up",
        Modifier.clearAndSetSemantics { contentDescription = "Загрузка $down, отдача $up" },
        color = Titan.palette.textTertiary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        style = tabularStyle(),
    )
}

/** Behind the status: the last minute of download as a faint filled curve, newest on the right. */
@Composable
private fun SpeedBackdrop(history: SpeedHistory, modifier: Modifier) {
    val color = Titan.palette.primary
    Canvas(modifier.clearAndSetSemantics {}) {
        val values = history.down
        val n = values.size
        if (n < 2) return@Canvas
        val top = max(values.max(), MIN_TOP) * 1.2f
        val slot = size.width / (WINDOW - 1)
        fun pointAt(i: Int) = Offset(size.width - (n - 1 - i) * slot, size.height * (1f - values[i] / top))
        val first = pointAt(0)
        val path = Path()
        path.moveTo(first.x, first.y)
        // Through the midpoints, each point a control: a smooth line without overshoot.
        var at = first
        var prev = first
        for (i in 1 until n) {
            val point = pointAt(i)
            val mid = Offset((prev.x + point.x) / 2, (prev.y + point.y) / 2)
            // A quadratic curve (control: prev) written as a cubic one.
            path.cubicTo(
                at.x + (prev.x - at.x) * 2 / 3, at.y + (prev.y - at.y) * 2 / 3,
                mid.x + (prev.x - mid.x) * 2 / 3, mid.y + (prev.y - mid.y) * 2 / 3,
                mid.x, mid.y,
            )
            at = mid
            prev = point
        }
        path.lineTo(prev.x, prev.y)
        path.lineTo(prev.x, size.height)
        path.lineTo(first.x, size.height)
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(color.copy(alpha = 0.14f), color.copy(alpha = 0f))))
    }
}

/**
 * Проверить соединение (as in Happ): the result of a request through the VPN, tap to repeat.
 * The answer to a check by hand is felt too.
 */
@Composable
private fun ConnectionPill(check: ConnectionCheck.State, techMode: Boolean, onCheck: () -> Unit, modifier: Modifier) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    var byHand by remember { mutableStateOf(false) }
    LaunchedEffect(check) {
        if (!byHand) return@LaunchedEffect
        when (check) {
            is ConnectionCheck.State.Ok -> { haptics.success(); byHand = false }
            is ConnectionCheck.State.Failed -> { haptics.warning(); byHand = false }
            else -> Unit
        }
    }
    val checking = check == ConnectionCheck.State.Checking
    val description = when (check) {
        ConnectionCheck.State.Idle -> "Проверить соединение"
        ConnectionCheck.State.Checking -> "Проверяем соединение"
        is ConnectionCheck.State.Ok -> "Соединение работает, ${check.ms} миллисекунд. Проверить ещё раз"
        is ConnectionCheck.State.Failed -> "Нет ответа через VPN. Проверить ещё раз"
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                // While a check runs a tap does nothing (it looks the same, as on iOS).
                .pressable(onClick = {
                    if (!checking) {
                        byHand = true
                        onCheck()
                    }
                })
                .semantics { contentDescription = description }
                .height(40.dp)
                .clip(CircleShape)
                .background(palette.surface)
                .border(1.dp, palette.outline.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(check, Modifier.clearAndSetSemantics {}, animationSpec = tween(200), label = "check") { state ->
                PillContent(state)
            }
        }
        Crossfade(check, animationSpec = tween(200), label = "checkNote") { state ->
            when (state) {
                is ConnectionCheck.State.Ok -> state.ip?.let { ip ->
                    SelectionContainer {
                        Text("IP: $ip", color = palette.textSecondary, fontSize = 12.sp, style = tabularStyle())
                    }
                }
                is ConnectionCheck.State.Failed -> Text(
                    if (techMode && !state.reason.isNullOrBlank()) "${state.reason}. Попробуйте другой сервер"
                    else "Попробуйте ещё раз или выберите другой сервер",
                    Modifier.padding(horizontal = 16.dp),
                    color = palette.red.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun PillContent(check: ConnectionCheck.State) {
    val palette = Titan.palette
    val text = MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (check) {
            ConnectionCheck.State.Idle -> {
                Icon(Icons.Filled.NetworkCheck, null, Modifier.size(18.dp), tint = palette.primary)
                PillText("Проверить соединение", text)
            }
            ConnectionCheck.State.Checking -> {
                CircularProgressIndicator(Modifier.size(18.dp), color = palette.primary, strokeWidth = 2.dp)
                PillText("Проверяем соединение…", text)
            }
            is ConnectionCheck.State.Ok -> {
                val color = pingColor(check.ms)
                Box(Modifier.size(9.dp).clip(CircleShape).background(color))
                PillText("${check.ms} мс", color, tabular = true)
                check.country?.let { code ->
                    PillText("·", palette.textSecondary)
                    CountryFlagCircle(code, 18.dp)
                    PillText(Fmt.countryName(code), text)
                }
                Icon(Icons.Filled.Refresh, null, Modifier.size(12.dp), tint = palette.textSecondary)
            }
            is ConnectionCheck.State.Failed -> {
                Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = palette.orange)
                PillText("Нет ответа через VPN", text)
                Icon(Icons.Filled.Refresh, null, Modifier.size(12.dp), tint = palette.textSecondary)
            }
        }
    }
}

@Composable
private fun PillText(text: String, color: Color, tabular: Boolean = false) {
    Text(
        text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = if (tabular) tabularStyle() else LocalTextStyle.current,
    )
}

/** Seconds of speed kept (and slots across the curve). */
private const val WINDOW = 60

/** The curve's scale never goes below 64 КБ/с: a quiet minute stays low. */
private const val MIN_TOP = 65536f
