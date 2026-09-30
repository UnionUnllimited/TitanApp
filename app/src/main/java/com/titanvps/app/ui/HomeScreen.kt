package com.titanvps.app.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.R
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerGroups.Group
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.theme.Brand
import com.titanvps.app.ui.theme.BrandGradient
import com.titanvps.app.ui.theme.Connected
import com.titanvps.app.ui.theme.pingColor
import com.titanvps.app.vpn.VpnState
import kotlinx.coroutines.delay

private enum class Filter(val title: String) { ALL("Все"), AUTO("Авто"), FAVORITES("Избранное") }

private fun isAuto(s: Server) = s.name.lowercase().let { "авто" in it || "auto" in it }

/**
 * Главная, as in the mockup: a server list with a big "Подключиться" button at the
 * bottom and a Серверы | Обходы switcher on top. Обходы work on mobile data only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(viewModel: MainViewModel, sub: Subscription, busy: Boolean, onConnect: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val pings by viewModel.pings.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    val pingErrors by viewModel.pingErrors.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val onMobile by viewModel.onMobile.collectAsState()
    var details by remember { mutableStateOf<Server?>(null) }

    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers).toMap() }
    var page by rememberSaveable(sub.servers) {
        mutableStateOf(selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: Group.SERVERS)
    }
    if (groups[page].isNullOrEmpty()) page = Group.SERVERS
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val bypassLocked = page == Group.BYPASS && !onMobile
    val selectedLocked = !onMobile && selected != null && ServerGroups.groupOf(selected, sub.servers) == Group.BYPASS

    val shown = groups[page].orEmpty().filter { s ->
        (query.isBlank() || s.name.contains(query.trim(), ignoreCase = true)) &&
            when (filter) {
                Filter.ALL -> true
                Filter.AUTO -> isAuto(s)
                Filter.FAVORITES -> s.name in favorites
            }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally).padding(horizontal = 4.dp)) {
            TopBar(title = "Главная") {
                IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, "Обновить подписку")
                }
            }
        }

        PullToRefreshBox(isRefreshing = busy, onRefresh = { viewModel.refresh() }, modifier = Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().align(Alignment.TopCenter),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (groups.size > 1) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            groups.forEach { (group, list) ->
                                TabChip(group.title, list.size, group == page, Modifier.weight(1f)) {
                                    if (page != group) { page = group; filter = Filter.ALL; query = "" }
                                }
                            }
                        }
                    }
                }
                if (page == Group.BYPASS) {
                    if (!onMobile) item { WifiWarning() }
                    else item { InfoBanner("Для ограничений мобильного интернета") }
                    item { RemainingTrafficCard(sub) { context.openUrl(BuildConfig.TELEGRAM_URL) } }
                } else {
                    sub.info.announce?.let { item { InfoBanner(it) } }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(if (page == Group.BYPASS) "Найти обход" else "Найти сервер") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Filter.entries.forEach { f -> FilterChip(f.title, f == filter, Modifier.weight(1f)) { filter = f } }
                        PingButton(pinging, viewModel::pingAll)
                    }
                }
                if (shown.isEmpty()) {
                    item {
                        Text(
                            if (filter == Filter.FAVORITES) "Пока пусто. Зажмите сервер и добавьте его в избранное." else "Ничего не найдено",
                            Modifier.fillMaxWidth().padding(24.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shown, key = { it.id }) { s ->
                    ServerCard(
                        server = s,
                        ping = pings[s.id],
                        pending = pinging && pings[s.id] == null,
                        selected = s.id == selected?.id,
                        favorite = s.name in favorites,
                        locked = bypassLocked,
                        onClick = { if (bypassLocked) viewModel.explainBypassOnWifi() else viewModel.select(s.id) },
                        onLongClick = { details = s },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }

        ConnectBar(state, selected, selectedLocked, Modifier.widthIn(max = 640.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
            when (state) {
                is VpnState.Connected, VpnState.Connecting -> viewModel.disconnect()
                VpnState.Disconnecting -> Unit
                else -> onConnect()
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

/** Big bottom button from the mockup, with connection state and timer. */
@Composable
private fun ConnectBar(state: VpnState, server: Server?, locked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        when (state) {
            is VpnState.Connected -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                Text(
                    "● Подключено · ${formatDuration(now - state.since)}" + (server?.let { " · ${it.name}" } ?: ""),
                    color = Connected, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
                )
            }
            is VpnState.Error -> Text(
                state.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
            )
            else -> if (locked) Text(
                "Обходы недоступны на Wi-Fi — выберите сервер во вкладке «Серверы»",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp),
            )
        }
        val connected = state is VpnState.Connected
        Button(
            onClick = onClick,
            enabled = state != VpnState.Disconnecting,
            shape = RoundedCornerShape(14.dp),
            colors = if (connected) ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        ) {
            if (state == VpnState.Connecting || state == VpnState.Disconnecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                Spacer(Modifier.width(10.dp))
            }
            Text(
                when (state) {
                    is VpnState.Connected -> "Отключиться"
                    VpnState.Connecting -> "Подключение…"
                    VpnState.Disconnecting -> "Отключение…"
                    else -> "Подключиться"
                },
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** One half of the Серверы | Обходы switcher. */
@Composable
private fun TabChip(title: String, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, label = "tab")
    Row(
        modifier
            .clip(RoundedCornerShape(11.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Text(title, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("$count", color = color.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
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

/**
 * "Остаток трафика" card. Dark mockup: "Докупить ГБ" full width under the progress;
 * light mockup: the button sits to the right of the amount.
 */
@Composable
private fun RemainingTrafficCard(sub: Subscription, onBuy: () -> Unit) {
    val info = sub.info
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val remaining = (info.totalBytes - info.usedBytes).coerceAtLeast(0)
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Остаток трафика", fontWeight = FontWeight.SemiBold)
                Text(if (info.totalBytes > 0) formatBytes(remaining) else "Безлимит", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                if (info.totalBytes > 0) Text("из ${formatBytes(info.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (light) Button(onClick = onBuy, shape = RoundedCornerShape(12.dp)) { Text("Докупить ГБ") }
        }
        if (info.totalBytes > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { (remaining.toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        if (!light) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onBuy, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("Докупить ГБ", fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!light) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
            }
            Text("Трафик расходуется только на обходах", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FilterChip(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PingButton(pinging: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !pinging, onClick = onClick)
            .padding(9.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pinging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Speed, "Пинг", Modifier.size(20.dp))
    }
}

/**
 * List row from the mockup. Dark: server icon; light: signal bars colored by ping.
 * "Авто" entries get the "По качеству соединения" subtitle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: Server,
    ping: Long?,
    pending: Boolean,
    selected: Boolean,
    favorite: Boolean,
    locked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) primary.copy(alpha = if (light) 0.08f else 0.10f) else MaterialTheme.colorScheme.surface)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (locked) 0.45f else 1f)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (light) {
            Icon(
                Icons.Default.SignalCellularAlt, null, Modifier.size(24.dp),
                tint = when {
                    selected -> primary
                    ping != null && ping >= 0 -> pingColor(ping)
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        } else {
            Icon(Icons.Outlined.Dns, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name.trim(), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (isAuto(server)) {
                Text("По качеству соединения", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (favorite) {
            Icon(Icons.Default.Star, null, tint = Color(0xFFF5B301), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        when {
            pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            ping != null -> Text(
                if (ping >= 0) "$ping мс" else "таймаут",
                color = if (!light && ping >= 150) MaterialTheme.colorScheme.onSurfaceVariant else pingColor(ping),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.width(14.dp))
        if (selected) {
            Box(Modifier.size(24.dp).clip(CircleShape).background(primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
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
