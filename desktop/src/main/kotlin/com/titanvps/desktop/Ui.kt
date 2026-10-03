@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.titanvps.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.desktop.ServerGroups.Group
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Default.Home),
    SUBSCRIPTION("Подписка", Icons.Default.WorkspacePremium),
    SETTINGS("Настройки", Icons.Default.Settings),
}

@Composable
fun App(state: AppState) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); state.message = null }
    }
    var tab by remember { mutableStateOf(Tab.HOME) }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val sub = state.subscription
        if (sub == null) {
            Welcome(state)
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(12.dp))
                    Image(painterResource("logo.png"), null, Modifier.size(36.dp))
                    Spacer(Modifier.height(16.dp))
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.title, fontSize = 12.sp) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (tab) {
                        Tab.HOME -> Home(state, sub) { tab = Tab.SUBSCRIPTION }
                        Tab.SUBSCRIPTION -> SubscriptionPage(state, sub)
                        Tab.SETTINGS -> SettingsPage(state)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

// ------------------------------------------------------------------ welcome

@Composable
private fun Welcome(state: AppState) {
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var key by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(painterResource("logo.png"), null, Modifier.size(110.dp))
            Spacer(Modifier.height(12.dp))
            Text("Titan VPS", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Вставьте ключ подписки из бота или личного кабинета", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("https://api1.titanvps.su/…") })
            Spacer(Modifier.height(10.dp))
            OutlinedButton({ clipboard.getText()?.text?.trim()?.let { key = it } }, Modifier.fillMaxWidth().height(46.dp)) {
                Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Вставить из буфера")
            }
            Spacer(Modifier.height(10.dp))
            Button({ state.activate(key) }, Modifier.fillMaxWidth().height(48.dp), enabled = key.isNotBlank() && !state.busy) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("Подключить", fontWeight = FontWeight.SemiBold)
            }
            TextButton({ uri.openUri(Config.TELEGRAM_URL) }) { Text("Нет ключа? Получить в боте") }
        }
    }
}

// ------------------------------------------------------------------ home

@Composable
private fun Home(state: AppState, sub: Subscription, openSubscription: () -> Unit) {
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers) }
    var page by remember(sub.servers) {
        mutableStateOf(state.selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: Group.SERVERS)
    }
    if (groups[page].isNullOrEmpty()) page = Group.SERVERS

    val controls: @Composable ColumnScope.() -> Unit = {
        Header(state)
        SubscriptionCard(sub, openSubscription)
        PowerSwitch(state)
        Status(state)
    }
    val list: LazyListScope.() -> Unit = {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { (g, items) -> GroupTab(g, items.size, g == page, Modifier.weight(1f)) { page = g } }
                }
                PingButton(state.pinging) { state.pingAll() }
            }
        }
        item {
            Text(
                if (page == Group.SERVERS) "Безлимит на обычных серверах"
                else "Для мобильного интернета с белыми списками (раздача с телефона). Расходуют ГБ" +
                    (if (sub.info.totalBytes > 0) " · осталось ${formatBytes(sub.info.remainingBytes)} из ${formatBytes(sub.info.totalBytes)}" else ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp),
            )
        }
        items(groups[page].orEmpty(), key = { it.id }) { s ->
            ServerRow(
                server = s,
                ping = state.pings[s.id],
                pending = state.pinging && state.pings[s.id] == null,
                selected = s.id == state.selected?.id,
                connected = state.vpn is VpnState.Connected && s.id == state.selected?.id,
                limited = page == Group.BYPASS,
            ) { state.select(s.id) }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 860.dp) {
            Row(Modifier.widthIn(max = 1300.dp).fillMaxSize().padding(horizontal = 8.dp)) {
                Column(
                    Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = controls,
                )
                LazyColumn(
                    Modifier.weight(1.1f).fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = list,
                )
            }
        } else {
            LazyColumn(
                Modifier.widthIn(max = 640.dp).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Column(verticalArrangement = Arrangement.spacedBy(12.dp), content = controls) }
                list()
            }
        }
    }
}

@Composable
private fun Header(state: AppState) {
    val dark = isDark(state.theme)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Titan VPS", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        SquareButton(Icons.Default.Refresh, "Обновить подписку", spinning = state.busy) { state.refresh() }
        Spacer(Modifier.width(8.dp))
        SquareButton(if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode, "Тема") {
            state.setTheme(if (dark) "light" else "dark")
        }
    }
}

@Composable
private fun SquareButton(icon: ImageVector, description: String, spinning: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !spinning, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (spinning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else Icon(icon, description, Modifier.size(20.dp))
    }
}

@Composable
private fun SubscriptionCard(sub: Subscription, onOpen: () -> Unit) {
    val now = System.currentTimeMillis() / 1000
    val active = sub.info.expireAt <= 0 || sub.info.expireAt > now
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.clickable(onClick = onOpen).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(Brand.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.WorkspacePremium, null, tint = if (active) Brand else Red, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (active) "Подписка активна" else "Подписка истекла", fontWeight = FontWeight.SemiBold)
                Text(expiryText(sub.info.expireAt), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun expiryText(expireAt: Long): String =
    if (expireAt <= 0) "Бессрочно" else "До " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(expireAt * 1000))

/** Выкл (red while off) | Вкл (green while on), spinner knob while connecting. */
@Composable
private fun PowerSwitch(state: AppState) {
    val vpn = state.vpn
    val on = vpn is VpnState.Connected
    val busy = vpn == VpnState.Connecting || vpn == VpnState.Disconnecting
    val off = !on && !busy
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(
        Modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(29.dp))
            .background(if (busy) Brush.horizontalGradient(listOf(track, Brand.copy(alpha = 0.2f), track)) else Brush.horizontalGradient(listOf(track, track)))
            .padding(5.dp),
    ) {
        Row(Modifier.fillMaxSize()) {
            val offBg by animateColorAsState(if (off) Red.copy(alpha = 0.14f) else Color.Transparent)
            val onBg by animateColorAsState(if (on) Green.copy(alpha = 0.16f) else Color.Transparent)
            Half(Modifier.weight(1f), offBg, if (off) Red else null, "Выкл", Icons.Default.PowerSettingsNew,
                if (off) Red else MaterialTheme.colorScheme.onSurface) { state.disconnect() }
            Half(Modifier.weight(1f), onBg, if (on) Green else null, "Вкл", Icons.Outlined.Shield,
                if (on) Green else if (busy) Brand else MaterialTheme.colorScheme.onSurface) { if (!on && !busy) state.connect() }
        }
        if (busy) {
            Box(Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = Brand)
            }
        }
    }
}

@Composable
private fun Half(modifier: Modifier, bg: Color, border: Color?, text: String, icon: ImageVector, tint: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(25.dp)
    Row(
        modifier.fillMaxHeight().clip(shape).background(bg)
            .then(if (border != null) Modifier.border(1.5.dp, border, shape) else Modifier)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
private fun Status(state: AppState) {
    val vpn = state.vpn
    val server = state.selected
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val (title, color) = when (vpn) {
            is VpnState.Connected -> "Подключено" to Green
            VpnState.Connecting -> "Подключаемся" to Brand
            VpnState.Disconnecting -> "Отключаемся" to MaterialTheme.colorScheme.onSurface
            else -> "Не подключено" to MaterialTheme.colorScheme.onSurface
        }
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = color)
        val name = server?.let { ServerGroups.splitFlag(it.name).second }
        when (vpn) {
            is VpnState.Connected -> {
                val ping = server?.let { state.pings[it.id] }?.takeIf { it >= 0 }
                Text((name ?: "") + (ping?.let { " · $it мс" } ?: ""), fontSize = 14.sp, maxLines = 1, modifier = Modifier.basicMarquee())
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(vpn.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                val s = (now - vpn.since) / 1000
                Text("%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Браузеры и большинство программ идут через VPN", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            VpnState.Connecting -> Text("Устанавливаем соединение", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            is VpnState.Error -> Text(vpn.message, fontSize = 13.sp, color = Red, textAlign = TextAlign.Center)
            else -> Text(name ?: "Выберите сервер", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun GroupTab(group: Group, count: Int, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val color = if (selected) Brand else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.clip(shape).background(if (selected) Brand.copy(alpha = 0.08f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Brand.copy(alpha = 0.35f), shape) else Modifier)
            .clickable(onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (group == Group.SERVERS) Icons.Outlined.Storage else Icons.Default.Shuffle, null, Modifier.size(15.dp), tint = color)
        Spacer(Modifier.width(4.dp))
        Text(group.title, color = color, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(4.dp))
        Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (selected) Brand else MaterialTheme.colorScheme.outlineVariant).padding(horizontal = 5.dp, vertical = 1.dp)) {
            Text("$count", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PingButton(pinging: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(enabled = !pinging, onClick = onClick).heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinging) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Speed, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Пинг", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Country code in a circle (Windows can't draw flag emoji); АВТО gets a bolt. */
@Composable
private fun FlagCircle(server: Server, size: Dp) {
    val (flag, name) = remember(server.name) { ServerGroups.splitFlag(server.name) }
    val code = ServerGroups.countryCode(flag)
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            code != null -> Text(code.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand)
            "авто" in name.lowercase() -> Icon(Icons.Default.Bolt, null, Modifier.size(size * 0.55f), tint = Brand)
            else -> Icon(Icons.Outlined.Dns, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerRow(server: Server, ping: Long?, pending: Boolean, selected: Boolean, connected: Boolean, limited: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val accent = if (connected) Green else Brand
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface)
            .then(if (selected) Modifier.border(1.5.dp, accent, shape) else Modifier)
            .clickable(onClick = onClick).heightIn(min = 54.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagCircle(server, 32.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(ServerGroups.splitFlag(server.name).second, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.basicMarquee())
            Text(if (limited) "Лимитный · расходует ГБ" else "Безлимит", fontSize = 11.sp, color = if (limited) Yellow else Green)
        }
        when {
            pending -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            ping != null -> Text(if (ping >= 0) "$ping мс" else "таймаут", color = pingColor(ping), fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        when {
            connected -> Box(Modifier.size(24.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            selected -> Box(Modifier.size(24.dp).border(2.dp, accent, CircleShape), contentAlignment = Alignment.Center) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(accent))
            }
            else -> Box(Modifier.size(24.dp).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
        }
    }
}

// ------------------------------------------------------------------ subscription

@Composable
private fun SubscriptionPage(state: AppState, sub: Subscription) {
    val uri = LocalUriHandler.current
    val info = sub.info
    val now = System.currentTimeMillis() / 1000
    val active = info.expireAt <= 0 || info.expireAt > now
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Подписка", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (active) Green else Red))
                    Spacer(Modifier.width(8.dp))
                    Text(if (active) "Подписка активна" else "Подписка истекла", color = if (active) Green else Red, fontSize = 13.sp)
                }
                Text(expiryText(info.expireAt).replaceFirstChar { it.lowercase() }, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (info.expireAt > 0) Text("Осталось ${TimeUnit.SECONDS.toDays(info.expireAt - now).coerceAtLeast(0)} дн.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Card {
                Text("Трафик на обходах", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (info.totalBytes > 0) {
                    Text("${formatBytes(info.remainingBytes)} из ${formatBytes(info.totalBytes)}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (info.remainingBytes.toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else Text("Безлимит", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("На обычных серверах — безлимит", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Обходы — серверы для мобильного интернета, когда оператор пропускает только разрешённые сайты " +
                        "(белые списки) и обычные серверы не работают. На компьютере нужны только при раздаче интернета с телефона. " +
                        "Гигабайты списываются, пока вы подключены к обходу. Закончились ГБ — докупите в личном кабинете.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button({ uri.openUri(info.webPageUrl ?: Config.TELEGRAM_URL) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Outlined.AccountCircle, null); Spacer(Modifier.width(8.dp)); Text("Личный кабинет", fontWeight = FontWeight.SemiBold)
            }
            Card {
                Text("В личном кабинете", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                listOf(
                    Icons.Outlined.Autorenew to "Продление подписки",
                    Icons.Outlined.DataUsage to "Докупка ГБ для обходов",
                    Icons.Outlined.Devices to "Управление устройствами",
                    Icons.Outlined.Loyalty to "Промокоды и история платежей",
                    Icons.Outlined.SupportAgent to "Поддержка",
                ).forEach { (icon, text) ->
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(18.dp), tint = Brand); Spacer(Modifier.width(10.dp)); Text(text, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsPage(state: AppState) {
    val uri = LocalUriHandler.current
    var confirmLogout by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Настройки", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Card {
                Text("Оформление", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
                    listOf("system" to "Системная", "light" to "Светлая", "dark" to "Тёмная").forEach { (v, t) ->
                        val sel = state.theme == v
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) Brand else Color.Transparent)
                                .clickable { state.setTheme(v) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(t, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp) }
                    }
                }
            }
            Card {
                Text("Режим подключения", fontWeight = FontWeight.SemiBold)
                Text(
                    "Системный прокси: через VPN идут браузеры и большинство программ. Игры и часть приложений его не используют — " +
                        "для них появится режим TUN. При выходе из приложения прокси выключается.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Card {
                SettingsRow("Обновить подписку") { state.refresh() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("Папка с логами") { runCatching { java.awt.Desktop.getDesktop().open(AppPaths.logDir) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("Поддержка") { uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow(if (confirmLogout) "Точно выйти? Нажмите ещё раз" else "Выйти из аккаунта", danger = true) {
                    if (confirmLogout) state.logout() else confirmLogout = true
                }
            }
            Text("Версия ${System.getProperty("jpackage.app-version") ?: "dev"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsRow(title: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = if (danger) Red else MaterialTheme.colorScheme.onSurface)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
