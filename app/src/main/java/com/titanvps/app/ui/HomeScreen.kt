package com.titanvps.app.ui

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.lazy.LazyRow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(viewModel: MainViewModel, sub: Subscription, busy: Boolean, onConnect: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val pings by viewModel.pings.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    val pingErrors by viewModel.pingErrors.collectAsState()
    var menu by remember { mutableStateOf(false) }

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
                    Text(sub.info.title ?: "TitanVPS", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, "Обновить подписку")
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Меню") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
                        Text(it, Modifier.padding(14.dp), fontSize = 14.sp)
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

            item { UsageCard(sub) }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(groups, key = { it.first }) { (group, list) ->
                            TabChip(group.title, list.size, group == tab) { tab = group }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    PingButton(pinging, viewModel::pingAll)
                }
            }

            items(tabServers, key = { it.id }) { s ->
                ServerCard(
                    server = s,
                    ping = pings[s.id],
                    error = pingErrors[s.id],
                    pending = pinging && pings[s.id] == null,
                    selected = s.id == selected?.id,
                    onClick = { viewModel.select(s.id) },
                )
            }

            item {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { context.openUrl(sub.info.webPageUrl ?: BuildConfig.WEBSITE_URL) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Продлить") }
                    OutlinedButton(
                        onClick = { context.openUrl(sub.info.supportUrl ?: BuildConfig.TELEGRAM_URL) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Поддержка") }
                }
                Spacer(Modifier.height(16.dp))
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
private fun TabChip(title: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier
            .clip(shape)
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.surfaceVariant).border(1.dp, Brand, shape)
                else Modifier.background(MaterialTheme.colorScheme.surface)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
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
        if (pinging) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Speed, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Пинг", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ServerCard(server: Server, ping: Long?, error: String?, pending: Boolean, selected: Boolean, onClick: () -> Unit) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Connected.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(1.dp, Connected.copy(alpha = 0.6f), shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (flag != null) Text(flag, fontSize = 26.sp)
        else Icon(Icons.Default.Public, null, tint = Brand, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (error != null && (ping ?: 0) < 0) {
                Text(error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
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

/** Traffic limit applies only to bypass servers; regular ones are unlimited. */
@Composable
private fun UsageCard(sub: Subscription) {
    val info = sub.info
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
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
            Spacer(Modifier.height(6.dp))
            Text(
                "Лимит только для серверов «Обходы». Остальные серверы — без ограничений.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                Text("Подписка до", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatExpiry(info.expireAt))
            }
        }
    }
}

