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
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
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
    val dark = isDark(state.theme)
    val bg = if (dark) Brush.linearGradient(listOf(Color(0xFF0A0F1E), Color(0xFF0E1630), Color(0xFF0B1226)))
    else Brush.linearGradient(listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background))
    Box(Modifier.fillMaxSize().background(bg)) {
        val sub = state.subscription
        if (sub == null) {
            Welcome(state)
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 1000.dp
                Row(Modifier.fillMaxSize()) {
                    Sidebar(tab, compact, state) { tab = it }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        when (tab) {
                            Tab.HOME -> Home(state, sub) { tab = Tab.SUBSCRIPTION }
                            Tab.SUBSCRIPTION -> SubscriptionPage(state, sub)
                            Tab.SETTINGS -> SettingsPage(state)
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        if (state.updateDialog) UpdateDialog(state)
        if (state.needAdmin) AdminDialog(state)
    }
}

@Composable
private fun AdminDialog(state: AppState) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { state.cancelAdmin() },
        title = { Text("Нужны права администратора") },
        text = {
            Text(
                "Режим «Весь компьютер» создаёт виртуальную сетевую карту — для этого Windows требует права администратора. " +
                    "Приложение перезапустится, Windows покажет запрос — нажмите «Да»."
            )
        },
        confirmButton = { androidx.compose.material3.Button(onClick = { state.restartAsAdmin() }) { Text("Перезапустить") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { state.cancelAdmin() }) { Text("Системный прокси") } },
    )
}

/** Programs that bypass the VPN in TUN mode: running ones + any .exe picked by hand. */
@Composable
private fun AppsPage(state: AppState, onBack: () -> Unit) {
    var apps by remember { mutableStateOf<List<RunningApps.App>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RunningApps.list() } }
    val excluded = state.excludedApps
    // Excluded ones first (also those not running now), then the running ones.
    val rows = (excluded.sortedBy { it.lowercase() }.map { e -> apps.firstOrNull { it.exe.equals(e, true) } ?: RunningApps.App(e, "") } +
        apps.filter { a -> excluded.none { it.equals(a.exe, true) } })
        .filter { query.isBlank() || query.trim().lowercase() in it.exe.lowercase() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 700.dp).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Text("Раздельное туннелирование", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                "Отмеченные программы идут в интернет напрямую, мимо VPN. Остальные — через VPN. " +
                    "Изменения применятся при выходе с этой страницы.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val fd = java.awt.FileDialog(null as java.awt.Frame?, "Выберите программу", java.awt.FileDialog.LOAD)
                    fd.setFilenameFilter { _, name -> name.endsWith(".exe", true) }
                    fd.file = "*.exe"
                    fd.isVisible = true
                    fd.file?.let { state.setExcluded(it, true) }
                }) { Text("Добавить .exe") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(rows, key = { it.exe.lowercase() }) { app ->
                    val on = excluded.any { it.equals(app.exe, true) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                            .clickable { state.setExcluded(app.exe, !on) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(app.exe, fontWeight = FontWeight.Medium)
                            if (app.path.isNotEmpty()) Text(app.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        androidx.compose.material3.Switch(checked = on, onCheckedChange = { state.setExcluded(app.exe, it) })
                    }
                }
            }
        }
    }
}

/** Logo + "Titan VPS", pill-shaped sections, support at the bottom (icons only when narrow). */
@Composable
private fun Sidebar(tab: Tab, compact: Boolean, state: AppState, onTab: (Tab) -> Unit) {
    val uri = LocalUriHandler.current
    Column(
        Modifier.width(if (compact) 76.dp else 210.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(painterResource("logo.png"), null, Modifier.size(if (compact) 40.dp else 64.dp))
        if (!compact) {
            Spacer(Modifier.height(8.dp))
            Text("Titan VPS", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(28.dp))
        Tab.entries.forEach { t ->
            SidebarItem(t.icon, t.title, t == tab, compact) { onTab(t) }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        SidebarItem(Icons.Outlined.SupportAgent, "Поддержка", false, compact) {
            uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL)
        }
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, title: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) Brush.horizontalGradient(listOf(Brand, Color(0xFF2F7BFF))) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
            .clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = if (compact) 0.dp else 16.dp),
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, title, Modifier.size(22.dp), tint = color)
        if (!compact) {
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = color)
        }
    }
}

@Composable
private fun UpdateDialog(state: AppState) {
    val u = state.update
    val busy = u == UpdateState.ConnectingVpn || u == UpdateState.Checking || u is UpdateState.Downloading || u == UpdateState.Installing
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) state.dismissUpdate() },
        title = { Text("Обновление приложения") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    when (u) {
                        UpdateState.ConnectingVpn -> "Включаем VPN…"
                        UpdateState.Checking -> "Проверяем…"
                        UpdateState.UpToDate -> "У вас последняя версия"
                        is UpdateState.Available -> "Доступна новая версия ${u.release.name}. Приложение закроется, установит её и откроется снова."
                        is UpdateState.Downloading -> "Скачиваем обновление… ${(u.progress * 100).toInt()}%"
                        UpdateState.Installing -> "Устанавливаем…"
                        is UpdateState.Error -> u.message
                        UpdateState.Idle -> ""
                    }
                )
                if (u is UpdateState.Downloading) androidx.compose.material3.LinearProgressIndicator(progress = { u.progress }, modifier = Modifier.fillMaxWidth())
                else if (busy) androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            when (u) {
                is UpdateState.Available -> androidx.compose.material3.Button(onClick = { state.installUpdate() }) { Text("Обновить") }
                is UpdateState.Error -> androidx.compose.material3.TextButton(onClick = { state.checkUpdate() }) { Text("Повторить") }
                else -> {}
            }
        },
        dismissButton = {
            if (!busy) androidx.compose.material3.TextButton(onClick = { state.dismissUpdate() }) {
                Text(if (u is UpdateState.Available) "Позже" else "Закрыть")
            }
        },
    )
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

private enum class Sort(val title: String) { RECOMMENDED("Рекомендуемые"), PING("По пингу"), NAME("По названию") }

@Composable
private fun Home(state: AppState, sub: Subscription, openSubscription: () -> Unit) {
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers) }
    var page by remember(sub.servers) {
        mutableStateOf(state.selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: Group.SERVERS)
    }
    if (groups[page].isNullOrEmpty()) page = Group.SERVERS
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.RECOMMENDED) }
    val servers = groups[page].orEmpty()
        .filter { query.isBlank() || query.trim().lowercase() in it.name.lowercase() }
        .let { list ->
            when (sort) {
                Sort.RECOMMENDED -> list
                Sort.PING -> list.sortedBy { s -> state.pings[s.id]?.takeIf { it >= 0 } ?: Long.MAX_VALUE }
                Sort.NAME -> list.sortedBy { ServerGroups.splitFlag(it.name).second.lowercase() }
            }
        }

    val toolbar: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    groups.forEach { (g, items) -> GroupTab(g, items.size, g == page, Modifier.weight(1f)) { page = g } }
                }
                PingButton(state.pinging) { state.pingAll(servers.map { it.id }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SearchField(query, { query = it }, Modifier.weight(1f))
                SortMenu(sort) { sort = it }
            }
            Text(
                if (page == Group.SERVERS) "Безлимит на обычных серверах"
                else "Серверы для мобильного интернета с белыми списками. Расходуют ГБ" +
                    (if (sub.info.totalBytes > 0) " · осталось ${formatBytes(sub.info.remainingBytes)} из ${formatBytes(sub.info.totalBytes)}" else ""),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
    val row: @Composable (Server) -> Unit = { s ->
        ServerRow(
            server = s,
            ping = state.pings[s.id],
            measuring = s.id in state.measuring,
            selected = s.id == state.selected?.id,
            connected = state.vpn is VpnState.Connected && s.id == state.selected?.id,
            limited = groups[Group.BYPASS]?.any { it.id == s.id } == true,
            onPing = { state.pingOne(s.id) },
        ) { state.select(s.id) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val panelWidth = if (maxWidth >= 1050.dp) 440.dp else 380.dp
        if (maxWidth >= 820.dp) {
            Row(Modifier.fillMaxSize().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ControlPanel(state, sub, openSubscription, Modifier.width(panelWidth).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    toolbar()
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                        items(servers, key = { it.id }) { row(it) }
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { ControlPanel(state, sub, openSubscription, Modifier.fillMaxWidth(), scrollable = false) }
                item { toolbar() }
                items(servers, key = { it.id }) { row(it) }
            }
        }
    }
}

/** Title, subscription, the big power button with status, selected server, features. */
@Composable
private fun ControlPanel(state: AppState, sub: Subscription, openSubscription: () -> Unit, modifier: Modifier, scrollable: Boolean = true) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
        // Not scrollable inside the narrow layout's list: nested vertical scrolling crashes Compose.
        Column(
            (if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("Titan VPS", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text("Быстрый. Стабильный. Без ограничений.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SubscriptionCard(sub, openSubscription)
            AccountBanner(state)
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BigPowerButton(state)
                    Status(state)
                    PowerSwitch(state)
                }
            }
            state.selected?.let { SelectedServer(it, ServerGroups.groupOf(it, sub.servers) == Group.BYPASS) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Feature(Icons.Default.Bolt, "Высокая\nскорость")
                Feature(Icons.Outlined.Shield, "Стабильное\nсоединение")
            }
        }
    }
}

/** Round power button with a glow: blue when off, spinning while connecting, green when on. */
@Composable
private fun BigPowerButton(state: AppState) {
    val vpn = state.vpn
    val on = vpn is VpnState.Connected
    val busy = vpn == VpnState.Connecting || vpn == VpnState.Disconnecting
    val color by animateColorAsState(if (on) Green else Brand)
    Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(170.dp).background(Brush.radialGradient(listOf(color.copy(alpha = 0.35f), Color.Transparent)), CircleShape))
        Box(
            Modifier.size(128.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.55f))))
                .border(3.dp, color.copy(alpha = 0.9f), CircleShape)
                .clickable(enabled = !busy) { if (on) state.disconnect() else state.connect() },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(54.dp), strokeWidth = 4.dp, color = Color.White)
            else Icon(Icons.Default.PowerSettingsNew, if (on) "Выключить" else "Включить", Modifier.size(58.dp), tint = Color.White)
        }
    }
}

@Composable
private fun SelectedServer(server: Server, limited: Boolean) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Выбранный сервер", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlagCircle(server, 28.dp)
                Spacer(Modifier.width(10.dp))
                MarqueeText(ServerGroups.splitFlag(server.name).second, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Badge(if (limited) "Лимитный" else "Безлимит", if (limited) Yellow else Green)
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = Brand)
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).heightIn(min = 44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск сервера…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.text.BasicTextField(
                value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Brand),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Close, "Очистить", Modifier.size(16.dp).clickable { onChange("") }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SortMenu(sort: Sort, onSort: (Sort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface).clickable { open = true }
                .heightIn(min = 44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Сортировка: ", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sort.title, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            Sort.entries.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(s.title) }, onClick = { onSort(s); open = false })
            }
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
                MarqueeText((name ?: "") + (ping?.let { " · $it мс" } ?: ""), fontSize = 14.sp, fontWeight = FontWeight.Normal)
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(vpn.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
                val s = (now - vpn.since) / 1000
                Text("%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (state.mode == "tun") "Весь трафик компьютера идёт через VPN" else "Браузеры и большинство программ идут через VPN",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        // Windows can't draw flag emoji: real flag images (flag-icons, added in CI).
        val flagRes = code?.lowercase()?.let { "flags/$it.svg" }
            ?.takeIf { Thread.currentThread().contextClassLoader.getResource(it) != null }
        when {
            flagRes != null -> Image(painterResource(flagRes), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            code != null -> Text(code.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Brand)
            "авто" in name.lowercase() -> Icon(Icons.Default.Bolt, null, Modifier.size(size * 0.55f), tint = Brand)
            else -> Icon(Icons.Outlined.Dns, null, Modifier.size(size * 0.5f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerRow(
    server: Server, ping: Long?, measuring: Boolean, selected: Boolean, connected: Boolean, limited: Boolean,
    onPing: () -> Unit, onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val accent = if (connected) Green else Brand
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) accent.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
            .then(if (selected) Modifier.border(1.5.dp, accent, shape) else Modifier)
            .clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FlagCircle(server, 34.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            MarqueeText(ServerGroups.splitFlag(server.name).second, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(if (limited) "Лимитный · расходует ГБ" else "Безлимит", fontSize = 11.sp, color = if (limited) Yellow else Green)
        }
        Spacer(Modifier.width(10.dp))
        // Click the ping to re-measure just this server (like Happ).
        Box(
            Modifier.heightIn(min = 36.dp).widthIn(min = 36.dp).clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !measuring, onClick = onPing).padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ping != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (measuring) { CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp); Spacer(Modifier.width(4.dp)) }
                    Row(Modifier.graphicsLayer { alpha = if (measuring) 0.5f else 1f }, verticalAlignment = Alignment.CenterVertically) {
                        SignalBars(ping)
                        Spacer(Modifier.width(6.dp))
                        Text(if (ping >= 0) "$ping мс" else "таймаут", color = pingColor(ping), fontSize = 13.sp)
                    }
                }
                measuring -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Default.Speed, "Пинг", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(10.dp))
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

/** Three bars coloured like the ping: 3 green, 2 yellow, 1 red. */
@Composable
private fun SignalBars(ping: Long) {
    val color = pingColor(ping)
    val level = when { ping < 0 -> 0; ping < 500 -> 3; ping < 1500 -> 2; else -> 1 }
    Row(Modifier.height(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(6.dp, 10.dp, 14.dp).forEachIndexed { i, h ->
            Box(Modifier.width(3.dp).height(h).clip(RoundedCornerShape(1.dp)).background(if (i < level) color else color.copy(alpha = 0.25f)))
        }
    }
}

/** One line; only a name that doesn't fit scrolls (after a pause) with faded edges. */
@Composable
private fun MarqueeText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: FontWeight, modifier: Modifier = Modifier) {
    var overflow by remember(text) { mutableStateOf(false) }
    Text(
        text,
        modifier.then(
            if (overflow) Modifier
                .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 12.dp.toPx().coerceAtMost(size.width / 4)
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, fade / size.width to Color.Black,
                            1f - fade / size.width to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                    )
                }
                .basicMarquee(initialDelayMillis = 2000, repeatDelayMillis = 2500)
            else Modifier
        ),
        fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow) overflow = true },
    )
}

// ------------------------------------------------------------------ subscription

/** Devices that used this key (from the bot), right on the subscription page. */
@Composable
private fun DevicesCard(state: AppState) {
    LaunchedEffect(Unit) { state.loadAccount() }
    val info = state.account
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Мои устройства", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (info != null && info.devicesEnabled) {
                Text(
                    "${info.devices.size}" + if (info.deviceLimit > 0) " из ${info.deviceLimit}" else "",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (state.accountLoading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.Refresh, "Обновить", Modifier.size(18.dp).clickable { state.loadAccount() }, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        when {
            info == null && !state.accountLoading -> Text("Не удалось загрузить список. Нажмите ↻, чтобы повторить", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info == null -> {}
            !info.devicesEnabled -> Text("Список устройств сейчас недоступен", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            info.devices.isEmpty() -> Text("Пока нет подключённых устройств", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> info.devices.forEach { d ->
                val os = d.os.lowercase()
                val icon = when {
                    "ios" in os || "iphone" in d.model.lowercase() -> Icons.Outlined.PhoneIphone
                    "android" in os -> Icons.Outlined.PhoneAndroid
                    "windows" in os || "mac" in os || "linux" in os -> Icons.Outlined.Computer
                    else -> Icons.Outlined.DevicesOther
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(Brand.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Icon(icon, null, Modifier.size(20.dp), tint = Brand)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.model.ifEmpty { "Устройство" }, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(listOf(d.os, d.osVersion).filter { it.isNotEmpty() }.joinToString(" "), Account.appName(d.app))
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Account.lastSeen(d.lastSeen)?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (info != null && info.devices.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Чтобы отвязать лишнее устройство, напишите в поддержку.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Blocked account / maintenance, from the bot; click → support. */
@Composable
private fun AccountBanner(state: AppState) {
    val uri = LocalUriHandler.current
    val text = when (state.account?.status) {
        "BLOCKED" -> "Аккаунт заблокирован. Напишите в поддержку"
        "MAINTENANCE" -> "Идут технические работы. Подключение может не работать"
        else -> return
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Red.copy(alpha = 0.14f))
            .clickable { uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL) }.padding(14.dp),
    ) { Text(text, color = Red, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

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
                        "(белые списки) и обычные серверы не работают. Работают и по Wi‑Fi, например при раздаче интернета с телефона. " +
                        "Гигабайты списываются, пока вы подключены к обходу. Закончились ГБ — докупите в личном кабинете.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DevicesCard(state)
            Button({ uri.openUri(state.openCabinetUrl()) }, Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(14.dp)) {
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
    var showApps by remember { mutableStateOf(false) }
    if (showApps) { AppsPage(state) { showApps = false; state.applyExclusions() }; return }
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
                                .clickable { state.changeTheme(v) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(t, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp) }
                    }
                }
            }
            Card {
                Text("Режим подключения", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(3.dp)) {
                    listOf("tun" to "Весь компьютер (TUN)", "proxy" to "Системный прокси").forEach { (v, t) ->
                        val sel = state.mode == v
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (sel) Brand else Color.Transparent)
                                .clickable { state.changeMode(v) }.padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(t, color = if (sel) Color.White else MaterialTheme.colorScheme.onSurface, fontSize = 13.sp) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.mode == "tun")
                        "Через VPN идёт весь трафик компьютера: браузеры, игры, Discord, торренты. Нужны права администратора — " +
                            "Windows спросит при включении. Отдельные программы можно пустить мимо VPN."
                    else
                        "Через VPN идут браузеры и программы, которые используют системный прокси. Игры и часть приложений " +
                            "его не используют — для них выберите «Весь компьютер».",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.mode == "tun") {
                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsRow("Раздельное туннелирование" + if (state.excludedApps.isNotEmpty()) " · ${state.excludedApps.size}" else "") { showApps = true }
                }
            }
            Card {
                Text("Запуск", fontWeight = FontWeight.SemiBold)
                SettingsToggle(
                    "Автоподключение",
                    "VPN включится сам при запуске приложения",
                    state.autoConnect,
                ) { state.changeAutoConnect(it) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsToggle(
                    "Запускать вместе с Windows",
                    if (state.mode == "tun") "С правами администратора, без запроса при входе — если включить из режима TUN" else "Приложение откроется при входе в Windows",
                    state.autostart,
                ) { state.changeAutostart(it) }
            }
            Card {
                SettingsRow("Обновить подписку") { state.refresh() }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SettingsRow("Обновление приложения") { state.checkUpdate() }
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
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsRow(title: String, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = if (danger) Red else MaterialTheme.colorScheme.onSurface)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
