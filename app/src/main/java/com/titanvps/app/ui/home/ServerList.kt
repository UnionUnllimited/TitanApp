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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Fmt
import com.titanvps.app.data.NetworkMonitor
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
 * Серверы | Обходы (only those that have servers) and the Пинг button. [progress]: how much of
 * a running Пинг is done, read only by the button.
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
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(palette.surface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            counts.forEach { (group, count) ->
                TabChip(group, count, group == page, Modifier.weight(1f)) { onPage(group) }
            }
        }
        PingButton(pinging, progress, onPing)
    }
}

@Composable
private fun TabChip(group: Group, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(13.dp)
    val text by animateColorAsState(if (selected) palette.primary else palette.textSecondary, tween(200), label = "tabText")
    val fill by animateColorAsState(if (selected) palette.primary.copy(alpha = 0.08f) else Color.Transparent, tween(200), label = "tabFill")
    val stroke by animateColorAsState(if (selected) palette.primary.copy(alpha = 0.35f) else Color.Transparent, tween(200), label = "tabStroke")
    val badge by animateColorAsState(if (selected) palette.primary else palette.outline, tween(200), label = "tabBadge")
    val badgeText by animateColorAsState(if (selected) Color.White else palette.textSecondary, tween(200), label = "tabBadgeText")
    Row(
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
            .semantics { this.selected = selected }
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, stroke, shape)
            .padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (group == Group.SERVERS) Icons.Filled.Storage else Icons.Filled.Shuffle, null, Modifier.size(16.dp), tint = text)
        Text(
            group.title,
            Modifier.weight(1f, fill = false),
            color = text,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(badge)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text("$count", color = badgeText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Пинг for every server (the tab on screen first); Стоп with a progress ring while it runs. */
@Composable
private fun PingButton(pinging: Boolean, progress: () -> Float, onClick: () -> Unit) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val shape = RoundedCornerShape(16.dp)
    val lit by animateFloatAsState(if (pinging) 1f else 0f, tween(200), label = "pingFill")
    val text by animateColorAsState(if (pinging) Color.White else MaterialTheme.colorScheme.onSurface, tween(200), label = "pingText")
    val description = if (pinging) "Остановить пинг" else "Пинг всех серверов"
    Row(
        Modifier
            .pressable(onClick = {
                haptics.tap()
                onClick()
            })
            .semantics { contentDescription = description }
            .height(52.dp)
            .clip(shape)
            .background(palette.surface)
            .background(AccentGradient, shape, alpha = lit)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (pinging) {
            ProgressRing(progress(), Modifier.size(17.dp))
        } else {
            Icon(Icons.Filled.Speed, null, Modifier.size(18.dp), tint = text)
        }
        Text(if (pinging) "Стоп" else "Пинг", color = text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
            page == Group.SERVERS -> Caption("Безлимит на обычных серверах")
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
                        "На Wi-Fi подключиться к ним нельзя — выберите сервер во вкладке «Серверы».",
                )
            }
        },
        containerColor = Titan.palette.surface,
    )
}
