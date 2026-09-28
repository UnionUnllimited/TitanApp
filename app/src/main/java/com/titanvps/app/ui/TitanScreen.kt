package com.titanvps.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.titanvps.app.R
import com.titanvps.app.ui.theme.BrandGradient
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.theme.Brand
import com.titanvps.app.ui.theme.Connected
import com.titanvps.app.vpn.VpnState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TitanScreen(viewModel: MainViewModel, onConnect: () -> Unit) {
    val subscription by viewModel.subscription.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            val sub = subscription
            if (sub == null) WelcomeScreen(busy) else HomeScreen(viewModel, sub, busy, onConnect)
        }
    }
}

@Composable
private fun WelcomeScreen(busy: Boolean) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(painterResource(R.drawable.logo), null, Modifier.size(128.dp))
        Spacer(Modifier.height(20.dp))
        Text("TitanVPS", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Войдите в личный кабинет через Telegram или почту и нажмите «Открыть в TitanVPS»",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
        if (busy) {
            CircularProgressIndicator()
        } else {
            GradientButton("Войти", Icons.AutoMirrored.Filled.Login) { context.openUrl(BuildConfig.WEBSITE_URL) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { context.openUrl(BuildConfig.TELEGRAM_URL) },
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
                Spacer(Modifier.width(8.dp))
                Text("Открыть Telegram-бот")
            }
        }
    }
}

@Composable
private fun GradientButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(27.dp))
            .background(BrandGradient)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(viewModel: MainViewModel, sub: Subscription, busy: Boolean, onConnect: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val pings by viewModel.pings.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    var showServers by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(36.dp))
            Spacer(Modifier.width(10.dp))
            Text(sub.info.title ?: "TitanVPS", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { viewModel.refresh() }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Обновить")
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

        sub.info.announce?.let {
            Spacer(Modifier.height(12.dp))
            Surface(shape = RoundedCornerShape(14.dp), color = Brand.copy(alpha = 0.15f), modifier = Modifier.fillMaxWidth()) {
                Text(it, Modifier.padding(14.dp), fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(40.dp))
        ConnectButton(state) {
            when (state) {
                is VpnState.Connected, VpnState.Connecting -> viewModel.disconnect()
                VpnState.Disconnecting -> Unit
                else -> onConnect()
            }
        }
        Spacer(Modifier.height(20.dp))
        StatusText(state)

        Spacer(Modifier.height(36.dp))
        val selected = sub.servers.firstOrNull { it.id == selectedId }
        Card(onClick = { viewModel.pingAll(); showServers = true }) {
            Icon(if (selected == null) Icons.Default.Bolt else Icons.Default.Public, null, tint = Brand)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Локация", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected?.name ?: "Автовыбор (самый быстрый)", fontWeight = FontWeight.Medium)
            }
            selected?.let { pings[it.id] }?.let { PingLabel(it); Spacer(Modifier.width(8.dp)) }
            Icon(Icons.Default.KeyboardArrowDown, null)
        }

        Spacer(Modifier.height(12.dp))
        UsageCard(sub)

        Spacer(Modifier.height(16.dp))
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
    }

    if (showServers) {
        ModalBottomSheet(onDismissRequest = { showServers = false }) {
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Локации", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::pingAll, enabled = !pinging) {
                            if (pinging) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Пинг") }
                        }
                    }
                }
                item {
                    ServerRow("Автовыбор (самый быстрый)", null, selectedId == null, Icons.Default.Bolt) {
                        viewModel.select(null); showServers = false
                    }
                }
                items(sub.servers, key = { it.id }) { s ->
                    ServerRow(s.name, pings[s.id], s.id == selectedId, Icons.Default.Public) {
                        viewModel.select(s.id); showServers = false
                    }
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
private fun StatusText(state: VpnState) {
    when (state) {
        is VpnState.Connected -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.since) {
                while (true) { now = System.currentTimeMillis(); delay(1000) }
            }
            Text("Защищено", color = Connected, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text("${state.serverName} · ${formatDuration(now - state.since)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        VpnState.Connecting -> Text("Подключение…", fontSize = 20.sp)
        VpnState.Disconnecting -> Text("Отключение…", fontSize = 20.sp)
        VpnState.Disconnected -> Text("Не подключено", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        is VpnState.Error -> Text(state.message, fontSize = 16.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

@Composable
private fun UsageCard(sub: Subscription) {
    val info = sub.info
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row {
                Text("Трафик", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Spacer(Modifier.height(12.dp))
            Row {
                Text("Подписка до", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatExpiry(info.expireAt))
            }
        }
    }
}

@Composable
private fun Card(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
private fun ServerRow(
    name: String,
    ping: Long?,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Brand)
        Spacer(Modifier.width(16.dp))
        Text(name, Modifier.weight(1f))
        ping?.let {
            PingLabel(it)
            Spacer(Modifier.width(12.dp))
        }
        if (selected) Icon(Icons.Default.Check, null, tint = Brand)
    }
}

@Composable
private fun PingLabel(ms: Long) {
    Text(
        if (ms > 0) "$ms мс" else "нет связи",
        fontSize = 13.sp,
        color = when {
            ms <= 0 -> MaterialTheme.colorScheme.error
            ms < 150 -> Connected
            ms < 300 -> Color(0xFFF59E0B)
            else -> MaterialTheme.colorScheme.error
        },
    )
}

private fun android.content.Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun formatDuration(ms: Long): String {
    val s = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

private fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1) "%.1f ГБ".format(gb) else "%.0f МБ".format(bytes / 1024.0 / 1024.0)
}

private fun formatExpiry(expireAt: Long): String {
    if (expireAt <= 0) return "бессрочно"
    val date = SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(expireAt * 1000))
    val days = TimeUnit.SECONDS.toDays(expireAt - System.currentTimeMillis() / 1000)
    return if (days >= 0) "$date ($days дн.)" else "$date (истекла)"
}
