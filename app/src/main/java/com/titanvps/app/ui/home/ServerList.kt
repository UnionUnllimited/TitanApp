package com.titanvps.app.ui.home

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Fmt
import com.titanvps.app.data.NetworkMonitor
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerGroups.Group
import com.titanvps.app.data.SubscriptionInfo
import com.titanvps.app.ui.kit.FlagCircle
import com.titanvps.app.ui.kit.ProgressRing
import com.titanvps.app.ui.kit.ToastKind
import com.titanvps.app.ui.kit.Toasts
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.pingColor

/**
 * Страны | Обходы (only those that have servers) and the Пинг button. [progress]: how much of
 * a running Пинг is done, read only by the button. Each tab says where its servers work: after
 * the count when the window has room, under the title otherwise (phones, Пинг's icon above
 * the word there, so the tabs get more room).
 */
@Composable
internal fun ServerTabs(
    counts: List<Pair<Group, Int>>,
    page: Group,
    onPage: (Group) -> Unit,
    pinging: Boolean,
    progress: () -> Float,
    onPing: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Titan.palette
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fit = rememberTabsFit(counts, maxWidth)
        // Пинг as tall as the tabs: two lines make them taller.
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TabSize.gap),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.surface)
                    .padding(TabSize.stripPad),
                horizontalArrangement = Arrangement.spacedBy(TabSize.chipGap),
            ) {
                counts.forEach { (group, count) ->
                    TabChip(group, count, group == page, fit, Modifier.weight(1f).fillMaxHeight()) { onPage(group) }
                }
            }
            PingButton(pinging, progress, onPing, column = !fit.oneLine, Modifier.fillMaxHeight())
        }
    }
}

/** The tabs' sizes: the layout and [rememberTabsFit] use the same ones. */
private object TabSize {
    /** Between the tabs and Пинг. */
    val gap = 8.dp
    val stripPad = 4.dp
    val chipGap = 2.dp
    val widePad = 10.dp
    val narrowPad = 4.dp
    val icon = 16.dp
    val wideSpacing = 6.dp
    val narrowSpacing = 5.dp
    /** Tight: smaller gaps and badge sides, so the icon stays. */
    val tightSpacing = 3.dp
    /** More room before the network on one line. */
    val wideNetworkGap = 4.dp
    val wideGlyph = 13.dp
    val narrowGlyph = 11.dp
    val networkSpacing = 4.dp
    val badgePad = 7.dp
    val tightBadgePad = 5.dp
    val pingPad = 14.dp
    val pingIcon = 18.dp
    val pingSpacing = 7.dp
    /** Phones: Пинг as a column, the icon above the word (as in the mock). */
    val pingColumn = 58.dp
    val pingColumnPad = 6.dp
    val pingColumnSpacing = 3.dp
    /** What the layout may round up. */
    val slack = 2.dp
    val title = 14.sp
    val badge = 12.sp
    val ping = 14.sp
    val pingColumnText = 13.sp
    val pingColumnLine = 16.sp
    val wideNetwork = 12.sp
    val narrowNetwork = 11.sp
    /** Smaller than this the network line is left out (the largest text sizes, a narrow screen). */
    val minNetwork = 9.sp
}

/**
 * How the tabs fit: [oneLine], the network after the count; else under the title (Пинг a
 * column), at [networkSize] (smaller when tight; null: no room even so). A tight title row:
 * first smaller gaps and badge sides ([tight]), only then without the [icon].
 */
@Immutable
private data class TabsFit(val oneLine: Boolean, val tight: Boolean, val icon: Boolean, val networkSize: TextUnit?)

/** Measures the tabs' words at the app's text size: what fits [width], the row with Пинг. */
@Composable
private fun rememberTabsFit(counts: List<Pair<Group, Int>>, width: Dp): TabsFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    return remember(counts, width, density, base, measurer) {
        fun textWidth(text: String, size: TextUnit, weight: FontWeight = FontWeight.Medium): Float =
            measurer.measure(text, base.merge(TextStyle(fontSize = size, fontWeight = weight)), maxLines = 1).size.width.toFloat()
        fun pingWord(size: TextUnit): Float =
            maxOf(textWidth("Пинг", size, FontWeight.SemiBold), textWidth("Стоп", size, FontWeight.SemiBold))
        with(density) {
            val n = counts.size.coerceAtLeast(1)
            // One tab's room next to Пинг, less what the layout may round up.
            fun tabRoom(ping: Float): Float = (width.toPx() - TabSize.gap.toPx() - ping - (TabSize.stripPad * 2).toPx() -
                TabSize.chipGap.toPx() * (n - 1)) / n - TabSize.slack.toPx()
            // The icon, the title and the count.
            fun titleRow(group: Group, count: Int, spacing: Dp, badgePad: Dp, icon: Boolean): Float =
                (if (icon) (TabSize.icon + spacing).toPx() else 0f) + textWidth(group.title, TabSize.title) + spacing.toPx() +
                    textWidth("$count", TabSize.badge, FontWeight.SemiBold) + (badgePad * 2).toPx()
            val pingRow = (TabSize.pingPad * 2 + TabSize.pingIcon + TabSize.pingSpacing).toPx() + pingWord(TabSize.ping)
            val oneLine = counts.all { (group, count) ->
                val network = (TabSize.wideSpacing + TabSize.wideNetworkGap + TabSize.wideGlyph + TabSize.networkSpacing).toPx() +
                    textWidth(group.networks, TabSize.wideNetwork)
                (TabSize.widePad * 2).toPx() + titleRow(group, count, TabSize.wideSpacing, TabSize.badgePad, icon = true) + network <=
                    tabRoom(pingRow)
            }
            if (oneLine) return@remember TabsFit(oneLine = true, tight = false, icon = true, networkSize = TabSize.wideNetwork)
            // Two lines: Пинг as a column is narrower, the tabs get the rest.
            val pingColumn = maxOf(TabSize.pingColumn.toPx(), (TabSize.pingColumnPad * 2).toPx() + pingWord(TabSize.pingColumnText))
            val inner = tabRoom(pingColumn) - (TabSize.narrowPad * 2).toPx()
            fun rowsFit(spacing: Dp, badgePad: Dp): Boolean =
                counts.all { (group, count) -> titleRow(group, count, spacing, badgePad, icon = true) <= inner }
            val tight = !rowsFit(TabSize.narrowSpacing, TabSize.badgePad)
            val icon = !tight || rowsFit(TabSize.tightSpacing, TabSize.tightBadgePad)
            // The network line at its size, else half a point smaller at a time, else none.
            val room = inner - (TabSize.narrowGlyph + TabSize.networkSpacing).toPx()
            var size = TabSize.narrowNetwork.value
            while (size >= TabSize.minNetwork.value &&
                counts.any { (group, _) -> textWidth(group.networks, size.sp) > room }
            ) size -= 0.5f
            TabsFit(oneLine = false, tight = tight, icon = icon, networkSize = if (size >= TabSize.minNetwork.value) size.sp else null)
        }
    }
}

@Composable
private fun TabChip(group: Group, count: Int, selected: Boolean, fit: TabsFit, modifier: Modifier, onClick: () -> Unit) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(13.dp)
    // Where it works, under the picked tab's title: a lighter blue on dark; on light a little
    // darker than the title, as small text needs (about 5.4:1 on the tab's tint, not 3.4).
    val picked = if (palette.isDark) lerp(palette.primary, Color.White, 0.55f) else lerp(palette.primary, Color.Black, 0.1f)
    val text by animateColorAsState(if (selected) palette.primary else palette.textSecondary, tween(200), label = "tabText")
    val network by animateColorAsState(if (selected) picked else palette.textSecondary, tween(200), label = "tabNetwork")
    val fill by animateColorAsState(if (selected) palette.primary.copy(alpha = 0.08f) else Color.Transparent, tween(200), label = "tabFill")
    val stroke by animateColorAsState(if (selected) palette.primary.copy(alpha = 0.35f) else Color.Transparent, tween(200), label = "tabStroke")
    val badge by animateColorAsState(if (selected) palette.primary else palette.outline, tween(200), label = "tabBadge")
    val badgeText by animateColorAsState(if (selected) Color.White else palette.textSecondary, tween(200), label = "tabBadgeText")
    val description = ServerGroups.tabDescription(group, count)
    val icon = if (group == Group.SERVERS) Icons.Filled.Language else Icons.Filled.Shuffle
    Box(
        modifier
            .pressable(
                onClick = {
                    if (!selected) {
                        haptics.selection()
                        onClick()
                    }
                },
                scale = 0.97f,
                role = Role.Tab,
            )
            .semantics {
                this.selected = selected
                contentDescription = description
            }
            .heightIn(min = if (fit.oneLine) 44.dp else 50.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .padding(horizontal = if (fit.oneLine) TabSize.widePad else TabSize.narrowPad),
        contentAlignment = Alignment.Center,
    ) {
        // Read out as the description, in one go.
        val parts = Modifier.clearAndSetSemantics {}
        if (fit.oneLine) {
            Row(
                parts,
                horizontalArrangement = Arrangement.spacedBy(TabSize.wideSpacing, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, Modifier.size(TabSize.icon), tint = text)
                TabTitle(group.title, text, Modifier.weight(1f, fill = false))
                CountBadge(count, badge, badgeText)
                NetworkLabel(group, network, TabSize.wideNetwork, TabSize.wideGlyph, Modifier.padding(start = TabSize.wideNetworkGap))
            }
        } else {
            Column(parts, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(
                        if (fit.tight) TabSize.tightSpacing else TabSize.narrowSpacing,
                        Alignment.CenterHorizontally,
                    ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (fit.icon) Icon(icon, null, Modifier.size(TabSize.icon), tint = text)
                    TabTitle(group.title, text, Modifier.weight(1f, fill = false))
                    CountBadge(count, badge, badgeText, if (fit.tight) TabSize.tightBadgePad else TabSize.badgePad)
                }
                fit.networkSize?.let { NetworkLabel(group, network, it, TabSize.narrowGlyph) }
            }
        }
    }
}

@Composable
private fun TabTitle(title: String, color: Color, modifier: Modifier) {
    Text(
        title,
        modifier,
        color = color,
        fontSize = TabSize.title,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CountBadge(count: Int, background: Color, color: Color, pad: Dp = TabSize.badgePad) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .padding(horizontal = pad, vertical = 2.dp),
    ) {
        Text("$count", color = color, fontSize = TabSize.badge, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Where a tab's servers work: Wi-Fi's fan or the signal bars, and the words. */
@Composable
private fun NetworkLabel(group: Group, color: Color, size: TextUnit, glyph: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TabSize.networkSpacing), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (group == Group.SERVERS) Icons.Filled.Wifi else Icons.Filled.SignalCellularAlt, null, Modifier.size(glyph), tint = color)
        Text(
            group.networks,
            color = color,
            fontSize = size,
            lineHeight = size * 1.3f,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Пинг for every server (the tab on screen first); Стоп with a progress ring while it runs.
 * [column]: the icon above the word (phones, two-line tabs), else beside it.
 */
@Composable
private fun PingButton(pinging: Boolean, progress: () -> Float, onClick: () -> Unit, column: Boolean, modifier: Modifier = Modifier) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(16.dp)
    val lit by animateFloatAsState(if (pinging) 1f else 0f, tween(200), label = "pingFill")
    val text by animateColorAsState(if (pinging) Color.White else MaterialTheme.colorScheme.onSurface, tween(200), label = "pingText")
    val description = if (pinging) "Остановить пинг" else "Пинг всех серверов"
    val button = modifier
        .pressable(onClick = {
            haptics.tap()
            onClick()
        })
        .semantics { contentDescription = description }
        .heightIn(min = 52.dp)
        .clip(shape)
        .background(palette.surface)
        .background(AccentGradient, shape, alpha = lit)
    val icon = @Composable {
        if (pinging) {
            ProgressRing(progress(), Modifier.size(17.dp))
        } else {
            Icon(Icons.Filled.Speed, null, Modifier.size(TabSize.pingIcon), tint = text)
        }
    }
    val word = if (pinging) "Стоп" else "Пинг"
    if (column) {
        Column(
            button.widthIn(min = TabSize.pingColumn).padding(horizontal = TabSize.pingColumnPad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TabSize.pingColumnSpacing, Alignment.CenterVertically),
        ) {
            icon()
            Text(
                word,
                color = text,
                fontSize = TabSize.pingColumnText,
                lineHeight = TabSize.pingColumnLine,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    } else {
        Row(
            button.padding(horizontal = TabSize.pingPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TabSize.pingSpacing),
        ) {
            icon()
            Text(word, color = text, fontSize = TabSize.ping, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/**
 * The line under the tabs: what the tab's servers cost. Обходы on Wi-Fi ([wifiLocked]): also
 * that they work over mobile internet only.
 */
@Composable
internal fun ListCaption(page: Group, info: SubscriptionInfo, wifiLocked: Boolean, onBuy: () -> Unit, modifier: Modifier = Modifier) {
    val palette = Titan.palette
    Column(modifier.fillMaxWidth().padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when {
            page == Group.SERVERS -> Caption("Безлимит · работают и на Wi‑Fi, и в мобильной сети")
            info.totalBytes > 0 -> Row(verticalAlignment = Alignment.CenterVertically) {
                val left = (info.totalBytes - info.usedBytes).coerceAtLeast(0)
                Caption("Трафик: ${Fmt.bytes(left)} из ${Fmt.bytes(info.totalBytes)}", Modifier.weight(1f, fill = false))
                // Android: more GB in Личный кабинет.
                Text(
                    "Докупить ГБ",
                    Modifier
                        .pressable(onClick = onBuy, scale = 0.95f)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = palette.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
            else -> Caption("Для ограничений мобильного интернета")
        }
        if (page == Group.BYPASS && wifiLocked) {
            Text(NetworkMonitor.BYPASS_WIFI_MESSAGE, color = palette.orange, fontSize = 13.sp)
        }
    }
}

@Composable
private fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = Titan.palette.textSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/**
 * One server: flag, name (scrolls when long), what it costs, its ping (tap: measure just it)
 * and the choice mark. Swipe right: favourite; left: ping; long press: a menu. Its values are
 * plain, so a ping result redraws only its own row.
 */
@Composable
internal fun ServerRow(
    name: String,
    ping: Long?,
    measuring: Boolean,
    error: String?,
    selected: Boolean,
    connected: Boolean,
    favorite: Boolean,
    locked: Boolean,
    limited: Boolean,
    techMode: Boolean,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val title = Flags.nameWithoutFlag(name)
    // Технический режим: why the last ping failed (also to copy for the support chat).
    val reason = error?.takeIf { techMode && ping == -1L }
    SwipeRow(isFavorite = favorite, onFavorite = onFavorite, onPing = onPing, enabled = true, modifier = modifier) {
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .pressable(
                        onClick = onSelect,
                        scale = 0.98f,
                        // The long press is felt already (combinedClickable's own haptic).
                        onLongClick = { menu = true },
                        onClickLabel = "Выбрать",
                    )
                    .swipeActions(favorite, onFavorite, onPing)
                    .semantics { this.selected = selected }
                    .alpha(if (locked) 0.45f else 1f)
                    .heightIn(min = 60.dp)
                    .clip(shape)
                    .background(if (selected) palette.primary.copy(alpha = 0.08f) else palette.surface)
                    .then(if (selected) Modifier.border(1.5.dp, palette.primary, shape) else Modifier)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlagCircle(name, 34.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (favorite) Icon(Icons.Filled.Star, null, Modifier.size(11.dp), tint = palette.yellow)
                        MarqueeText(title, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(
                        if (limited) "Лимитный · расходует ГБ" else "Безлимит",
                        color = if (limited) palette.orange else palette.green,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                    if (reason != null && !measuring) {
                        Text(reason, color = palette.red.copy(alpha = 0.8f), fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
                PingValue(ping, measuring, onPing)
                ChoiceMark(selected, connected)
            }
            ServerMenu(
                expanded = menu,
                onDismiss = { menu = false },
                locked = locked,
                favorite = favorite,
                reason = reason,
                onSelect = onSelect,
                onPing = onPing,
                onFavorite = onFavorite,
                onCopy = {
                    val text = "$title: $reason"
                    runCatching {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Titan VPS", text))
                    }
                    haptics.success()
                    Toasts.show("Ошибка скопирована", ToastKind.SUCCESS)
                },
            )
        }
    }
}

/** Tap the ping to measure just this server (again to stop). */
@Composable
private fun PingValue(ping: Long?, measuring: Boolean, onPing: () -> Unit) {
    val palette = Titan.palette
    Box(
        Modifier
            .pressable(onClick = onPing, scale = 0.9f, onClickLabel = "Пинг")
            .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (measuring) CircularProgressIndicator(Modifier.size(14.dp), color = palette.primary, strokeWidth = 1.5.dp)
            when {
                ping != null -> Text(
                    if (ping >= 0) "$ping мс" else "таймаут",
                    Modifier.alpha(if (measuring) 0.5f else 1f),
                    color = pingColor(ping),
                    fontSize = 14.sp,
                    maxLines = 1,
                    style = tabularStyle(),
                )
                !measuring -> Icon(Icons.Filled.Speed, "Пинг", Modifier.size(18.dp), tint = palette.textSecondary.copy(alpha = 0.6f))
            }
        }
    }
}

/** The choice: a ring with a dot; the connected server's ring is green with a check (Android). */
@Composable
private fun ChoiceMark(selected: Boolean, connected: Boolean) {
    val palette = Titan.palette
    when {
        selected && connected -> Box(Modifier.size(26.dp).border(2.dp, palette.green, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = palette.green)
        }
        selected -> Box(Modifier.size(26.dp).border(2.dp, palette.primary, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(palette.primary))
        }
        else -> Box(Modifier.size(26.dp).border(1.5.dp, palette.outline, CircleShape))
    }
}

/** Long press on a server: Выбрать, Пинг, the favourite, and (Технический режим) the error. */
@Composable
private fun ServerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    locked: Boolean,
    favorite: Boolean,
    reason: String?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onFavorite: () -> Unit,
    onCopy: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // A window of its own: the app's text size, as on the screen.
        ProvideTitanTextScale {
            MenuItem("Выбрать", Icons.Filled.CheckCircle, enabled = !locked) { onDismiss(); onSelect() }
            MenuItem("Пинг", Icons.Filled.Speed) { onDismiss(); onPing() }
            if (favorite) {
                MenuItem("Убрать из избранного", Icons.Outlined.StarOutline) { onDismiss(); onFavorite() }
            } else {
                MenuItem("В избранное", Icons.Filled.Star) { onDismiss(); onFavorite() }
            }
            if (reason != null) MenuItem("Скопировать ошибку", Icons.Filled.ContentCopy) { onDismiss(); onCopy() }
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, fontSize = 15.sp) },
        onClick = onClick,
        leadingIcon = { Icon(icon, null, Modifier.size(20.dp)) },
        enabled = enabled,
    )
}

/** A bypass server tapped on Wi-Fi: why it can't be picked. */
@Composable
internal fun WifiLockedDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { ProvideTitanTextScale { Text("Понятно") } }
        },
        title = { ProvideTitanTextScale { Text("Недоступно на Wi-Fi") } },
        text = {
            ProvideTitanTextScale {
                Text(
                    "Обходы работают только через мобильный интернет и нужны при его ограничениях. " +
                        "На Wi-Fi подключиться к ним нельзя — выберите сервер во вкладке «Страны».",
                )
            }
        },
        containerColor = Titan.palette.surface,
    )
}
