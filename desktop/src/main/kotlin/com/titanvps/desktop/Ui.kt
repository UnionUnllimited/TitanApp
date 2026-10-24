@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.titanvps.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.DefaultContextMenuRepresentation
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalContextMenuRepresentation
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.AppRegistration
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Loyalty
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.desktop.ServerGroups.Group
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Default.Home),
    SUBSCRIPTION("Подписка", Icons.Default.WorkspacePremium),
    PROFILE("Профиль", Icons.Default.Person),
}

@Composable
fun App(state: AppState) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    val c = Palette.colors
    // Right-click menus (servers, text fields) in the app's colours.
    CompositionLocalProvider(LocalContextMenuRepresentation provides DefaultContextMenuRepresentation(c.surface, c.text, c.track)) {
        Box(Modifier.fillMaxSize().background(c.background)) {
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
                                Tab.HOME -> Home(state, sub, openSubscription = { tab = Tab.SUBSCRIPTION }, openProfile = { tab = Tab.PROFILE })
                                Tab.SUBSCRIPTION -> SubscriptionPage(state, sub)
                                Tab.PROFILE -> SettingsPage(state, openSubscription = { tab = Tab.SUBSCRIPTION }, openHome = { tab = Tab.HOME })
                            }
                        }
                    }
                }
            }
            ToastHost(state.toast, state::dismissToast, Modifier.align(Alignment.TopCenter))
            if (state.updateDialog) UpdateDialog(state)
            if (state.needAdmin) AdminDialog(state)
        }
    }
}

@Composable
private fun AdminDialog(state: AppState) {
    TitanDialog(
        { state.cancelAdmin() }, "Нужны права администратора",
        "Режим «Весь компьютер» создаёт виртуальную сетевую карту — для этого Windows требует права администратора. " +
            "Приложение перезапустится, Windows покажет запрос — нажмите «Да».",
        icon = Icons.Default.AdminPanelSettings, iconColor = Orange,
        buttons = {
            PrimaryButton("Перезапустить", { state.restartAsAdmin() }, icon = Icons.Default.RestartAlt, height = 48.dp)
            SecondaryButton("Остаться на системном прокси", { state.cancelAdmin() })
        },
    )
}

@Composable
private fun UpdateDialog(state: AppState) {
    val u = state.update
    val busy = u == UpdateState.ConnectingVpn || u == UpdateState.Checking || u is UpdateState.Downloading || u == UpdateState.Installing
    TitanDialog(
        { state.dismissUpdate() }, "Обновление приложения",
        when (u) {
            UpdateState.ConnectingVpn -> "Включаем VPN…"
            UpdateState.Checking -> "Проверяем…"
            UpdateState.UpToDate -> "У вас последняя версия"
            is UpdateState.Available -> "Доступна новая версия ${u.release.name}. Приложение закроется, установит её и откроется снова."
            is UpdateState.Downloading -> "Скачиваем обновление… ${(u.progress * 100).toInt()}%"
            UpdateState.Installing -> "Устанавливаем…"
            is UpdateState.Error -> u.message
            UpdateState.Idle -> ""
        },
        icon = if (u is UpdateState.Error) Icons.Default.Warning else if (u == UpdateState.UpToDate) Icons.Default.CheckCircle else Icons.Default.SystemUpdate,
        iconColor = when (u) {
            is UpdateState.Error -> Orange
            UpdateState.UpToDate -> Palette.colors.green
            else -> Brand
        },
        dismissible = !busy,
        content = if (!busy) null else {
            {
                if (u is UpdateState.Downloading) GradientBar(u.progress)
                else Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(Palette.colors.track).shimmer(true, Brand, 1200))
            }
        },
        buttons = if (busy) null else {
            {
                when (u) {
                    is UpdateState.Available -> PrimaryButton("Обновить", { state.installUpdate() }, icon = Icons.Default.SystemUpdate, height = 48.dp)
                    is UpdateState.Error -> PrimaryButton("Повторить", { state.checkUpdate() }, icon = Icons.Default.Refresh, height = 48.dp)
                    else -> {}
                }
                SecondaryButton(if (u is UpdateState.Available) "Позже" else "Закрыть", { state.dismissUpdate() }, tint = Palette.colors.secondary)
            }
        },
    )
}

// ------------------------------------------------------------------ sidebar

/** Logo and title, the sections as pills (the picked one in the gradient), support and theme at the bottom. */
@Composable
private fun Sidebar(tab: Tab, compact: Boolean, state: AppState, onTab: (Tab) -> Unit) {
    val uri = LocalUriHandler.current
    val c = Palette.colors
    Column(
        Modifier.width(if (compact) 84.dp else 232.dp).fillMaxHeight().background(c.surface)
            .drawBehind { drawLine(c.outline.copy(alpha = 0.5f), Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx()) }
            .padding(horizontal = 14.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (compact) {
            Image(painterResource("logo.png"), null, Modifier.size(44.dp))
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource("logo.png"), null, Modifier.size(42.dp))
                Spacer(Modifier.width(10.dp))
                LogoTitle(22.sp)
            }
        }
        Spacer(Modifier.height(30.dp))
        Tab.entries.forEach { t ->
            SidebarItem(t.icon, t.title, t == tab, compact) { onTab(t) }
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.weight(1f))
        val support: @Composable () -> Unit = {
            SquareButton(Icons.Default.Headphones, "Поддержка") { uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL) }
        }
        val theme: @Composable () -> Unit = {
            SquareButton(if (c.dark) Icons.Default.LightMode else Icons.Default.DarkMode, "Тема") {
                state.changeTheme(if (c.dark) "light" else "dark")
            }
        }
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { support(); theme() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { support(); theme() }
        }
    }
}

@Composable
private fun SidebarItem(icon: ImageVector, title: String, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    val c = Palette.colors
    val shape = RoundedCornerShape(14.dp)
    var hovered by remember { mutableStateOf(false) }
    val color by animateColorAsState(if (selected) Color.White else if (hovered) c.text else c.secondary)
    Row(
        Modifier.fillMaxWidth().height(48.dp)
            .then(
                if (selected) Modifier.shadow(8.dp, shape, ambientColor = Brand.copy(alpha = 0.35f), spotColor = Brand.copy(alpha = 0.4f))
                    .clip(shape).background(Palette.accent)
                else Modifier.clip(shape).background(if (hovered) c.track else Color.Transparent)
            )
            .press(scale = 0.97f, onHover = { hovered = it }, onClick = onClick)
            .padding(horizontal = if (compact) 0.dp else 16.dp),
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, title, Modifier.size(22.dp), tint = color)
        if (!compact) {
            Spacer(Modifier.width(14.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
        }
    }
}

/** Поддержка, Тема: a square on the background with the icon in the brand colour. */
@Composable
private fun SquareButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val c = Palette.colors
    val shape = RoundedCornerShape(14.dp)
    var hovered by remember { mutableStateOf(false) }
    Box(
        Modifier.size(48.dp).clip(shape).background(if (hovered) c.track else c.background)
            .border(1.dp, c.outline.copy(alpha = 0.6f), shape)
            .press(scale = 0.9f, onHover = { hovered = it }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(21.dp), tint = Brand)
    }
}

// ------------------------------------------------------------------ key entry

/** As on iPhone: drifting light, the glowing logo, the key in a card, where to get one. */
@Composable
private fun Welcome(state: AppState) {
    val clipboard = LocalClipboardManager.current
    val c = Palette.colors
    var key by remember { mutableStateOf("") }
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val shown by animateFloatAsState(if (appeared) 1f else 0f, spring(dampingRatio = 0.8f, stiffness = 120f))
    val valid = remember(key) { Links.find(key) != null }
    fun connect() {
        if (key.isNotBlank() && !state.busy) state.activate(key)
    }
    fun Modifier.entrance(rise: Float) = graphicsLayer { alpha = shown; translationY = (1 - shown) * rise * density }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.entrance(16f), horizontalAlignment = Alignment.CenterHorizontally) {
                    GlowingLogo(110.dp)
                    LogoTitle(34.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Вставьте ключ подписки из бота или личного кабинета — и VPN включится сразу",
                        fontSize = 15.sp, color = c.secondary, textAlign = TextAlign.Center, lineHeight = 20.sp,
                    )
                }
                Spacer(Modifier.height(22.dp))
                // The key in a frosted card.
                Glass(Modifier.entrance(24f)) {
                    Text("Ключ подписки", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, color = c.secondary)
                    Spacer(Modifier.height(12.dp))
                    KeyField(key, { key = it }, valid, ::connect)
                    AnimatedVisibility(key.isNotEmpty() && !valid) {
                        Text(
                            "Это не похоже на ключ Titan VPS. Скопируйте ссылку подписки из бота целиком.",
                            fontSize = 12.sp, color = Orange, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("Вставить из буфера", { clipboard.getText()?.text?.trim()?.let { key = it } }, icon = Icons.Default.ContentPaste, height = 50.dp)
                    Spacer(Modifier.height(10.dp))
                    PrimaryButton(
                        "Подключить", ::connect, icon = Icons.Default.VerifiedUser,
                        enabled = key.isNotBlank() && !state.busy, busy = state.busy, busyText = "Проверяем ключ…", height = 54.dp,
                    )
                }
                Spacer(Modifier.height(22.dp))
                Glass(Modifier.entrance(32f)) {
                    Text("Где взять ключ", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Step(1, "Откройте нашего бота в Telegram")
                    Spacer(Modifier.height(12.dp))
                    Step(2, "Оформите подписку или продлите её")
                    Spacer(Modifier.height(12.dp))
                    Step(3, "Скопируйте ссылку-ключ и вставьте её выше")
                    Spacer(Modifier.height(16.dp))
                    val uri = LocalUriHandler.current
                    SecondaryButton("Открыть бота", { uri.openUri(Config.TELEGRAM_URL) }, icon = Icons.AutoMirrored.Filled.Send, height = 48.dp)
                }
            }
        }
    }
}

/** A frosted card over the drifting light. */
@Composable
private fun Glass(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Palette.colors
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier.fillMaxWidth()
            .shadow(20.dp, shape, ambientColor = Color.Black.copy(alpha = 0.15f), spotColor = Color.Black.copy(alpha = 0.2f))
            .clip(shape).background(c.surface.copy(alpha = if (c.dark) 0.72f else 0.8f))
            .border(1.dp, Color.White.copy(alpha = if (c.dark) 0.1f else 0.5f), shape)
            .padding(18.dp),
        content = content,
    )
}

@Composable
private fun KeyField(key: String, onChange: (String) -> Unit, valid: Boolean, onGo: () -> Unit) {
    val c = Palette.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val border by animateColorAsState(
        when {
            key.isEmpty() -> if (focused) Brand else c.outline
            valid -> c.green
            else -> Orange
        },
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(c.background.copy(alpha = 0.7f))
            .border(if (focused || key.isNotEmpty()) 1.5.dp else 1.dp, border, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Key, null, Modifier.size(20.dp), tint = if (valid) c.green else Brand)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (key.isEmpty()) Text("https://api1.titanvps.su/…", fontSize = 15.sp, color = c.tertiary, maxLines = 1)
            BasicTextField(
                key, { onChange(it.trim()) }, singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = c.text),
                cursorBrush = SolidColor(Brand),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { onGo(); true } else false
                    },
            )
        }
        if (key.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Icon(if (valid) Icons.Default.CheckCircle else Icons.Default.Error, null, Modifier.size(20.dp), tint = if (valid) c.green else Orange)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Close, "Очистить", Modifier.size(18.dp).clip(CircleShape).press(scale = 0.85f) { onChange("") }, tint = c.secondary)
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Palette.accent), contentAlignment = Alignment.Center) {
            Text("$n", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp)
    }
}

// ------------------------------------------------------------------ home

private enum class Sort(val title: String) { RECOMMENDED("Рекомендуемые"), PING("По пингу"), NAME("По названию") }

@Composable
private fun Home(state: AppState, sub: Subscription, openSubscription: () -> Unit, openProfile: () -> Unit) {
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
                // Favourites first, in the subscription's order otherwise (as on iPhone).
                Sort.RECOMMENDED -> list.filter(state::isFavorite) + list.filterNot(state::isFavorite)
                Sort.PING -> list.sortedBy { s -> state.pings[s.id]?.takeIf { it >= 0 } ?: Long.MAX_VALUE }
                Sort.NAME -> list.sortedBy { ServerGroups.splitFlag(it.name).second.lowercase() }
            }
        }
    // Speed and traffic: read only while Главная is on screen and the VPN is on.
    val metricsPort = state.metricsPortNow
    DisposableEffect(metricsPort) {
        if (metricsPort > 0) state.traffic.start(metricsPort)
        onDispose { state.traffic.stop() }
    }

    val header: @Composable () -> Unit = {
        ListHeader(state, sub, groups, page, { page = it }, servers, query, { query = it }, sort, { sort = it }, openSubscription)
    }
    val row: @Composable (Server) -> Unit = { s ->
        val bypass = ServerGroups.isBypass(s)
        ServerRow(
            server = s,
            ping = state.pings[s.id],
            measuring = s.id in state.measuring,
            selected = s.id == state.selected?.id,
            limited = bypass && ServerGroups.isLimited(s),
            locked = state.bypassLocked && bypass,
            favorite = state.isFavorite(s),
            onPing = { state.pingOne(s.id) },
            onFavorite = { state.toggleFavorite(s) },
        ) { state.select(s.id) }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= 820.dp) {
            // Wider on big windows: the connection gets the room, the list isn't a stretched band.
            val panelWidth = (maxWidth * 0.4f).coerceIn(400.dp, 620.dp)
            Row(Modifier.fillMaxSize()) {
                ControlCentre(state, sub, openSubscription, openProfile, Modifier.width(panelWidth).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().padding(top = 20.dp, end = 20.dp)) {
                    header()
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
                        items(servers, key = { it.id }) { row(it) }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item { Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { Controls(state, sub, openSubscription) } }
                item { Box(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(top = 6.dp)) { header() } }
                items(servers, key = { it.id }) { Box(Modifier.widthIn(max = 640.dp).fillMaxWidth()) { row(it) } }
            }
        }
    }
}

/** Narrow windows, no panel under them: the subscription card, the Выкл | Вкл slider and the status as text. */
@Composable
private fun ColumnScope.Controls(state: AppState, sub: Subscription, openSubscription: () -> Unit) {
    AccountBanner(state)
    HomeSubscriptionCard(state, sub, openSubscription)
    Spacer(Modifier.height(2.dp))
    Power(state)
    StatusBlock(state)
}

/**
 * Wide windows, the whole height: the subscription card on top, the connection in the middle
 * of what's left (the flag in its ring, the status, the slider), a few facts at the bottom.
 */
@Composable
private fun ControlCentre(state: AppState, sub: Subscription, openSubscription: () -> Unit, openProfile: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        // Grows with the window; the default one keeps it small rather than scroll (smaller still under a banner).
        val banner = state.account?.status.let { it == "BLOCKED" || it == "MAINTENANCE" }
        val emblem = minOf(maxHeight - if (banner) 680.dp else 600.dp, maxWidth * 0.7f).coerceIn(136.dp, 380.dp)
        Column(
            // At least the window's height, so the spacers share what's free; scrolls only when even that doesn't fit.
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AccountBanner(state)
            HomeSubscriptionCard(state, sub, openSubscription)
            Spacer(Modifier.weight(1f))
            Column(Modifier.widthIn(max = 460.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                // The lines under the emblem come and go: the room they can take is kept above it
                // (and the check's below the slider), so the slider stays put under the mouse.
                val roomy = emblem >= 280.dp
                val gap = if (roomy) 24.dp else 16.dp
                Column(
                    Modifier.fillMaxWidth().heightIn(min = emblem + gap + 124.dp),
                    verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ConnectionEmblem(state.selected, state.vpn, emblem)
                    Spacer(Modifier.height(gap))
                    HeroStatus(state)
                }
                Spacer(Modifier.height(if (roomy) 32.dp else 20.dp))
                Power(state)
                Box(Modifier.fillMaxWidth().heightIn(min = 104.dp), contentAlignment = Alignment.TopCenter) {
                    ConnectedDetails(state, top = 16.dp)
                    ModeHint(state, top = 16.dp)
                }
            }
            Spacer(Modifier.weight(1f))
            InfoChips(state, sub, openSubscription, openProfile)
        }
    }
}

/** Выкл | Вкл, wired to the VPN. */
@Composable
private fun Power(state: AppState) {
    PowerSlider(
        phase = when (state.vpn) {
            VpnState.Connecting -> Phase.CONNECTING
            is VpnState.Connected -> Phase.ON
            VpnState.Disconnecting -> Phase.DISCONNECTING
            else -> Phase.OFF
        },
        failures = state.failures,
    ) { on ->
        if (on) {
            when {
                state.vpn is VpnState.Connected -> true
                state.selected == null -> { state.showToast("Выберите сервер в списке", Toast.Kind.WARNING); false }
                else -> { state.connect(); true }
            }
        } else {
            state.disconnect()
            true
        }
    }
}

/** Blocked account / maintenance, from the bot; click → support. */
@Composable
private fun AccountBanner(state: AppState) {
    val uri = LocalUriHandler.current
    val status = state.account?.status
    val text = when (status) {
        "BLOCKED" -> "Аккаунт заблокирован. Напишите в поддержку"
        "MAINTENANCE" -> "Идут технические работы. Подключение может не работать"
        else -> return
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (status == "BLOCKED") Red else Orange)
            .press(scale = 0.98f) { uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Warning, null, Modifier.size(20.dp), tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private fun isActive(info: SubscriptionInfo): Boolean = info.expireAt <= 0 || info.expireAt > System.currentTimeMillis() / 1000

@Composable
private fun HomeSubscriptionCard(state: AppState, sub: Subscription, openSubscription: () -> Unit) {
    val c = Palette.colors
    var expanded by remember { mutableStateOf(false) }
    val info = sub.info
    val active = isActive(info)
    TitanCard {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).press(scale = 0.98f, onClick = openSubscription),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(if (active) Palette.accent else SolidColor(Red)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.WorkspacePremium, null, Modifier.size(22.dp), tint = Color.White) }
                Spacer(Modifier.width(14.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (active) "Подписка активна" else "Подписка истекла", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = c.tertiary)
                    }
                    Text(if (info.expireAt > 0) "До " + Fmt.date(info.expireAt) else "Бессрочно", fontSize = 14.sp, color = c.secondary)
                }
            }
            CircleIconButton(Icons.Default.Refresh, "Обновить подписку", { state.refresh() }, spinning = state.busy, size = 44.dp)
        }
        HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = c.outline.copy(alpha = 0.6f))
        val turn by animateFloatAsState(if (expanded) 180f else 0f)
        Row(
            Modifier.fillMaxWidth().rowClick { expanded = !expanded }.padding(vertical = 9.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "Скрыть" else "Подробнее", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.secondary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp).rotate(turn), tint = c.secondary)
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val now = System.currentTimeMillis() / 1000
                if (info.expireAt > 0) Detail("Осталось", "${((info.expireAt - now) / 86400).coerceAtLeast(0)} дн.")
                Detail("Обычные серверы", "Безлимит")
                Detail("Трафик на обходах", if (info.totalBytes > 0) "${formatBytes(info.remainingBytes)} из ${formatBytes(info.totalBytes)}" else "Безлимит")
                Detail("Обновлено", Fmt.ago(sub.fetchedAt))
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Row {
        Text(label, fontSize = 14.sp, color = Palette.colors.secondary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

private enum class Phase { OFF, CONNECTING, ON, DISCONNECTING }

/**
 * Выкл | Вкл as one slider (as on iPhone): the knob is dragged across with the mouse, or a
 * side clicked. [onToggle] false means it can't be done now: the knob goes back and shakes.
 */
@Composable
private fun PowerSlider(phase: Phase, failures: Int, onToggle: (Boolean) -> Boolean) {
    val c = Palette.colors
    /** The side just picked, held until the VPN's state follows (or for a moment at most). */
    var pending by remember { mutableStateOf<Boolean?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    var shakes by remember { mutableIntStateOf(0) }
    val isOn = pending ?: (phase == Phase.ON || phase == Phase.CONNECTING)
    val busy = phase == Phase.CONNECTING || phase == Phase.DISCONNECTING
    LaunchedEffect(phase) { pending = null }
    LaunchedEffect(failures) {
        if (pending != null) { pending = null; shakes++ }
    }
    LaunchedEffect(pending) {
        if (pending != null) { delay(1500); pending = null }
    }
    fun commit(on: Boolean) {
        if (onToggle(on)) pending = on else shakes++
    }

    val height = 68.dp
    val inset = 5.dp
    BoxWithConstraints(Modifier.fillMaxWidth().height(height).shake(shakes)) {
        val density = LocalDensity.current
        val knobWidth = (maxWidth - inset * 2) / 2
        val maxX = with(density) { (maxWidth - inset * 2 - knobWidth).toPx() }.coerceAtLeast(1f)
        val base = if (isOn) maxX else 0f
        val target = if (dragging) (base + dragDx).coerceIn(0f, maxX) else base
        val x by animateFloatAsState(target, if (dragging) snap() else spring(dampingRatio = 0.8f, stiffness = 380f))
        val progress = (x / maxX).coerceIn(0f, 1f)
        val insetPx = with(density) { inset.toPx() }
        Box(
            Modifier.fillMaxSize().pointerHoverIcon(PointerIcon.Hand)
                .pointerInput(isOn) {
                    detectTapGestures { p -> val right = p.x > size.width / 2; if (right != isOn) commit(right) }
                }
                .pointerInput(isOn, maxX) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true; dragDx = 0f },
                        onDragEnd = {
                            val end = (base + dragDx).coerceIn(0f, maxX)
                            dragging = false
                            val to = end > maxX / 2
                            if (to != isOn) commit(to)
                        },
                        onDragCancel = { dragging = false },
                    ) { change, amount -> change.consume(); dragDx += amount }
                },
        ) {
            // The track: greener as the knob goes right, a shine while connecting.
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(c.track)
                    .drawBehind { drawRect(c.green.copy(alpha = 0.16f * progress)) }
                    .shimmer(busy, Brand, 1200)
                    .border(1.dp, c.outline.copy(alpha = 0.7f), CircleShape),
            )
            // What the free half says: where the knob would go.
            Row(Modifier.fillMaxSize().padding(horizontal = inset), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).graphicsLayer { alpha = progress }, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.PowerSettingsNew, null, Modifier.size(19.dp), tint = c.secondary)
                    Spacer(Modifier.width(6.dp))
                    Text("Выкл", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                }
                Row(
                    Modifier.weight(1f).graphicsLayer { alpha = 1 - progress }.shimmer(phase == Phase.OFF, Brand, 2000),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Вкл", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = c.secondary)
                }
            }
            // The knob.
            val lit = progress > 0.5f
            val glow = if (phase == Phase.CONNECTING) Brand else Green
            Box(
                Modifier.offset { IntOffset((insetPx + x).roundToInt(), insetPx.roundToInt()) }
                    .width(knobWidth).height(height - inset * 2)
                    .shadow(6.dp + 6.dp * progress, CircleShape, ambientColor = glow.copy(alpha = 0.3f * progress), spotColor = if (progress > 0.1f) glow.copy(alpha = 0.5f) else Color.Black.copy(alpha = 0.3f))
                    .clip(CircleShape).background(c.knob)
                    .drawWithContent {
                        drawRect(if (phase == Phase.CONNECTING) Palette.accent else Palette.green, alpha = progress)
                        drawContent()
                    },
                contentAlignment = Alignment.Center,
            ) {
                val tint = if (lit) Color.White else c.text
                Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (icon, label) = when {
                        busy -> null to if (phase == Phase.CONNECTING) "Подключение" else "Отключение"
                        lit -> Icons.Default.VerifiedUser to "Вкл"
                        else -> Icons.Default.PowerSettingsNew to "Выкл"
                    }
                    if (icon != null) Icon(icon, null, Modifier.size(20.dp), tint = tint) else Spinner(16.dp, if (lit) Color.White else Brand)
                    Spacer(Modifier.width(8.dp))
                    FitText(label, tint, 17.sp, 12.sp, FontWeight.Bold, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/** Under the slider: what the VPN is doing, the time on, the speed, the server, the check. */
@Composable
private fun StatusBlock(state: AppState) {
    val vpn = state.vpn
    Box(Modifier.fillMaxWidth()) {
        // Behind the status: the last minute of download as a faint curve, barely there.
        if (vpn is VpnState.Connected) SpeedBackdrop(state.traffic, Modifier.matchParentSize())
        Column(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            StatusTitle(vpn, 26.sp)
            when (vpn) {
                is VpnState.Connected -> { Uptime(vpn.since); SpeedLine(state.traffic) }
                VpnState.Connecting -> ConnectStep(state)
                else -> {}
            }
            ServerLine(state, flag = true)
            if (vpn is VpnState.Error) ErrorLine(vpn)
            ConnectedDetails(state, top = 4.dp)
        }
    }
}

/** Under the emblem, in the iPhone's order: the state in big letters, the time on and the speed, the server. */
@Composable
private fun HeroStatus(state: AppState) {
    val vpn = state.vpn
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        StatusTitle(vpn, 28.sp)
        when (vpn) {
            // The last minute faintly behind the two lines it's about only, not behind the names.
            is VpnState.Connected -> Box(Modifier.widthIn(max = 340.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                SpeedBackdrop(state.traffic, Modifier.matchParentSize())
                Column(Modifier.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Uptime(vpn.since)
                    SpeedLine(state.traffic)
                }
            }
            VpnState.Connecting -> ConnectStep(state)
            else -> {}
        }
        ServerLine(state, flag = false)
        if (vpn is VpnState.Error) ErrorLine(vpn)
    }
}

@Composable
private fun StatusTitle(vpn: VpnState, fontSize: TextUnit) {
    val c = Palette.colors
    val (title, color) = when (vpn) {
        is VpnState.Connected -> "Подключено" to c.green
        VpnState.Connecting -> "Подключаемся…" to Brand
        VpnState.Disconnecting -> "Отключаемся…" to c.text
        else -> "Не подключено" to c.text
    }
    val titleColor by animateColorAsState(color)
    Text(title, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, color = titleColor)
}

@Composable
private fun Uptime(since: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    val s = ((now - since) / 1000).coerceAtLeast(0)
    Text("%02d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Palette.colors.secondary, style = Tabular)
}

/** The speed now, quietly. */
@Composable
private fun SpeedLine(traffic: TrafficMonitor) {
    Text(
        "↓ ${Fmt.speed(traffic.down)}     ↑ ${Fmt.speed(traffic.up)}",
        fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.colors.tertiary, style = Tabular,
    )
}

@Composable
private fun ConnectStep(state: AppState) {
    Text(state.connectStep.ifEmpty { "Устанавливаем соединение" }, fontSize = 13.sp, color = Palette.colors.secondary, textAlign = TextAlign.Center)
}

@Composable
private fun ErrorLine(error: VpnState.Error) {
    Text(error.message, fontSize = 13.sp, color = Red, textAlign = TextAlign.Center)
}

/** The selected server and its ping; no small flag where it's shown big already. */
@Composable
private fun ServerLine(state: AppState, flag: Boolean) {
    val c = Palette.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        val s = state.selected
        if (s != null) {
            if (flag) {
                FlagCircle(s, 20.dp)
                Spacer(Modifier.width(7.dp))
            }
            val ping = state.pings[s.id]?.takeIf { it >= 0 }
            Text(
                ServerGroups.splitFlag(s.name).second + (ping?.let { " · $it мс" } ?: ""),
                fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text("Выберите сервер", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary)
        }
    }
}

/** Once connected: Проверить соединение with the IP, and what the mode sends through the VPN. */
@Composable
private fun ConnectedDetails(state: AppState, top: Dp) {
    val c = Palette.colors
    AnimatedVisibility(state.vpn is VpnState.Connected, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(top))
            ConnectionCheckView(state)
            Spacer(Modifier.height(6.dp))
            Text(
                if (state.mode == "tun") "Весь трафик компьютера идёт через VPN" else "Браузеры и большинство программ идут через VPN",
                fontSize = 11.sp, color = c.tertiary,
            )
        }
    }
}

/** Until it's on, where the check will be: what Вкл will send through the VPN. */
@Composable
private fun ModeHint(state: AppState, top: Dp) {
    AnimatedVisibility(state.vpn !is VpnState.Connected, enter = fadeIn(), exit = fadeOut()) {
        Text(
            if (state.mode == "tun") "Весь трафик компьютера пойдёт через VPN" else "Браузеры и большинство программ пойдут через VPN",
            fontSize = 11.sp, color = Palette.colors.tertiary, modifier = Modifier.padding(top = top),
        )
    }
}

/**
 * The selected server's flag in a ring that shows the connection: a plain track while off,
 * an arc running round while it connects, the ring closing green with a glow once on.
 */
@Composable
private fun ConnectionEmblem(server: Server?, vpn: VpnState, size: Dp) {
    val c = Palette.colors
    val busy = vpn == VpnState.Connecting || vpn == VpnState.Disconnecting
    val on by animateFloatAsState(if (vpn is VpnState.Connected) 1f else 0f, tween(900))
    val track by animateColorAsState(if (vpn is VpnState.Error) Red.copy(alpha = 0.6f) else c.outline)
    val arc = if (vpn == VpnState.Connecting) Brand else c.secondary
    // Turns only while busy: a still emblem costs no frames.
    val turn = if (busy) rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing))).value else 0f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val h = this.size.minDimension / 2
            val w = h * 0.05f
            val r = h * 0.72f
            val ring = Size(r * 2, r * 2)
            val at = Offset(center.x - r, center.y - r)
            if (on > 0f) drawCircle(
                Brush.radialGradient(0.45f to c.green.copy(alpha = (if (c.dark) 0.3f else 0.22f) * on), 1f to Color.Transparent, center = center, radius = h),
                h,
            )
            // Two faint ripples around it, green once on.
            val ripple = lerp(c.outline, c.green, on)
            drawCircle(ripple.copy(alpha = 0.5f), h * 0.86f, style = Stroke(1.dp.toPx()))
            drawCircle(ripple.copy(alpha = 0.25f), h * 0.98f, style = Stroke(1.dp.toPx()))
            drawCircle(track, r, style = Stroke(w))
            if (on > 0f) drawArc(c.green, -90f, 360f * on, false, at, ring, style = Stroke(w, cap = StrokeCap.Round))
            if (busy) rotate(turn) {
                // A comet with a fading tail: a butt end there (a round cap would catch the gradient's
                // wrap at 0° and show as a bright dot), a round head drawn on its own.
                drawArc(
                    Brush.sweepGradient(0f to arc.copy(alpha = 0f), 0.3f to arc, 1f to arc, center = center),
                    2f, 106f, false, at, ring, style = Stroke(w, cap = StrokeCap.Butt),
                )
                val head = Math.toRadians(108.0)
                drawCircle(arc, w / 2, center + Offset(r * cos(head).toFloat(), r * sin(head).toFloat()))
            }
            // The bezel the flag sits in; a step below the page in light, so a white rim still reads as round.
            drawCircle(if (c.dark) c.surface else c.track, h * 0.58f)
            drawCircle(c.outline.copy(alpha = 0.6f), h * 0.58f, style = Stroke(1.dp.toPx()))
            // An edge for flags that are white at the rim (Finland, Japan).
            val edge = (if (c.dark) 1.dp else 1.5.dp).toPx()
            drawCircle(if (c.dark) c.outline else c.secondary.copy(alpha = 0.35f), h * 0.44f + edge / 2, style = Stroke(edge))
        }
        if (server != null) FlagCircle(server, size * 0.44f) else FlagCircle(null, false, size * 0.44f)
    }
}

/** A few facts at the bottom of the column; each opens the page where it's set. */
@Composable
private fun InfoChips(state: AppState, sub: Subscription, openSubscription: () -> Unit, openProfile: () -> Unit) {
    val info = sub.info
    val hasBypass = remember(sub.servers) { sub.servers.any(ServerGroups::isBypass) }
    // In order of what matters; the last ones are left out where the column is narrow.
    FittingRow(8.dp) {
        state.account?.takeIf { it.devicesEnabled }?.let { acc ->
            InfoChip(
                Icons.Default.Devices, "Устройства",
                if (acc.deviceLimit > 0) "${acc.devices.size} из ${acc.deviceLimit}" else "${acc.devices.size}",
                onClick = openSubscription,
            )
        }
        if (hasBypass) InfoChip(
            Icons.Default.Shuffle, "Обходы",
            if (info.totalBytes > 0) formatBytes(info.remainingBytes) else "безлимит",
            tint = if (info.totalBytes > 0 && info.remainingBytes * 10 < info.totalBytes) Orange else Brand,
            onClick = openSubscription,
        )
        if (state.mode == "tun") InfoChip(Icons.Default.Computer, "Режим", "Весь ПК", onClick = openProfile)
        else InfoChip(Icons.Default.Language, "Режим", "Прокси", onClick = openProfile)
    }
}

/** Centred in one line, as many as fit: the ones that don't are left out rather than wrapped or cut. */
@Composable
private fun FittingRow(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content, Modifier.fillMaxWidth()) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val shown = mutableListOf<Placeable>()
        var width = 0
        for (m in measurables) {
            val p = m.measure(Constraints())
            val w = width + (if (shown.isEmpty()) 0 else gap) + p.width
            if (w <= constraints.maxWidth) { shown += p; width = w }
        }
        val height = shown.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = (constraints.maxWidth - width) / 2
            shown.forEach { it.placeRelative(x, (height - it.height) / 2); x += it.width + gap }
        }
    }
}

@Composable
private fun InfoChip(icon: ImageVector, label: String, value: String, tint: Color = Brand, onClick: () -> Unit) {
    val c = Palette.colors
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(if (hovered) c.track else c.surface)
            .border(1.dp, c.outline.copy(alpha = 0.6f), CircleShape)
            .press(scale = 0.96f, onHover = { hovered = it }, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(15.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text("$label ", fontSize = 12.sp, color = c.secondary, maxLines = 1)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** The last minute of download behind the status, a faint filled curve. */
@Composable
private fun SpeedBackdrop(monitor: TrafficMonitor, modifier: Modifier) {
    val history = monitor.history
    // Fainter on white, where the same blue shows more.
    val alpha = if (Palette.colors.dark) 0.10f else 0.07f
    // Soft at the sides, so it doesn't end in a cliff.
    val fade = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.2f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
    }
    Canvas(modifier.then(fade)) {
        if (history.size < 2) return@Canvas
        val top = maxOf(history.maxOf { it.down }, 64.0 * 1024) * 1.5
        val lastId = history.last().id
        val firstId = lastId - TrafficMonitor.WINDOW + 1
        fun px(id: Int) = (id - firstId).toFloat() / (TrafficMonitor.WINDOW - 1) * size.width
        fun py(v: Double) = size.height - (v / top).toFloat() * size.height
        val points = history.map { Offset(px(it.id), py(it.down)) }
        val path = Path().apply {
            moveTo(points.first().x, size.height)
            lineTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val mid = (a.x + b.x) / 2
                cubicTo(mid, a.y, mid, b.y, b.x, b.y)
            }
            lineTo(points.last().x, size.height)
            close()
        }
        drawPath(path, Brush.verticalGradient(listOf(Brand.copy(alpha = alpha), Brand.copy(alpha = 0f))))
    }
}

/** Проверить соединение (as on iPhone): the result of a request through the VPN, click to repeat. */
@Composable
private fun ConnectionCheckView(state: AppState) {
    val c = Palette.colors
    val check = state.check
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(if (hovered) c.track else c.surface)
            .border(1.dp, c.outline.copy(alpha = 0.6f), CircleShape)
            .press(check != ConnectionCheck.Status.Checking, onHover = { hovered = it }) { state.checkConnection() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val text = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        when (check) {
            ConnectionCheck.Status.Idle -> {
                Icon(Icons.Default.MonitorHeart, null, Modifier.size(18.dp), tint = Brand)
                Spacer(Modifier.width(8.dp))
                Text("Проверить соединение", style = text)
            }
            ConnectionCheck.Status.Checking -> {
                Spinner(15.dp)
                Spacer(Modifier.width(8.dp))
                Text("Проверяем соединение…", style = text)
            }
            is ConnectionCheck.Status.Ok -> {
                Box(Modifier.size(9.dp).clip(CircleShape).background(pingColor(check.ms)))
                Spacer(Modifier.width(8.dp))
                Text("${check.ms} мс", style = text.merge(Tabular), color = pingColor(check.ms))
                check.country?.let { code ->
                    Text("  ·  ", style = text, color = c.secondary)
                    FlagCircle(code.lowercase(), false, 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(Fmt.country(code), style = text, maxLines = 1)
                }
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Refresh, "Проверить ещё раз", Modifier.size(15.dp), tint = c.secondary)
            }
            is ConnectionCheck.Status.Failed -> {
                Icon(Icons.Default.Warning, null, Modifier.size(18.dp), tint = Orange)
                Spacer(Modifier.width(8.dp))
                Text("Нет ответа через VPN", style = text)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Refresh, "Проверить ещё раз", Modifier.size(15.dp), tint = c.secondary)
            }
        }
    }
    when (check) {
        is ConnectionCheck.Status.Ok -> check.ip?.let { ip ->
            SelectionContainer {
                Text("IP: $ip", fontSize = 12.sp, color = c.secondary, style = Tabular, modifier = Modifier.padding(top = 6.dp))
            }
        }
        is ConnectionCheck.Status.Failed -> Text(
            if (check.why.isEmpty() || !state.techMode) "Попробуйте ещё раз или выберите другой сервер" else "${check.why}. Попробуйте другой сервер",
            fontSize = 12.sp, color = Red.copy(alpha = 0.85f), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
        else -> {}
    }
}

@Composable
private fun ListHeader(
    state: AppState, sub: Subscription, groups: Map<Group, List<Server>>, page: Group, onPage: (Group) -> Unit,
    servers: List<Server>, query: String, onQuery: (String) -> Unit, sort: Sort, onSort: (Sort) -> Unit,
    openSubscription: () -> Unit,
) {
    val c = Palette.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val counts = groups.map { (g, items) -> g to items.size }
            val fit = rememberTabsFit(counts, maxWidth)
            // Пинг as tall as the tabs: two lines make them taller.
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TabSize.gap),
            ) {
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(TabSize.stripPad),
                    horizontalArrangement = Arrangement.spacedBy(TabSize.chipGap),
                ) {
                    counts.forEach { (g, count) -> GroupTab(g, count, g == page, fit, Modifier.weight(1f).fillMaxHeight()) { onPage(g) } }
                }
                // In the order shown (favourites first): results fill the list from the top.
                PingButton(state, Modifier.fillMaxHeight()) { state.pingAll(servers.map { it.id }) }
            }
        }
        val info = sub.info
        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                page == Group.SERVERS -> Text("Безлимит · работают и на Wi‑Fi, и в мобильной сети", fontSize = 13.sp, color = c.secondary)
                state.bypassLocked -> {
                    Text(
                        "Обходы откроются, когда интернет заработает по белым спискам (ya.ru и vk.com открываются, google.com — нет). " +
                            "Сейчас интернет обычный — используйте «Страны».",
                        fontSize = 13.sp, color = Orange, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    LinkText(if (state.whitelistChecking) "Проверяем…" else "Проверить", !state.whitelistChecking) { state.checkWhitelist() }
                }
                info.totalBytes > 0 -> {
                    Text("Трафик: ${formatBytes(info.remainingBytes)} из ${formatBytes(info.totalBytes)}", fontSize = 13.sp, color = c.secondary)
                    Spacer(Modifier.width(8.dp))
                    LinkText("Докупить ГБ", onClick = openSubscription)
                }
                else -> Text("Для ограничений мобильного интернета", fontSize = 13.sp, color = c.secondary)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(query, onQuery, Modifier.weight(1f))
            SortMenu(sort, onSort)
        }
    }
}

@Composable
private fun LinkText(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Brand else Palette.colors.secondary,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).press(enabled, 0.95f, onClick = onClick).padding(horizontal = 2.dp, vertical = 2.dp),
    )
}

/** Sizes of Главная's tabs: the layout and [rememberTabsFit] use the same ones. */
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
    /** More room before the network on one line. */
    val wideNetworkGap = 4.dp
    val wideGlyph = 13.dp
    val narrowGlyph = 11.dp
    val networkSpacing = 4.dp
    val badgePad = 7.dp
    val pingPad = 16.dp
    val pingIcon = 19.dp
    val pingSpacing = 7.dp
    /** What the layout may round up. */
    val slack = 2.dp
    val title = 14.sp
    val badge = 12.sp
    val ping = 14.sp
    val wideNetwork = 12.sp
    val narrowNetwork = 11.sp
    /** Smaller than this the network line is left out (a narrow window, large text). */
    val minNetwork = 9.sp
}

/**
 * How the tabs fit: [oneLine], the network after the count; else under the title, at
 * [networkSize] (smaller when tight; null: no room even so), without the [icon] when the
 * title row is tight.
 */
@Immutable
private data class TabsFit(val oneLine: Boolean, val icon: Boolean, val networkSize: TextUnit?)

/** Measures the tabs' words at the app's text size: what fits [width], the row with Пинг. */
@Composable
private fun rememberTabsFit(counts: List<Pair<Group, Int>>, width: Dp): TabsFit {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val base = LocalTextStyle.current
    return remember(counts, width, density, base, measurer) {
        fun textWidth(text: String, size: TextUnit, weight: FontWeight = FontWeight.Medium): Float =
            measurer.measure(text, base.merge(TextStyle(fontSize = size, fontWeight = weight)), maxLines = 1).size.width.toFloat()
        with(density) {
            val n = counts.size.coerceAtLeast(1)
            val ping = (TabSize.pingPad * 2 + TabSize.pingIcon + TabSize.pingSpacing).toPx() +
                maxOf(textWidth("Пинг", TabSize.ping, FontWeight.SemiBold), textWidth("Стоп", TabSize.ping, FontWeight.SemiBold))
            // One tab's room, less what the layout may round up.
            val tab = (width.toPx() - TabSize.gap.toPx() - ping - (TabSize.stripPad * 2).toPx() -
                TabSize.chipGap.toPx() * (n - 1)) / n - TabSize.slack.toPx()
            // The icon, the title and the count.
            fun titleRow(group: Group, count: Int, spacing: Dp, icon: Boolean): Float =
                (if (icon) (TabSize.icon + spacing).toPx() else 0f) + textWidth(group.title, TabSize.title) + spacing.toPx() +
                    textWidth("$count", TabSize.badge, FontWeight.SemiBold) + (TabSize.badgePad * 2).toPx()
            val oneLine = counts.all { (group, count) ->
                val network = (TabSize.wideSpacing + TabSize.wideNetworkGap + TabSize.wideGlyph + TabSize.networkSpacing).toPx() +
                    textWidth(group.networks, TabSize.wideNetwork)
                (TabSize.widePad * 2).toPx() + titleRow(group, count, TabSize.wideSpacing, icon = true) + network <= tab
            }
            if (oneLine) return@remember TabsFit(oneLine = true, icon = true, networkSize = TabSize.wideNetwork)
            val inner = tab - (TabSize.narrowPad * 2).toPx()
            val icon = counts.all { (group, count) -> titleRow(group, count, TabSize.narrowSpacing, icon = true) <= inner }
            // The network line at its size, else half a point smaller at a time, else none.
            val room = inner - (TabSize.narrowGlyph + TabSize.networkSpacing).toPx()
            var size = TabSize.narrowNetwork.value
            while (size >= TabSize.minNetwork.value && counts.any { (group, _) -> textWidth(group.networks, size.sp) > room }) size -= 0.5f
            TabsFit(oneLine = false, icon = icon, networkSize = if (size >= TabSize.minNetwork.value) size.sp else null)
        }
    }
}

/** What a screen reader says for a tab: «Страны, 29 серверов, работают на Wi-Fi и LTE». */
private fun tabDescription(group: Group, count: Int): String =
    "${group.title}, ${Fmt.plural(count.toLong(), "сервер", "сервера", "серверов")}, ${group.spokenNetworks}"

/** The title and count, and where its servers work: after them on one line, under them otherwise. */
@Composable
private fun GroupTab(group: Group, count: Int, selected: Boolean, fit: TabsFit, modifier: Modifier, onClick: () -> Unit) {
    val c = Palette.colors
    val shape = RoundedCornerShape(13.dp)
    var hovered by remember { mutableStateOf(false) }
    val color = if (selected) Brand else if (hovered) c.text else c.secondary
    // Where it works: a lighter blue on the picked tab, grey on the other.
    val network = if (!selected) c.secondary else if (c.dark) lerp(Brand, Color.White, 0.55f) else Brand.copy(alpha = 0.85f)
    val description = tabDescription(group, count)
    val icon = if (group == Group.SERVERS) Icons.Default.Language else Icons.Default.Shuffle
    Box(
        modifier.heightIn(min = if (fit.oneLine) 44.dp else 50.dp).clip(shape)
            .background(if (selected) Brand.copy(alpha = 0.08f) else Color.Transparent)
            .border(1.dp, if (selected) Brand.copy(alpha = 0.35f) else Color.Transparent, shape)
            .press(scale = 0.97f, onHover = { hovered = it }, onClick = onClick)
            .semantics {
                role = Role.Tab
                this.selected = selected
                contentDescription = description
            }
            .padding(horizontal = if (fit.oneLine) TabSize.widePad else TabSize.narrowPad),
        contentAlignment = Alignment.Center,
    ) {
        // Read out as the description, in one go.
        val parts = Modifier.clearAndSetSemantics {}
        if (fit.oneLine) {
            Row(parts, horizontalArrangement = Arrangement.spacedBy(TabSize.wideSpacing, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(TabSize.icon), tint = color)
                TabTitle(group.title, color, Modifier.weight(1f, fill = false))
                CountBadge(count, selected)
                NetworkLabel(group, network, TabSize.wideNetwork, TabSize.wideGlyph, Modifier.padding(start = TabSize.wideNetworkGap))
            }
        } else {
            Column(parts, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(TabSize.narrowSpacing, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                    if (fit.icon) Icon(icon, null, Modifier.size(TabSize.icon), tint = color)
                    TabTitle(group.title, color, Modifier.weight(1f, fill = false))
                    CountBadge(count, selected)
                }
                fit.networkSize?.let { NetworkLabel(group, network, it, TabSize.narrowGlyph) }
            }
        }
    }
}

@Composable
private fun TabTitle(title: String, color: Color, modifier: Modifier) {
    Text(title, modifier, color = color, fontSize = TabSize.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun CountBadge(count: Int, selected: Boolean) {
    Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (selected) Brand else Palette.colors.outline).padding(horizontal = TabSize.badgePad, vertical = 2.dp)) {
        Text("$count", fontSize = TabSize.badge, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Palette.colors.secondary, maxLines = 1)
    }
}

/** Where a tab's servers work: Wi-Fi's fan or the signal bars, and the words. */
@Composable
private fun NetworkLabel(group: Group, color: Color, size: TextUnit, glyph: Dp, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(TabSize.networkSpacing), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (group == Group.SERVERS) Icons.Default.Wifi else Icons.Default.SignalCellularAlt, null, Modifier.size(glyph), tint = color)
        Text(group.networks, color = color, fontSize = size, lineHeight = size * 1.3f, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
    }
}

/** Пинг for every server (the tab on screen first); Стоп with a progress ring while it runs. */
@Composable
private fun PingButton(state: AppState, modifier: Modifier, onClick: () -> Unit) {
    val c = Palette.colors
    val pinging = state.pinging
    val shape = RoundedCornerShape(16.dp)
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier.heightIn(min = 52.dp).clip(shape)
            .background(if (pinging) Palette.accent else SolidColor(if (hovered) c.track else c.surface))
            .press(onHover = { hovered = it }, onClick = onClick).padding(horizontal = TabSize.pingPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinging) ProgressRing(state.pingProgress, Color.White) else Icon(Icons.Default.Speed, null, Modifier.size(TabSize.pingIcon), tint = c.text)
        Spacer(Modifier.width(TabSize.pingSpacing))
        Text(if (pinging) "Стоп" else "Пинг", fontSize = TabSize.ping, fontWeight = FontWeight.SemiBold, color = if (pinging) Color.White else c.text)
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val c = Palette.colors
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(c.surface).heightIn(min = 42.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, null, Modifier.size(18.dp), tint = c.secondary)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск сервера…", fontSize = 14.sp, color = c.tertiary)
            BasicTextField(
                value, onChange, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 14.sp, color = c.text),
                cursorBrush = SolidColor(Brand),
            )
        }
        if (value.isNotEmpty()) Icon(Icons.Default.Close, "Очистить", Modifier.size(16.dp).press(scale = 0.85f) { onChange("") }, tint = c.secondary)
    }
}

@Composable
private fun SortMenu(sort: Sort, onSort: (Sort) -> Unit) {
    val c = Palette.colors
    var open by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(14.dp)).background(if (hovered) c.track else c.surface)
                .press(scale = 0.97f, onHover = { hovered = it }) { open = true }
                .heightIn(min = 42.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(sort.title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp), tint = c.secondary)
        }
        DropdownMenu(open, { open = false }, shape = RoundedCornerShape(14.dp), containerColor = c.surface) {
            Sort.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s.title, fontWeight = if (s == sort) FontWeight.SemiBold else FontWeight.Normal) },
                    trailingIcon = { if (s == sort) Icon(Icons.Default.Check, null, Modifier.size(18.dp), tint = Brand) },
                    onClick = { onSort(s); open = false },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: Server, ping: Long?, measuring: Boolean, selected: Boolean, limited: Boolean, locked: Boolean,
    favorite: Boolean, onPing: () -> Unit, onFavorite: () -> Unit, onClick: () -> Unit,
) {
    val c = Palette.colors
    val shape = RoundedCornerShape(16.dp)
    var hovered by remember { mutableStateOf(false) }
    // Right click: the same as the long press on iPhone.
    ContextMenuArea(items = {
        listOf(
            ContextMenuItem("Выбрать", onClick),
            ContextMenuItem("Пинг", onPing),
            ContextMenuItem(if (favorite) "Убрать из избранного" else "В избранное", onFavorite),
        )
    }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(shape)
                .background(if (selected) Brand.copy(alpha = 0.08f) else c.surface)
                .border(if (selected) 1.5.dp else 1.dp, if (selected) Brand else if (hovered) c.outline else Color.Transparent, shape)
                .graphicsLayer { alpha = if (locked) 0.45f else 1f }
                .press(scale = 0.99f, onHover = { hovered = it }, onClick = onClick)
                .padding(start = 14.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FlagCircle(server, 34.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (favorite) {
                        Icon(Icons.Default.Star, null, Modifier.size(14.dp), tint = Yellow)
                        Spacer(Modifier.width(4.dp))
                    }
                    MarqueeText(ServerGroups.splitFlag(server.name).second, 15.sp, FontWeight.SemiBold, Modifier.weight(1f, fill = false))
                }
                Text(
                    if (limited) "Лимитный · расходует ГБ" else "Безлимит",
                    fontSize = 11.sp, color = if (limited) Orange else c.green,
                )
            }
            // On hover: star it (or not any more), as the swipe on iPhone.
            if (hovered) {
                Box(Modifier.size(34.dp).clip(CircleShape).press(scale = 0.85f, onClick = onFavorite), contentAlignment = Alignment.Center) {
                    Icon(
                        if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                        if (favorite) "Убрать из избранного" else "В избранное",
                        Modifier.size(19.dp), tint = if (favorite) Yellow else c.tertiary,
                    )
                }
            }
            // Click the ping to measure just this server (again to stop).
            Row(
                Modifier.heightIn(min = 44.dp).widthIn(min = 52.dp).clip(RoundedCornerShape(10.dp))
                    .press(scale = 0.92f, onClick = onPing).padding(horizontal = 6.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (measuring) {
                    Spinner(13.dp, stroke = 1.5.dp)
                    if (ping != null) Spacer(Modifier.width(5.dp))
                }
                when {
                    ping != null -> Text(
                        if (ping >= 0) "$ping мс" else "таймаут", fontSize = 14.sp, color = pingColor(ping), style = Tabular,
                        modifier = Modifier.graphicsLayer { alpha = if (measuring) 0.5f else 1f },
                    )
                    !measuring -> Icon(Icons.Default.Speed, "Пинг", Modifier.size(18.dp), tint = c.tertiary)
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.size(26.dp).border(if (selected) 2.dp else 1.5.dp, if (selected) Brand else c.outline, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) Box(Modifier.size(13.dp).clip(CircleShape).background(Brand))
            }
        }
    }
}

/** One line; only a name that doesn't fit scrolls (after a pause) with faded edges. */
@Composable
private fun MarqueeText(text: String, fontSize: TextUnit, fontWeight: FontWeight, modifier: Modifier = Modifier) {
    var overflow by remember(text) { mutableStateOf(false) }
    Text(
        text,
        modifier.then(
            if (overflow) Modifier
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = 12.dp.toPx().coerceAtMost(size.width / 4)
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, fade / size.width to Color.Black,
                            1f - fade / size.width to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }
                .basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 2500)
            else Modifier
        ),
        fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false,
        onTextLayout = { if (it.hasVisualOverflow) overflow = true },
    )
}

// ------------------------------------------------------------------ subscription

/** A page's title with an action on the right (Подписка, Настройки). */
@Composable
private fun PageTitle(title: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

/** A scrolling page, 640 wide at most, in the middle. */
@Composable
private fun Page(content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
            content = content,
        )
    }
}

@Composable
private fun SubscriptionPage(state: AppState, sub: Subscription) {
    val uri = LocalUriHandler.current
    val c = Palette.colors
    val info = sub.info
    // The cabinet in the app, signed in by the subscription's link (no email / Telegram login).
    var showCabinet by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { state.loadAccount() }
    val cabinetLink = info.webPageUrl
    if (showCabinet && cabinetLink != null) {
        CabinetPage(cabinetLink, state.account?.let { it.devices.size to it.deviceLimit }) { showCabinet = false }
        return
    }
    val now = System.currentTimeMillis() / 1000
    val active = isActive(info)
    Page {
        PageTitle("Подписка") {
            CircleIconButton(Icons.Default.Refresh, "Обновить подписку", { state.refresh() }, spinning = state.busy, size = 42.dp)
        }
        Spacer(Modifier.height(8.dp))
        TitanCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(if (active) "Подписка активна" else "Подписка истекла", if (active) c.green else Red)
                Text(if (info.expireAt > 0) "до " + Fmt.date(info.expireAt) else "Бессрочно", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (info.expireAt > 0) Text("Осталось ${((info.expireAt - now) / 86400).coerceAtLeast(0)} дн.", color = c.secondary)
                Text("Обновлено " + Fmt.ago(sub.fetchedAt), fontSize = 12.sp, color = c.tertiary)
            }
        }
        Spacer(Modifier.height(12.dp))
        TitanCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Трафик на обходах", fontSize = 14.sp, color = c.secondary)
                if (info.totalBytes > 0) {
                    Text("${formatBytes(info.remainingBytes)} из ${formatBytes(info.totalBytes)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    GradientBar(
                        info.remainingBytes.toFloat() / info.totalBytes,
                        if (info.remainingBytes * 10 < info.totalBytes) Brush.horizontalGradient(listOf(Orange, Red)) else Palette.accent,
                    )
                } else {
                    Text("Безлимит", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Text("На обычных серверах — безлимит", fontSize = 13.sp, color = c.secondary)
                Text(
                    "Обходы — серверы для мобильного интернета, когда оператор пропускает только разрешённые сайты " +
                        "(белые списки) и обычные серверы не работают. Работают и по Wi‑Fi, например при раздаче интернета с телефона. " +
                        "Гигабайты списываются, пока вы подключены к обходу. Закончились ГБ — докупите в личном кабинете.",
                    fontSize = 12.sp, color = c.secondary, lineHeight = 16.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            "Личный кабинет",
            { if (cabinetLink != null) showCabinet = true else uri.openUri(state.openCabinetUrl()) },
            icon = Icons.Default.AccountCircle,
        )
        state.account?.takeIf { it.devicesEnabled }?.let { acc ->
            Spacer(Modifier.height(12.dp))
            TitanCard {
                SettingsRow(Icons.Default.Devices, Brand, "Подключённые устройства", chevron = true, onClick = { showDevices = true }) {
                    Text("${acc.devices.size}" + if (acc.deviceLimit > 0) " из ${acc.deviceLimit}" else "", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.secondary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        TitanCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("В личном кабинете", fontWeight = FontWeight.SemiBold)
                Feature(Icons.Default.Autorenew, "Продление подписки")
                Feature(Icons.Default.DataUsage, "Докупка ГБ для обходов")
                Feature(Icons.Default.Loyalty, "Коды оплаты")
            }
        }
        Spacer(Modifier.height(12.dp))
        // What the in-app cabinet can't do: the bot or the full cabinet on the site.
        TitanCard {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Расширенные функции", fontWeight = FontWeight.SemiBold)
                Text(
                    "Изменение лимита устройств, отвязка устройств, рефералы и история платежей — в боте " +
                        "или в полном кабинете на сайте (вход по почте или через Telegram).",
                    fontSize = 13.sp, color = c.secondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Открыть бота", { uri.openUri(Config.TELEGRAM_URL) }, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.Send)
                    cabinetLink?.let { runCatching { java.net.URI(it) }.getOrNull() }?.takeIf { it.host != null }?.let { site ->
                        SecondaryButton("Открыть сайт", { uri.openUri("${site.scheme}://${site.host}/auth") }, Modifier.weight(1f), icon = Icons.Default.Language)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
    if (showDevices) state.account?.let { DevicesDialog(it) { showDevices = false } }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = Brand)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp)
    }
}

/** The devices that used the key, and the limit (from the bot). */
@Composable
private fun DevicesDialog(account: Account.Info, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val c = Palette.colors
    TitanDialog(
        onDismiss, "Устройства",
        "Подключено ${account.devices.size}" + if (account.deviceLimit > 0) " из ${account.deviceLimit}" else "",
        icon = Icons.Default.Devices, width = 480.dp,
        buttons = {
            SecondaryButton("Открыть бота", { uri.openUri(Config.TELEGRAM_URL) }, icon = Icons.AutoMirrored.Filled.Send)
            SecondaryButton("Закрыть", onDismiss, tint = c.secondary)
        },
    ) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.background)) {
            if (account.devices.isEmpty()) {
                Text("Пока ни одного устройства", color = c.secondary, modifier = Modifier.padding(16.dp))
            }
            account.devices.forEachIndexed { i, d ->
                if (i > 0) RowDivider(66.dp)
                val os = d.os.lowercase()
                val icon = when {
                    "ios" in os || "iphone" in d.model.lowercase() -> Icons.Default.PhoneIphone
                    "android" in os -> Icons.Default.PhoneAndroid
                    else -> Icons.Default.Computer
                }
                val ours = Account.appName(d.app).startsWith("Titan VPS") && d.model.isNotEmpty() &&
                    "windows" in os && Hwid.model().let { m -> m.isNotEmpty() && (d.model.contains(m, true) || m.contains(d.model, true)) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(icon, Brand, 38.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.model.ifEmpty { "Устройство" } + if (ours) " · это устройство" else "", fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(listOf(d.os, d.osVersion).filter { it.isNotEmpty() }.joinToString(" "), Account.appName(d.app))
                                .filter { it.isNotEmpty() }.joinToString(" · "),
                            fontSize = 12.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Account.lastSeen(d.lastSeen)?.let { Text("Активность: $it", fontSize = 12.sp, color = c.secondary) }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Отвязать лишнее устройство или изменить лимит можно в боте.", fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

// ------------------------------------------------------------------ settings

@Composable
private fun SettingsPage(state: AppState, openSubscription: () -> Unit, openHome: () -> Unit) {
    val uri = LocalUriHandler.current
    val c = Palette.colors
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showTech by remember { mutableStateOf(false) }
    if (showApps) { AppsPage(state) { showApps = false; state.applyExclusions() }; return }
    if (showTech) { TechModePage(state) { showTech = false }; return }
    Page {
        PageTitle("Настройки")
        state.subscription?.let { AccountCard(state, it, openSubscription) }

        SectionTitle("Оформление")
        TitanCard {
            OptionTiles(
                listOf(
                    Triple("system", "Системная", Icons.Default.SettingsBrightness),
                    Triple("light", "Светлая", Icons.Default.LightMode),
                    Triple("dark", "Тёмная", Icons.Default.DarkMode),
                ),
                state.theme, state::changeTheme,
            )
        }

        SectionTitle("Подключение")
        TitanCard {
            SettingsRow(Icons.Default.Dns, Brand, "Сервер", chevron = true, onClick = openHome) {
                Text(
                    state.selected?.let { ServerGroups.splitFlag(it.name).second } ?: "Автоматически",
                    fontSize = 15.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp),
                )
            }
            RowDivider()
            ToggleRow(
                Icons.Default.Bolt, c.green, "Автоподключение", "VPN включится сам при запуске приложения",
                state.autoConnect, state::changeAutoConnect,
            )
            RowDivider()
            ToggleRow(
                Icons.Default.PowerSettingsNew, Violet, "Запускать вместе с Windows",
                if (state.mode == "tun") "С правами администратора, без запроса при входе — если включить из режима TUN" else "Приложение откроется при входе в Windows",
                state.autostart, state::changeAutostart,
            )
            RowDivider()
            ToggleRow(
                Icons.Default.NotificationsActive, Orange, "Напоминания",
                "За 3 дня и за день до конца подписки и когда на обходах кончается трафик",
                state.reminders, state::changeReminders,
            )
        }

        SectionTitle("Режим подключения")
        TitanCard {
            OptionTiles(
                listOf(
                    Triple("tun", "Весь компьютер", Icons.Default.Computer),
                    Triple("proxy", "Системный прокси", Icons.Default.Language),
                ),
                state.mode, state::changeMode,
            )
            Text(
                if (state.mode == "tun")
                    "Через VPN идёт весь трафик компьютера: браузеры, игры, Discord, торренты. Нужны права администратора — " +
                        "Windows спросит при включении. Отдельные программы можно пустить мимо VPN."
                else
                    "Через VPN идут браузеры и программы, которые используют системный прокси. Игры и часть приложений " +
                        "его не используют — для них выберите «Весь компьютер».",
                fontSize = 12.sp, color = c.secondary, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            )
            if (state.mode == "tun") {
                RowDivider(0.dp)
                SettingsRow(
                    Icons.Default.CallSplit, Brand, "Раздельное туннелирование",
                    (if (state.appsMode == "only") "Только выбранные программы через VPN" else "Выбранные программы мимо VPN") +
                        if (state.pickedApps.isNotEmpty()) " · ${state.pickedApps.size}" else "",
                    chevron = true, onClick = { showApps = true },
                )
            }
        }

        SectionTitle("Приложение")
        TitanCard {
            SettingsRow(Icons.Default.Headphones, Brand, "Поддержка", "Ответим в Telegram", onClick = {
                uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL)
            }) { Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(16.dp), tint = c.tertiary) }
            RowDivider()
            SettingsRow(
                Icons.Default.Refresh, c.green, "Обновить подписку", state.subscription?.let { "Обновлено " + Fmt.ago(it.fetchedAt) },
                onClick = { if (!state.busy) state.refresh() },
            ) { if (state.busy) Spinner(18.dp) }
            RowDivider()
            SettingsRow(Icons.Default.SystemUpdate, Brand, "Обновление приложения", "Новая версия скачивается через VPN", chevron = true, onClick = { state.checkUpdate() })
            RowDivider()
            SettingsRow(Icons.Default.Folder, Color.Gray, "Папка с логами", chevron = true, onClick = {
                runCatching { java.awt.Desktop.getDesktop().open(AppPaths.logDir) }
            })
            RowDivider()
            SettingsRow(Icons.Default.Info, Violet, "О приложении", chevron = true, onClick = { about = true })
            RowDivider()
            // 7 clicks on the version open the hidden «Технический режим» (TechMode.kt).
            TechModeRows(state) { showTech = true }
        }

        Spacer(Modifier.height(18.dp))
        TitanCard {
            SettingsRow(Icons.Default.RestartAlt, Orange, "Сброс настроек", onClick = { confirmReset = true })
            RowDivider()
            SettingsRow(Icons.AutoMirrored.Filled.Logout, Red, "Выйти из аккаунта", titleColor = Red, onClick = { confirmLogout = true })
        }
        Spacer(Modifier.height(12.dp))
    }
    if (about) AboutDialog(state) { about = false }
    if (confirmReset) TitanDialog(
        { confirmReset = false }, "Сбросить настройки?",
        "Тема, избранное, автоподключение и выбранный сервер вернутся к исходным. Ключ останется.",
        icon = Icons.Default.RestartAlt, iconColor = Orange,
        buttons = {
            PrimaryButton("Сбросить", { confirmReset = false; state.resetSettings() }, height = 48.dp)
            SecondaryButton("Отмена", { confirmReset = false }, tint = c.secondary)
        },
    )
    if (confirmLogout) TitanDialog(
        { confirmLogout = false }, "Выйти из аккаунта?",
        "VPN отключится, ключ нужно будет вставить заново.",
        icon = Icons.AutoMirrored.Filled.Logout, iconColor = Red,
        buttons = {
            SecondaryButton("Выйти", { confirmLogout = false; state.logout() }, tint = Red, height = 48.dp)
            SecondaryButton("Отмена", { confirmLogout = false }, tint = c.secondary)
        },
    )
}

/** Who this is: the subscription's state and devices; a click opens Подписка. */
@Composable
private fun AccountCard(state: AppState, sub: Subscription, openSubscription: () -> Unit) {
    val c = Palette.colors
    val active = isActive(sub.info)
    val shape = RoundedCornerShape(20.dp)
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).clip(shape).background(c.surface)
            .border(1.dp, Brand.copy(alpha = if (hovered) 0.45f else 0.25f), shape)
            .press(scale = 0.98f, onHover = { hovered = it }, onClick = openSubscription).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(c.track), contentAlignment = Alignment.Center) {
            Image(painterResource("logo.png"), null, Modifier.size(44.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Titan VPS", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (active) c.green else Red))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (!active) "Подписка истекла" else if (sub.info.expireAt > 0) "Активна до " + Fmt.date(sub.info.expireAt) else "Подписка бессрочная",
                    fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (active) c.text else Red,
                )
            }
            state.account?.takeIf { it.devicesEnabled }?.let { acc ->
                Text("Устройства: ${acc.devices.size}" + if (acc.deviceLimit > 0) " из ${acc.deviceLimit}" else "", fontSize = 13.sp, color = c.secondary)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = c.tertiary)
    }
}

/** Системная / Светлая / Тёмная (or the connection modes) as tiles; the picked one in the gradient. */
@Composable
private fun OptionTiles(options: List<Triple<String, String, ImageVector>>, selected: String, onSelect: (String) -> Unit) {
    val c = Palette.colors
    Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (id, title, icon) ->
            val on = id == selected
            val shape = RoundedCornerShape(14.dp)
            var hovered by remember { mutableStateOf(false) }
            val color by animateColorAsState(if (on) Color.White else if (hovered) c.text else c.secondary)
            Column(
                Modifier.weight(1f).heightIn(min = 66.dp)
                    .then(
                        if (on) Modifier.shadow(4.dp, shape, ambientColor = Brand.copy(alpha = 0.35f), spotColor = Brand.copy(alpha = 0.4f)).clip(shape).background(Palette.accent)
                        else Modifier.clip(shape).background(if (hovered) c.track else Color.Transparent)
                    )
                    .press(scale = 0.95f, onHover = { hovered = it }) { if (!on) onSelect(id) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Icon(icon, null, Modifier.size(20.dp), tint = color)
                Spacer(Modifier.height(6.dp))
                Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
            }
        }
    }
}

/** О приложении: the logo, the version and where to write. */
@Composable
private fun AboutDialog(state: AppState, onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    TitanDialog(
        onDismiss, "Titan VPS", "Версия ${System.getProperty("jpackage.app-version") ?: "dev"}",
        width = 380.dp,
        header = { GlowingLogo(84.dp) },
        buttons = {
            PrimaryButton("Telegram-бот", { uri.openUri(Config.TELEGRAM_URL) }, icon = Icons.AutoMirrored.Filled.Send)
            SecondaryButton("Поддержка", { uri.openUri(state.subscription?.info?.supportUrl ?: Config.TELEGRAM_URL) }, icon = Icons.Default.Headphones)
            SecondaryButton("Закрыть", onDismiss, tint = Palette.colors.secondary)
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Row(
                Modifier.clip(CircleShape).background(Brand.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Memory, null, Modifier.size(16.dp), tint = Brand)
                Spacer(Modifier.width(6.dp))
                Text("Ядро Xray", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Brand)
            }
        }
    }
}

/** TUN mode: programs that bypass the VPN, or the only ones that use it; running ones + any .exe picked by hand. */
@Composable
private fun AppsPage(state: AppState, onBack: () -> Unit) {
    val c = Palette.colors
    var apps by remember { mutableStateOf<List<RunningApps.App>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { RunningApps.list() } }
    val excluded = state.pickedApps
    val only = state.appsMode == "only"
    // Picked ones first (also those not running now), then the running ones.
    val rows = (excluded.sortedBy { it.lowercase() }.map { e -> apps.firstOrNull { it.exe.equals(e, true) } ?: RunningApps.App(e, "") } +
        apps.filter { a -> excluded.none { it.equals(a.exe, true) } })
        .filter { query.isBlank() || query.trim().lowercase() in it.exe.lowercase() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 700.dp).fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack, size = 40.dp)
                Spacer(Modifier.width(12.dp))
                Text("Раздельное туннелирование", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            SegmentedTabs(
                listOf("Все, кроме выбранных" to Icons.Default.CallSplit, "Только выбранные" to Icons.Default.AppRegistration),
                if (only) 1 else 0,
                { state.changeAppsMode(if (it == 1) "only" else "exclude") },
            )
            Text(
                (if (only) "Через VPN идут только отмеченные программы. Остальные — напрямую, мимо VPN."
                else "Отмеченные программы идут в интернет напрямую, мимо VPN. Остальные — через VPN.") +
                    " Изменения применятся при выходе с этой страницы.",
                fontSize = 12.sp, color = c.secondary, modifier = Modifier.padding(horizontal = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.weight(1f))
                SecondaryButton("Добавить .exe", {
                    val fd = java.awt.FileDialog(null as java.awt.Frame?, "Выберите программу", java.awt.FileDialog.LOAD)
                    fd.setFilenameFilter { _, name -> name.endsWith(".exe", true) }
                    fd.file = "*.exe"
                    fd.isVisible = true
                    fd.file?.let { state.setAppPicked(it, true) }
                }, Modifier.width(170.dp), icon = Icons.Default.Add, height = 42.dp)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(rows, key = { it.exe.lowercase() }) { app ->
                    val on = excluded.any { it.equals(app.exe, true) }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                            .rowClick { state.setAppPicked(app.exe, !on) }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconTile(Icons.Default.AppRegistration, if (on) Brand else Color.Gray, 34.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.exe, fontWeight = FontWeight.Medium)
                            if (app.path.isNotEmpty()) Text(app.path, fontSize = 11.sp, color = c.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TitanSwitch(on, { state.setAppPicked(app.exe, it) })
                    }
                }
            }
        }
    }
}
