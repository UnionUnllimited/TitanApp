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
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    var details by remember { mutableStateOf<Server?>(null) }

    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers) }
    var tab by rememberSaveable(sub.servers) {
        mutableStateOf(selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: groups.firstOrNull()?.first ?: Group.SERVERS)
    }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    val tabServers = groups.firstOrNull { it.first == tab }?.second ?: groups.firstOrNull()?.second.orEmpty()
    val shown = tabServers.filter { s ->
        (query.isBlank() || s.name.contains(query.trim(), ignoreCase = true)) &&
            when (filter) {
                Filter.ALL -> true
                Filter.AUTO -> "авто" in s.name.lowercase() || "auto" in s.name.lowercase()
                Filter.FAVORITES -> s.name in favorites
            }
    }

    PullToRefreshBox(isRefreshing = busy, onRefresh = { viewModel.refresh() }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            val connectSize = (if (wide) maxWidth * 0.25f else maxWidth * 0.46f).coerceIn(140.dp, 210.dp)
            val padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            val spacing = Arrangement.spacedBy(10.dp)

            val topItems: LazyListScope.() -> Unit = {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Image(painterResource(R.drawable.logo), null, Modifier.size(32.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Titan VPS", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, "Обновить подписку")
                        }
                    }
                }
                sub.info.announce?.let {
                    item {
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
                            Text(it, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ConnectButton(state, connectSize) {
                            when (state) {
                                is VpnState.Connected, VpnState.Connecting -> viewModel.disconnect()
                                VpnState.Disconnecting -> Unit
                                else -> onConnect()
                            }
                        }
                    }
                }
                item { StatusBlock(state, selected, selected?.let { pings[it.id] }) }
            }

            val serverItems: LazyListScope.() -> Unit = {
                item {
                    // Equal-width tabs so nothing gets clipped on narrow screens / big fonts.
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        groups.forEach { (group, list) ->
                            TabChip(group.title, list.size, group == tab, Modifier.weight(1f)) { tab = group }
                        }
                        PingButton(pinging, viewModel::pingAll)
                    }
                }
                if (tab == Group.BYPASS) {
                    item { InfoBanner("Для ограничений мобильного интернета") }
                    item { RemainingTrafficCard(sub) { context.openUrl(BuildConfig.TELEGRAM_URL) } }
                }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        placeholder = { Text(if (tab == Group.BYPASS) "Найти обход" else "Найти сервер") },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Filter.entries.forEach { f ->
                            FilterChip(f.title, f == filter, Modifier.weight(1f)) { filter = f }
                        }
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
                        onClick = { viewModel.select(s.id) },
                        onLongClick = { details = s },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }

            if (wide) {
                // Tablets / unfolded foldables / landscape: controls left, locations right.
                Row(Modifier.widthIn(max = 1200.dp).fillMaxSize().align(Alignment.TopCenter)) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = padding, verticalArrangement = spacing) { topItems() }
                    LazyColumn(Modifier.weight(1.2f).fillMaxHeight(), contentPadding = padding, verticalArrangement = spacing) { serverItems() }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxHeight().widthIn(max = 640.dp).fillMaxWidth().align(Alignment.TopCenter),
                    contentPadding = padding,
                    verticalArrangement = spacing,
                ) {
                    topItems()
                    serverItems()
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
            onPing = { viewModel.pingOne(server.id) },
            onFavorite = { viewModel.toggleFavorite(server.name) },
            onSelect = { viewModel.select(server.id); details = null },
            onDismiss = { details = null },
        )
    }
}

@Composable
private fun ConnectButton(state: VpnState, size: Dp, onClick: () -> Unit) {
    val color by animateColorAsState(
        when (state) {
            is VpnState.Connected -> Connected
            VpnState.Connecting, VpnState.Disconnecting -> Brand.copy(alpha = 0.6f)
            else -> Brand
        },
        label = "connect",
    )
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .then(
                if (state is VpnState.Connected || state == VpnState.Connecting || state == VpnState.Disconnecting)
                    Modifier.border(3.dp, color, CircleShape)
                else Modifier.border(3.dp, BrandGradient, CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state == VpnState.Connecting || state == VpnState.Disconnecting) {
            CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 3.dp)
        }
        Icon(Icons.Default.PowerSettingsNew, "Подключить", tint = color, modifier = Modifier.size(size * 0.42f))
    }
}

@Composable
private fun StatusBlock(state: VpnState, server: Server?, ping: Long?) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        when (state) {
            is VpnState.Connected -> {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(state.since) {
                    while (true) { now = System.currentTimeMillis(); delay(1000) }
                }
                Text("Подключено", color = Connected, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(formatDuration(now - state.since), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            VpnState.Connecting -> Text("Подключение…", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            VpnState.Disconnecting -> Text("Отключение…", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            VpnState.Disconnected -> Text("Не подключено", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is VpnState.Error -> Text(state.message, fontSize = 15.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        server?.let {
            Spacer(Modifier.height(4.dp))
            val suffix = ping?.let { p -> if (p >= 0) " · $p мс" else " · таймаут" }.orEmpty()
            Text(it.name + suffix, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "Остаток трафика" card from the mockup; only bypass servers are metered. */
@Composable
private fun RemainingTrafficCard(sub: Subscription, onBuy: () -> Unit) {
    val info = sub.info
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Остаток трафика", fontWeight = FontWeight.SemiBold)
                if (info.totalBytes > 0) {
                    Text(formatBytes((info.totalBytes - info.usedBytes).coerceAtLeast(0)), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("из ${formatBytes(info.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("Безлимит", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                }
            }
            Button(onClick = onBuy, shape = RoundedCornerShape(12.dp)) { Text("Докупить ГБ") }
        }
        if (info.totalBytes > 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { ((info.totalBytes - info.usedBytes).toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Трафик расходуется только на обходах", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TabChip(title: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Text(
            title,
            modifier = Modifier.weight(1f, fill = false),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier
                .widthIn(min = 24.dp)
                .clip(CircleShape)
                .background(if (selected) Color.White.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 7.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) { Text("$count", fontSize = 12.sp, color = fg, maxLines = 1, softWrap = false) }
    }
}

@Composable
private fun FilterChip(title: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
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
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !pinging, onClick = onClick)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (pinging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Speed, "Пинг", Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(
    server: Server,
    ping: Long?,
    pending: Boolean,
    selected: Boolean,
    favorite: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(1.5.dp, primary, shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flag != null) Text(flag, fontSize = 24.sp)
        else Icon(
            Icons.Default.SignalCellularAlt, null,
            tint = ping?.let { pingColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (favorite) {
            Icon(Icons.Default.Star, null, tint = Color(0xFFF5B301), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        when {
            pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            ping != null -> Text(if (ping >= 0) "$ping мс" else "таймаут", color = pingColor(ping), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(10.dp))
        // Radio mark as in the mockup.
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
                Button(onClick = onSelect, enabled = !selected, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (selected) "Выбран" else "Выбрать")
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
