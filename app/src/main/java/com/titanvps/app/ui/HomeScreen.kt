package com.titanvps.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.titanvps.app.ui.theme.Brand
import com.titanvps.app.ui.theme.BrandGradient
import com.titanvps.app.ui.theme.Connected
import com.titanvps.app.vpn.VpnState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(viewModel: MainViewModel, sub: Subscription, busy: Boolean, onConnect: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val pings by viewModel.pings.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Server?>(null) }

    LaunchedEffect(Unit) {
        viewModel.openUrl.collect { context.openUrl(it) }
    }

    if (showApps) {
        AppsScreen(viewModel) { showApps = false; viewModel.reconnectIfConnected() }
        return
    }

    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers) }
    var tab by rememberSaveable(sub.servers) {
        mutableStateOf(selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: groups.firstOrNull()?.first ?: Group.SERVERS)
    }
    val tabServers = groups.firstOrNull { it.first == tab }?.second ?: groups.firstOrNull()?.second.orEmpty()

    PullToRefreshBox(
        isRefreshing = busy,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(R.drawable.logo), null, Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("Titan VPS", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Обновить подписку")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Исключения приложений") },
                                leadingIcon = { Icon(Icons.Default.Apps, null) },
                                onClick = { menu = false; showApps = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Поддержка") },
                                leadingIcon = { Icon(Icons.Default.SupportAgent, null) },
                                onClick = { menu = false; context.openUrl(sub.info.supportUrl ?: BuildConfig.TELEGRAM_URL) },
                            )
                            DropdownMenuItem(
                                text = { Text("Сброс настроек") },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                onClick = { menu = false; confirmReset = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Выйти из аккаунта") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                onClick = { menu = false; viewModel.logout() },
                            )
                        }
                    }
                }
            }

            sub.info.announce?.let {
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = Brand.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                        Text(it, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    ConnectButton(state) {
                        when (state) {
                            is VpnState.Connected, VpnState.Connecting -> viewModel.disconnect()
                            VpnState.Disconnecting -> Unit
                            else -> onConnect()
                        }
                    }
                }
            }

            item { StatusBlock(state, selected, selected?.let { pings[it.id] }) }

            item { UsageCard(sub, bypass = tab == Group.BYPASS) { context.openUrl(BuildConfig.TELEGRAM_URL) } }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { context.openUrl(BuildConfig.TELEGRAM_URL) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Продлить", maxLines = 1) }
                    OutlinedButton(
                        onClick = viewModel::openCabinet,
                        enabled = !busy,
                        modifier = Modifier.weight(1f).height(48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text("Личный кабинет", maxLines = 1) }
                }
            }

            item {
                // Equal-width tabs so nothing gets clipped on narrow screens / big fonts.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    groups.forEach { (group, list) ->
                        TabChip(group.title, list.size, group == tab, Modifier.weight(1f)) { tab = group }
                    }
                    PingButton(pinging, viewModel::pingAll)
                }
            }

            items(tabServers, key = { it.id }) { s ->
                ServerCard(
                    server = s,
                    ping = pings[s.id],
                    pending = pinging && pings[s.id] == null,
                    selected = s.id == selected?.id,
                    onClick = { viewModel.select(s.id) },
                    onLongClick = { details = s },
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    details?.let { server ->
        ServerDetailsSheet(
            server = server,
            ping = pings[server.id],
            pinging = pinging,
            selected = server.id == selected?.id,
            onPing = { viewModel.pingOne(server.id) },
            onSelect = { viewModel.select(server.id); details = null },
            onDismiss = { details = null },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки?") },
            text = { Text("Исключения приложений и выбранный сервер вернутся к исходным. Ключ останется.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetSettings() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDetailsSheet(
    server: Server,
    ping: Long?,
    pinging: Boolean,
    selected: Boolean,
    onPing: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (flag != null) Text(flag, fontSize = 32.sp)
                Spacer(Modifier.width(12.dp))
                Text(name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Пинг", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    pinging && ping == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    ping != null -> PingDot(ping)
                    else -> Text("—")
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onPing, enabled = !pinging, modifier = Modifier.weight(1f).height(48.dp)) {
                    Icon(Icons.Default.Speed, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Пинг")
                }
                Button(onClick = onSelect, enabled = !selected, modifier = Modifier.weight(1f).height(48.dp)) {
                    Text(if (selected) "Выбран" else "Выбрать")
                }
            }
        }
    }
}

@Composable
private fun ConnectButton(state: VpnState, onClick: () -> Unit) {
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
            .size(200.dp)
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
            CircularProgressIndicator(Modifier.size(200.dp), color = color, strokeWidth = 3.dp)
        }
        Icon(Icons.Default.PowerSettingsNew, "Подключить", tint = color, modifier = Modifier.size(84.dp))
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
            val suffix = ping?.let { p -> if (p >= 0) " · $p ms" else " · таймаут" }.orEmpty()
            Text(it.name + suffix, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun TabChip(title: String, count: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Brand, shape)
                else Modifier.background(MaterialTheme.colorScheme.surface)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(title, maxLines = 1, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(CircleShape).background(if (selected) Brand else MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) { Text("$count", fontSize = 12.sp, color = Color.White) }
    }
}

@Composable
private fun PingButton(pinging: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !pinging, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinging) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Speed, "Пинг", Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerCard(server: Server, ping: Long?, pending: Boolean, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Connected.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(1.dp, Connected.copy(alpha = 0.6f), shape) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flag != null) Text(flag, fontSize = 26.sp)
        else Icon(Icons.Default.Public, null, tint = Brand, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Text(name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        when {
            pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            ping != null -> PingDot(ping)
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(28.dp).clip(CircleShape).background(Connected), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun PingDot(ms: Long) {
    val color = when {
        ms < 0 -> MaterialTheme.colorScheme.error
        ms < 150 -> Connected
        ms < 300 -> Color(0xFFF59E0B)
        else -> MaterialTheme.colorScheme.error
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(if (ms >= 0) "$ms" else "таймаут", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Regular servers are unlimited; only bypass servers are metered, so the traffic
 * counter is shown on the Обходы tab only.
 */
@Composable
private fun UsageCard(sub: Subscription, bypass: Boolean, onBuyTraffic: () -> Unit) {
    val info = sub.info
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (bypass) {
                Row {
                    Text("Трафик на обходы", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        if (info.totalBytes > 0) "${formatBytes(info.usedBytes)} / ${formatBytes(info.totalBytes)}"
                        else "${formatBytes(info.usedBytes)} / ∞"
                    )
                }
                if (info.totalBytes > 0) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (info.usedBytes.toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onBuyTraffic, modifier = Modifier.fillMaxWidth().height(42.dp)) {
                    Text("Докупить ГБ")
                }
            } else {
                Row {
                    Text("Трафик", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Безлимит", color = Connected, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row {
                Text("Подписка до", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatExpiry(info.expireAt))
            }
        }
    }
}
