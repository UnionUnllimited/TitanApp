package com.titanvps.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.R
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerGroups.Group
import com.titanvps.app.data.Subscription
import com.titanvps.app.data.ThemeMode
import com.titanvps.app.ui.theme.Connected
import com.titanvps.app.ui.theme.isDark
import com.titanvps.app.ui.theme.pingColor
import com.titanvps.app.vpn.VpnState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private fun isAuto(s: Server) = s.name.lowercase().let { "авто" in it || "auto" in it }

@Composable
private fun isLight() = MaterialTheme.colorScheme.background.luminance() > 0.5f

/** Green used for the connected state: deeper on light backgrounds. */
@Composable
private fun connectedGreen() = if (isLight()) Color(0xFF12A150) else Connected

/**
 * Главная, as in the mockup: header, subscription card, Выкл | Вкл switch, status,
 * Серверы | Обходы tabs with a ping button and the server list. Обходы: mobile data only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    viewModel: MainViewModel,
    sub: Subscription,
    busy: Boolean,
    onConnect: () -> Unit,
    onOpenSubscription: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val pings by viewModel.pings.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    val pingErrors by viewModel.pingErrors.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val onMobile by viewModel.onMobile.collectAsState()
    val theme by viewModel.theme.collectAsState()
    val dark = isDark(theme)
    var details by remember { mutableStateOf<Server?>(null) }

    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers).toMap() }
    var page by rememberSaveable(sub.servers) {
        mutableStateOf(selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: Group.SERVERS)
    }
    if (groups[page].isNullOrEmpty()) page = Group.SERVERS
    val bypassLocked = page == Group.BYPASS && !onMobile
    val selectedLocked = !onMobile && selected != null && ServerGroups.groupOf(selected, sub.servers) == Group.BYPASS
    val connected = state is VpnState.Connected

    val onToggle: (Boolean) -> Unit = { on ->
        when {
            on && state !is VpnState.Connected && state != VpnState.Connecting -> onConnect()
            !on && (state is VpnState.Connected || state == VpnState.Connecting) -> viewModel.disconnect()
        }
    }
    val topItems: LazyListScope.() -> Unit = {
        item { SubscriptionCard(sub, onOpenSubscription) }
        item { PowerSwitch(state, Modifier.padding(top = 6.dp), onToggle) }
        item { StatusBlock(state, selected, selected?.let { pings[it.id] }, selectedLocked) }
    }
    val listItems: LazyListScope.() -> Unit = {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { (group, list) ->
                        GroupTab(group, list.size, group == page, Modifier.weight(1f)) { page = group }
                    }
                }
                PingButton(pinging, viewModel::pingAll)
            }
        }
        item {
            when {
                page == Group.SERVERS -> Caption("Безлимит на обычных серверах")
                bypassLocked -> WifiWarning()
                else -> BypassCaption(sub) { context.openUrl(BuildConfig.TELEGRAM_URL) }
            }
        }
        items(groups[page].orEmpty(), key = { it.id }) { s ->
            ServerRow(
                server = s,
                ping = pings[s.id],
                pending = pinging && pings[s.id] == null,
                selected = s.id == selected?.id,
                connected = connected && s.id == selected?.id,
                favorite = s.name in favorites,
                locked = bypassLocked,
                onClick = { if (bypassLocked) viewModel.explainBypassOnWifi() else viewModel.select(s.id) },
                onLongClick = { details = s },
            )
        }
    }
    val header: @Composable (Modifier) -> Unit = { m ->
        HomeHeader(
            dark = dark,
            onSettings = onOpenSettings,
            onTheme = { viewModel.setTheme(if (dark) ThemeMode.LIGHT else ThemeMode.DARK) },
            modifier = m,
        )
    }
    val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)

    // Phones: one column up to 640dp, centered. Tablets / landscape: controls on the
    // left, the server list on the right.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 720.dp) {
            Column(Modifier.widthIn(max = 1200.dp).fillMaxSize()) {
                header(Modifier.fillMaxWidth())
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.weight(0.9f).fillMaxHeight(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        content = topItems,
                    )
                    PullToRefreshBox(isRefreshing = busy, onRefresh = { viewModel.refresh() }, modifier = Modifier.weight(1.1f).fillMaxHeight()) {
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = listPadding,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            content = listItems,
                        )
                    }
                }
            }
        } else {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                header(Modifier.fillMaxWidth())
                PullToRefreshBox(isRefreshing = busy, onRefresh = { viewModel.refresh() }, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        topItems()
                        listItems()
                    }
                }
            }
        }
    }

    details?.let { server ->
        ServerDetailsSheet(
            server = server,
            ping = pings[server.id],
            error = pingErrors[server.id],
            pinging = pinging,
            selected = server.id == selected?.id,
            favorite = server.name in favorites,
            locked = !onMobile && ServerGroups.groupOf(server, sub.servers) == Group.BYPASS,
            onPing = { viewModel.pingOne(server.id) },
            onFavorite = { viewModel.toggleFavorite(server.name) },
            onSelect = { viewModel.select(server.id); details = null },
            onDismiss = { details = null },
        )
    }
}

/** Settings button, logo + "Titan VPS", theme button. */
@Composable
private fun HomeHeader(dark: Boolean, onSettings: () -> Unit, onTheme: () -> Unit, modifier: Modifier) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareButton(Icons.Outlined.Settings, "Настройки", onSettings)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp))
            Spacer(Modifier.width(8.dp))
            Text("Titan VPS", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        SquareButton(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, "Тема", onTheme)
    }
}

@Composable
private fun SquareButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .shadow(2.dp, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp))
    }
}

/** "Подписка активна · До …" with an expandable "Подробнее". */
@Composable
private fun SubscriptionCard(sub: Subscription, onOpen: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val info = sub.info
    val now = System.currentTimeMillis() / 1000
    val active = info.expireAt <= 0 || info.expireAt > now
    val primary = MaterialTheme.colorScheme.primary
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(primary.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(TitanIcons.Crown, null, Modifier.size(22.dp), tint = if (active) primary else MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (active) "Подписка активна" else "Подписка истекла", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(
                        if (info.expireAt <= 0) "Бессрочно"
                        else "До " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(info.expireAt * 1000)),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Подписка", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Подробнее", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (info.expireAt > 0) {
                        val days = TimeUnit.SECONDS.toDays(info.expireAt - now).coerceAtLeast(0)
                        DetailLine("Осталось", "$days дн.")
                    }
                    DetailLine("Обычные серверы", "Безлимит")
                    DetailLine(
                        "Трафик на обходах",
                        if (info.totalBytes > 0) "${formatBytes((info.totalBytes - info.usedBytes).coerceAtLeast(0))} из ${formatBytes(info.totalBytes)}"
                        else "Безлимит",
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Выкл | Вкл switch: grey "Выкл" when off, spinner knob while connecting, green "Вкл" when on. */
@Composable
private fun PowerSwitch(state: VpnState, modifier: Modifier, onToggle: (Boolean) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val green = connectedGreen()
    val connecting = state == VpnState.Connecting || state == VpnState.Disconnecting
    val on = state is VpnState.Connected
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(34.dp))
            .background(
                if (connecting) Brush.horizontalGradient(listOf(track, primary.copy(alpha = 0.18f), track))
                else Brush.horizontalGradient(listOf(track, track))
            )
            .padding(5.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            val offBg by animateColorAsState(if (!on && !connecting) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, label = "off")
            val onBg by animateColorAsState(if (on) green.copy(alpha = 0.16f) else Color.Transparent, label = "on")
            Row(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(30.dp)).background(offBg).clickable { onToggle(false) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Выкл", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(30.dp))
                    .background(onBg)
                    .then(if (on) Modifier.border(1.5.dp, green, RoundedCornerShape(30.dp)) else Modifier)
                    .clickable { onToggle(true) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = when {
                    on -> if (isLight()) Color(0xFF0B6B38) else green
                    connecting -> primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Icon(Icons.Outlined.Shield, null, Modifier.size(22.dp), tint = tint)
                Spacer(Modifier.width(10.dp))
                Text("Вкл", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = tint)
            }
        }
        if (connecting) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(58.dp)
                    .shadow(10.dp, CircleShape, ambientColor = primary, spotColor = primary)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.5.dp, color = primary)
            }
        }
    }
}

/** "Не подключено / Подключаемся / Подключено" with the server, ping and timer. */
@Composable
private fun StatusBlock(state: VpnState, server: Server?, ping: Long?, locked: Boolean) {
    val green = connectedGreen()
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val (title, color) = when (state) {
            is VpnState.Connected -> "Подключено" to green
            VpnState.Connecting -> "Подключаемся" to MaterialTheme.colorScheme.primary
            VpnState.Disconnecting -> "Отключаемся" to MaterialTheme.colorScheme.onSurface
            else -> "Не подключено" to MaterialTheme.colorScheme.onSurface
        }
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = color)
        val sub = MaterialTheme.colorScheme.onSurfaceVariant
        when (state) {
            is VpnState.Connected -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    server?.let { FlagCircle(it, 24.dp) }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (server?.let { ServerGroups.splitFlag(it.name).second } ?: state.serverName) +
                            (ping?.takeIf { it >= 0 }?.let { " · $it мс" } ?: ""),
                        fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                val s = (now - state.since).coerceAtLeast(0) / 1000
                Text(
                    if (s >= 3600) formatDuration(now - state.since) else "%02d:%02d".format(s / 60, s % 60),
                    fontSize = 13.sp, color = sub,
                )
            }
            VpnState.Connecting -> Text("Устанавливаем соединение", fontSize = 15.sp, color = sub)
            VpnState.Disconnecting -> Text("Закрываем соединение", fontSize = 15.sp, color = sub)
            is VpnState.Error -> Text(state.message, fontSize = 14.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            else -> Text(
                when {
                    locked -> "Обходы недоступны на Wi-Fi — выберите сервер"
                    server != null -> ServerGroups.splitFlag(server.name).second
                    else -> "Выберите сервер"
                },
                fontSize = 15.sp,
                color = if (locked) MaterialTheme.colorScheme.error else sub,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        }
    }
}

/** "Серверы 35" / "Обходы 52" tab with an icon and a count badge. */
@Composable
private fun GroupTab(group: Group, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(13.dp)
    val color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.08f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, primary.copy(alpha = 0.35f), shape) else Modifier)
            .clickable(onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (group == Group.SERVERS) Icons.Outlined.Storage else Icons.Default.Shuffle, null, Modifier.size(18.dp), tint = color)
        Spacer(Modifier.width(6.dp))
        Text(group.title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) primary else MaterialTheme.colorScheme.outlineVariant)
                .padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Text("$count", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PingButton(pinging: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !pinging, onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinging) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Speed, null, Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text("Пинг", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
}

/** Bypass traffic line: what's left and a "Докупить ГБ" link to the bot. */
@Composable
private fun BypassCaption(sub: Subscription, onBuy: () -> Unit) {
    val info = sub.info
    Row(Modifier.fillMaxWidth().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (info.totalBytes > 0) "Трафик: ${formatBytes((info.totalBytes - info.usedBytes).coerceAtLeast(0))} из ${formatBytes(info.totalBytes)}"
            else "Для ограничений мобильного интернета",
            Modifier.weight(1f),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Докупить ГБ",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBuy).padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

/** Shown on the Обходы tab while the phone is on Wi-Fi. */
@Composable
private fun WifiWarning() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Wifi, null, Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Недоступно на Wi-Fi", fontWeight = FontWeight.SemiBold)
                Text(
                    "Обходы работают только через мобильный интернет и нужны при его ограничениях. " +
                        "На Wi-Fi подключиться к ним нельзя — выберите сервер во вкладке «Серверы».",
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** Round flag from the name's emoji; АВТО gets a bolt, other entries a server icon. */
@Composable
private fun FlagCircle(server: Server, size: androidx.compose.ui.unit.Dp) {
    val flag = remember(server.name) { ServerGroups.splitFlag(server.name).first }
    Box(
        Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // Emoji drawn larger than the circle so the flag fills it.
            flag != null -> Text(flag, fontSize = (size.value * 0.95f).sp, lineHeight = (size.value * 0.95f).sp, maxLines = 1, softWrap = false)
            isAuto(server) -> Icon(Icons.Default.Bolt, null, Modifier.size(size * 0.6f), tint = MaterialTheme.colorScheme.primary)
            else -> Icon(Icons.Outlined.Dns, null, Modifier.size(size * 0.55f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: Server,
    ping: Long?,
    pending: Boolean,
    selected: Boolean,
    connected: Boolean,
    favorite: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val accent = if (connected) connectedGreen() else MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(1.5.dp, accent, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (locked) 0.45f else 1f)
            .heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagCircle(server, 34.dp)
        Spacer(Modifier.width(14.dp))
        Text(
            ServerGroups.splitFlag(server.name).second,
            Modifier.weight(1f),
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (favorite) {
            Icon(Icons.Default.Star, null, tint = Color(0xFFF5B301), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        when {
            pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            ping != null -> Text(
                if (ping >= 0) "$ping мс" else "таймаут",
                color = if (ping >= 0 && ping < 300) MaterialTheme.colorScheme.onSurfaceVariant else pingColor(ping),
                fontSize = 15.sp,
            )
        }
        Spacer(Modifier.width(14.dp))
        when {
            connected -> Box(Modifier.size(26.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(17.dp))
            }
            selected -> Box(Modifier.size(26.dp).border(2.dp, accent, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(13.dp).clip(CircleShape).background(accent))
            }
            else -> Box(Modifier.size(26.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDetailsSheet(
    server: Server,
    ping: Long?,
    error: String?,
    pinging: Boolean,
    selected: Boolean,
    favorite: Boolean,
    locked: Boolean,
    onPing: () -> Unit,
    onFavorite: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag != null) Text(flag, fontSize = 32.sp)
                Spacer(Modifier.width(12.dp))
                Text(name, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = onFavorite) {
                    if (favorite) Icon(Icons.Default.Star, "Убрать из избранного", tint = Color(0xFFF5B301))
                    else Icon(Icons.Outlined.StarBorder, "В избранное")
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Пинг", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    pinging && ping == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    ping != null -> Text(if (ping >= 0) "$ping мс" else "таймаут", color = pingColor(ping), fontWeight = FontWeight.Medium)
                    else -> Text("—")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(configSummary(server), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (error != null && (ping ?: 0) < 0) {
                Spacer(Modifier.height(8.dp))
                Text("Причина: $error", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPing, enabled = !pinging, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Default.Speed, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Пинг")
                }
                Button(onClick = onSelect, enabled = !selected && !locked, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (selected) "Выбран" else if (locked) "Только моб. сеть" else "Выбрать")
                }
            }
        }
    }
}

/** Where the config came from and how much of the panel's routing it carries. */
private fun configSummary(server: Server): String {
    val cfg = runCatching { org.json.JSONObject(server.xrayJson) }.getOrNull() ?: return "Конфиг: —"
    val routing = cfg.optJSONObject("routing")
    val rules = routing?.optJSONArray("rules")?.length() ?: 0
    val balancers = routing?.optJSONArray("balancers")?.length() ?: 0
    val outbounds = cfg.optJSONArray("outbounds")?.length() ?: 0
    return if (server.id.startsWith("json-")) {
        "Конфиг: Xray JSON с сервера · правил: $rules · балансировщиков: $balancers · узлов: $outbounds"
    } else {
        "Конфиг: из ссылки (стандартная маршрутизация) — обновите подписку"
    }
}
