package com.titanvps.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.R
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerGroups.Group
import com.titanvps.app.data.ServerOrder
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.home.ListCaption
import com.titanvps.app.ui.home.PowerSlider
import com.titanvps.app.ui.home.ServerRow
import com.titanvps.app.ui.home.ServerTabs
import com.titanvps.app.ui.home.StatusBlock
import com.titanvps.app.ui.home.WifiLockedDialog
import com.titanvps.app.ui.kit.SpinningIcon
import com.titanvps.app.ui.kit.SquareIconButton
import com.titanvps.app.ui.kit.ToastKind
import com.titanvps.app.ui.kit.Toasts
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.vpn.VpnState

/**
 * Главная: header (Поддержка, logo, Обновить подписку), the Выкл | Вкл slider, the status
 * with Проверить соединение, Страны | Обходы tabs with Пинг, and the server list. The
 * subscription's details are on Подписка; here only that it ran out. Обходы: mobile data only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    viewModel: MainViewModel,
    sub: Subscription,
    busy: Boolean,
    onConnect: () -> Unit,
    onOpenSubscription: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val state by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    // Read per row, not here: a ping result redraws only its own row.
    val pingsState = viewModel.pings.collectAsState()
    val measuringState = viewModel.measuring.collectAsState()
    val errorsState = viewModel.pingErrors.collectAsState()
    val progressState = viewModel.pingProgress.collectAsState()
    val pinging by viewModel.pinging.collectAsState()
    val check by viewModel.connectionCheck.collectAsState()
    val account by viewModel.account.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val mobileNet by viewModel.onMobile.collectAsState()
    val techMode by viewModel.techMode.collectAsState()
    // Bypass servers: mobile internet only, any network in Технический режим.
    val wifiLocked = !(mobileNet || techMode)
    var wifiInfo by remember { mutableStateOf(false) }

    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()
    val groups = remember(sub.servers) { ServerGroups.split(sub.servers).toMap() }
    var page by rememberSaveable(sub.servers) {
        mutableStateOf(selected?.let { ServerGroups.groupOf(it, sub.servers) } ?: Group.SERVERS)
    }
    if (groups[page].isNullOrEmpty()) page = Group.SERVERS
    // Favourites first, the subscription's order otherwise (as the ‹ › steps).
    val shown = remember(groups, page, favorites) { ServerOrder.ordered(groups[page].orEmpty(), favorites) }
    val counts = remember(groups) { groups.map { (group, list) -> group to list.size } }
    val selectedPing by remember(selected?.id) { derivedStateOf { selected?.id?.let { pingsState.value[it] } } }
    val connected = state is VpnState.Connected
    val expired = sub.info.expireAt > 0 && sub.info.expireAt <= System.currentTimeMillis() / 1000
    val supportUrl = sub.info.supportUrl ?: BuildConfig.TELEGRAM_URL

    // The slider moved: false when it can't be done now (the knob goes back with a shake).
    val onToggle: (Boolean) -> Boolean = { on ->
        val now = viewModel.vpnState.value
        val active = now is VpnState.Connected || now == VpnState.Connecting
        when {
            on && !active -> viewModel.connectRefusal()?.let { refusal ->
                Toasts.show(refusal, ToastKind.WARNING)
                false
            } ?: run {
                onConnect()
                true
            }
            !on && active -> {
                viewModel.disconnect()
                true
            }
            else -> true
        }
    }
    val bannerText = when (account?.status) {
        "BLOCKED" -> "Аккаунт заблокирован. Напишите в поддержку"
        "MAINTENANCE" -> "Идут технические работы. Подключение может не работать"
        else -> null
    }
    val topItems: LazyListScope.() -> Unit = {
        if (bannerText != null) {
            item(key = "home:account") {
                AccountBanner(bannerText, blocked = account?.status == "BLOCKED") { context.openUrl(supportUrl) }
            }
        }
        if (expired) item(key = "home:expired") { ExpiredBanner(onOpenSubscription) }
        // Room round the slider for its glow.
        item(key = "home:slider") { PowerSlider(state, onToggle, Modifier.padding(vertical = 6.dp)) }
        item(key = "home:status") {
            StatusBlock(
                state = state,
                serverName = selected?.name,
                ping = selectedPing,
                bypassOnWifi = wifiLocked && selected != null && ServerGroups.isBypass(selected),
                check = check,
                techMode = techMode,
                onCheck = { viewModel.checkConnection() },
            )
        }
    }
    val listItems: LazyListScope.() -> Unit = {
        // The tabs and the lines under them kept close: more rows on screen.
        item(key = "home:tabs") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ServerTabs(
                    counts = counts,
                    page = page,
                    onPage = { page = it },
                    pinging = pinging,
                    progress = { progressState.value },
                    // In the order shown (favourites first): results fill the list from the top.
                    onPing = { viewModel.pingAll(shown.map { it.id }) },
                )
                ListCaption(page, sub.info, wifiLocked, onBuy = { viewModel.openCabinet() })
            }
        }
        // Read once for the list, not per row.
        val selectedServerId = selected?.id
        val bypassPage = page == Group.BYPASS
        items(shown, key = { it.id }) { s ->
            val id = s.id
            val ping by remember(id) { derivedStateOf { pingsState.value[id] } }
            val measuring by remember(id) { derivedStateOf { id in measuringState.value } }
            val error by remember(id) { derivedStateOf { errorsState.value[id] } }
            val locked = wifiLocked && ServerGroups.isBypass(s)
            ServerRow(
                name = s.name,
                ping = ping,
                measuring = measuring,
                error = error,
                selected = id == selectedServerId,
                connected = connected && id == selectedServerId,
                favorite = s.name in favorites,
                locked = locked,
                limited = bypassPage && ServerGroups.isLimited(s),
                techMode = techMode,
                onSelect = {
                    if (locked) {
                        haptics.warning()
                        wifiInfo = true
                    } else {
                        if (id != viewModel.selectedId.value) haptics.selection()
                        // Connected: the view model moves the VPN to it.
                        viewModel.select(id)
                    }
                },
                onPing = { viewModel.pingOne(id) },
                onFavorite = { viewModel.toggleFavorite(s.name) },
                // Favourites move to the top: the row slides there.
                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
            )
        }
    }
    val listPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp)

    // Phones: one column up to 640dp, centered. Tablets / landscape: controls on the
    // left, the server list on the right.
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        if (maxWidth >= 720.dp) {
            Column(Modifier.widthIn(max = 1200.dp).fillMaxSize()) {
                HomeHeader(busy, { context.openUrl(supportUrl) }, { viewModel.refreshByHand() }, Modifier.fillMaxWidth())
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        Modifier.weight(0.9f).fillMaxHeight(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = topItems,
                    )
                    PullToRefreshBox(
                        isRefreshing = busy,
                        onRefresh = { viewModel.refreshByHand() },
                        modifier = Modifier.weight(1.1f).fillMaxHeight(),
                    ) {
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
                HomeHeader(busy, { context.openUrl(supportUrl) }, { viewModel.refreshByHand() }, Modifier.fillMaxWidth())
                PullToRefreshBox(
                    isRefreshing = busy,
                    onRefresh = { viewModel.refreshByHand() },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = listPadding,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        topItems()
                        listItems()
                    }
                }
            }
        }
    }

    if (wifiInfo) WifiLockedDialog { wifiInfo = false }
}

/** Поддержка, the logo with "Titan VPS", Обновить подписку (the theme is in Настройки). */
@Composable
private fun HomeHeader(busy: Boolean, onSupport: () -> Unit, onRefresh: () -> Unit, modifier: Modifier) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        SquareIconButton(Icons.Outlined.SupportAgent, "Поддержка", onSupport)
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.logo), null, Modifier.size(40.dp))
            Spacer(Modifier.width(8.dp))
            Text("Titan VPS", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        RefreshButton(busy, onRefresh)
    }
}

/** The header's square button with an icon that turns while the subscription is fetched. */
@Composable
private fun RefreshButton(busy: Boolean, onClick: () -> Unit) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(14.dp)
    val shadow = Color.Black.copy(alpha = 0.06f)
    // Looks as SquareIconButton; it has no turning icon.
    Box(
        Modifier
            .pressable(onClick = { if (!busy) onClick() }, scale = 0.9f)
            .semantics { contentDescription = "Обновить подписку" }
            .size(48.dp)
            .shadow(3.dp, shape, clip = false, ambientColor = shadow, spotColor = shadow)
            .clip(shape)
            .background(palette.surface)
            .border(1.dp, palette.outline.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        SpinningIcon(Icons.Filled.Refresh, busy, Modifier.size(19.dp), tint = palette.primary)
    }
}

/** Blocked account / maintenance, from the bot; tap → support. */
@Composable
private fun AccountBanner(text: String, blocked: Boolean, onClick: () -> Unit) {
    val palette = Titan.palette
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, scale = 0.98f)
            .clip(RoundedCornerShape(16.dp))
            .background(if (blocked) palette.red else palette.orange)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, null, Modifier.size(18.dp), tint = Color.White)
        Text(text, Modifier.weight(1f), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** The subscription ran out: one line, a tap opens Подписка (as on iOS). */
@Composable
private fun ExpiredBanner(onClick: () -> Unit) {
    val red = Titan.palette.red
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, scale = 0.98f)
            .semantics(mergeDescendants = true) { contentDescription = "Подписка истекла. Продлить" }
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(red.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Error, null, Modifier.size(18.dp), tint = red)
        Text("Подписка истекла", Modifier.weight(1f), color = red, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text("Продлить", color = red, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = red)
    }
}
