package com.titanvps.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.tappableElement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.titanvps.app.BuildConfig
import com.titanvps.app.ui.kit.AppNav
import com.titanvps.app.ui.kit.AuroraBackground
import com.titanvps.app.ui.kit.GlowingLogo
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.ToastKind
import com.titanvps.app.ui.kit.Toasts
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.LogoGradient
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp
import com.titanvps.app.ui.theme.iosSpring
import com.titanvps.app.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun TitanScreen(viewModel: MainViewModel, onConnect: () -> Unit) {
    val subscription by viewModel.subscription.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val pendingTab by AppNav.pendingTab.collectAsState()

    val context = LocalContext.current
    // Links prepared by the view model (Личный кабинет).
    LaunchedEffect(Unit) { viewModel.openUrl.collect { context.openUrl(it) } }
    ConnectHaptics(viewModel)

    // The view model's messages come up as toasts.
    LaunchedEffect(message) {
        message?.let {
            Toasts.show(it, toastKind(it))
            viewModel.consumeMessage()
        }
    }

    val sub = subscription
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var overlay by rememberSaveable { mutableStateOf<Overlay?>(null) }
    // Diagnostics opened from «Технический режим» goes back there.
    var logsFromTech by rememberSaveable { mutableStateOf(false) }

    // Signed out: a new key starts on Главная, nothing left open (as iOS).
    LaunchedEffect(sub == null) {
        if (sub == null) {
            tab = MainTab.HOME
            overlay = null
        }
    }

    // A tab asked for from outside (a reminder's notification): over anything open.
    LaunchedEffect(pendingTab) {
        val target = when (pendingTab) {
            AppNav.HOME -> MainTab.HOME
            AppNav.SUBSCRIPTION -> MainTab.SUBSCRIPTION
            AppNav.PROFILE -> MainTab.PROFILE
            else -> return@LaunchedEffect
        }
        tab = target
        overlay = null
        AppNav.consume()
    }

    // Launch screen, once per cold start; then it fades into the app.
    var splash by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) { if (splash) { delay(900); splash = false } }

    Crossfade(targetState = splash, animationSpec = tween(350), label = "splash") { showSplash ->
        if (showSplash) {
            SplashScreen()
            return@Crossfade
        }
        // Update flow (from settings or the "new version" notification), on any tab.
        UpdateDialog(viewModel)
        if (sub == null) {
            WelcomeScreen(viewModel, busy)
            return@Crossfade
        }
        // Each tab's scroll, kept while it's off screen; a tap on the tab it's on takes it to the top.
        val homeList = rememberLazyListState()
        val subscriptionScroll = rememberScrollState()
        val profileScroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val haptics = rememberHaptics()
        // The tabs side by side, swiped between (TabSwipe); none while a screen is open over them,
        // back on the tab it was opened from.
        val pager = if (overlay == null) rememberPagerState(initialPage = tab.ordinal) { MainTab.entries.size } else null
        if (pager != null) {
            // Settled on another tab after a swipe: that's the tab.
            LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { tab = MainTab.entries[it] } }
            // Picked any other way (the bar, Назад, a reminder, a row on Профиль): there at once.
            LaunchedEffect(pager, tab) { if (pager.currentPage != tab.ordinal) pager.scrollToPage(tab.ordinal) }
        }
        // The tab on screen, or the one a swipe is going to while it goes.
        val shown = pager?.let { MainTab.entries[it.targetPage] } ?: tab
        val open: (MainTab) -> Unit = { t ->
            tab = t
            pager?.let { scope.launch { it.scrollToPage(t.ordinal) } }
        }
        // A screen over the tabs: Назад comes back to the tab it was opened on, even one a swipe was
        // still settling on (the pager is gone meanwhile, and `tab` follows it only once settled).
        val openOverlay: (MainTab, Overlay) -> Unit = { from, o ->
            tab = from
            overlay = o
        }
        // Back on Подписка or Профиль goes to Главная first.
        BackHandler(enabled = overlay == null && shown != MainTab.HOME) { open(MainTab.HOME) }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (pager != null) {
                    BottomBar(
                        tab = shown,
                        onSelect = open,
                        onReselect = { t ->
                            if (!pager.isScrollInProgress) {
                                scope.launch {
                                    when (t) {
                                        MainTab.HOME -> homeList.animateScrollToItem(0)
                                        MainTab.SUBSCRIPTION -> subscriptionScroll.animateScrollTo(0)
                                        MainTab.PROFILE -> profileScroll.animateScrollTo(0)
                                    }
                                }
                            }
                        },
                    )
                }
            },
        ) { padding ->
            // Scaffold's padding covers the bars; consume it so safeDrawing (e.g. IME) isn't added twice.
            Box(Modifier.padding(padding).consumeWindowInsets(padding).safeDrawingPadding().fillMaxSize()) {
                when {
                    overlay == Overlay.APPS -> AppsScreen(viewModel) { overlay = null; viewModel.reconnectIfConnected() }
                    overlay == Overlay.DIAGNOSTICS -> DiagnosticsScreen {
                        overlay = if (logsFromTech) Overlay.TECH else null
                        logsFromTech = false
                    }
                    overlay == Overlay.TECH -> TechModeScreen(
                        viewModel, sub,
                        onOpenLogs = { logsFromTech = true; overlay = Overlay.DIAGNOSTICS },
                    ) { overlay = null }
                    overlay == Overlay.DEVICES -> DevicesScreen(viewModel) { overlay = null }
                    pager != null -> HorizontalPager(
                        state = pager,
                        modifier = Modifier.fillMaxSize().tabSwipe(pager) { haptics.selection() },
                        // Only TabSwipe moves it: the pages' own gestures go first.
                        userScrollEnabled = false,
                        pageNestedScrollConnection = NoPagerNestedScroll,
                    ) { page ->
                        when (MainTab.entries[page]) {
                            MainTab.HOME -> HomeScreen(viewModel, sub, busy, onConnect, homeList)
                            MainTab.SUBSCRIPTION -> SubscriptionScreen(
                                viewModel, sub, subscriptionScroll,
                                onOpenDevices = { openOverlay(MainTab.SUBSCRIPTION, Overlay.DEVICES) },
                            )
                            MainTab.PROFILE -> ProfileScreen(
                                viewModel, sub, profileScroll,
                                onOpenApps = { openOverlay(MainTab.PROFILE, Overlay.APPS) },
                                onOpenDiagnostics = { openOverlay(MainTab.PROFILE, Overlay.DIAGNOSTICS) },
                                onOpenHome = { open(MainTab.HOME) },
                                onOpenTech = { openOverlay(MainTab.PROFILE, Overlay.TECH) },
                                onOpenSubscription = { open(MainTab.SUBSCRIPTION) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A key added or the settings reset: a tick. Everything else the view model says is a refusal
 * (a bypass on Wi-Fi, NetworkMonitor.BYPASS_WIFI_MESSAGE) or a request that failed («Не удалось…»,
 * «Что-то пошло не так…», the key or the subscription's server): a warning.
 */
private fun toastKind(text: String): ToastKind =
    if (text == "Подписка подключена" || text == "Настройки сброшены") ToastKind.SUCCESS else ToastKind.WARNING

/** Filled in either state, as the iPhone's bar: the colour shows the picked one. */
private enum class MainTab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Filled.Home),
    SUBSCRIPTION("Подписка", TitanIcons.Crown),
    PROFILE("Профиль", Icons.Filled.Person),
}

private enum class Overlay { APPS, DIAGNOSTICS, DEVICES, TECH }

/**
 * The user's own Вкл / Выкл felt when it's done: success once connected, a light tap once off,
 * an error if the connect failed (ConnectFeedback). Only while the app is on screen: leaving it
 * forgets what was asked, so nothing the VPN comes to meanwhile is felt, then or on return.
 */
@Composable
private fun ConnectHaptics(viewModel: MainViewModel) {
    val haptics = rememberHaptics()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalContext.current as? Activity
    LaunchedEffect(viewModel, lifecycle) {
        val feedback = viewModel.connectFeedback
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                viewModel.vpnState.collect { state ->
                    when (feedback.onState(state, SystemClock.elapsedRealtime())) {
                        ConnectFeedback.Outcome.CONNECTED -> haptics.success()
                        ConnectFeedback.Outcome.DISCONNECTED -> haptics.tap()
                        ConnectFeedback.Outcome.FAILED -> haptics.error()
                        null -> Unit
                    }
                }
            } finally {
                // Turned, not left: the connect goes on in the new screen.
                if (activity?.isChangingConfigurations != true) feedback.forget()
            }
        }
    }
}

/** The logo rising out of the aurora, «Titan VPS» in the logo's colours (iOS SplashView). */
@Composable
private fun SplashScreen() {
    val palette = Titan.palette
    val reduceMotion = rememberReduceMotion()
    val appear = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, iosSpring(0.6f, 0.72f)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AuroraBackground()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            GlowingLogo(
                150.dp,
                Modifier.graphicsLayer {
                    val p = appear.value
                    scaleX = 0.8f + 0.2f * p
                    scaleY = 0.8f + 0.2f * p
                    alpha = p.coerceIn(0f, 1f)
                },
            )
            Column(
                Modifier.graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    translationY = (1f - appear.value) * 14.dp.toPx()
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "Titan VPS",
                    style = TextStyle(brush = LogoGradient, fontSize = fixedSp(40f), fontWeight = FontWeight.ExtraBold),
                )
                Text(
                    "Подключение в одно касание",
                    color = palette.textSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * Главная / Подписка / Профиль: a thin bar on the surface with a one-pixel hairline on top,
 * down to the screen's edge; the picked tab in the brand blue (iOS TitanTabBar). The picked one
 * tapped again: [onReselect] (its list to the top).
 */
@Composable
private fun BottomBar(tab: MainTab, onSelect: (MainTab) -> Unit, onReselect: (MainTab) -> Unit) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    // Of gesture navigation's inset (the handle, 24 dp) 18 under the items (48 dp tall, a touch
    // target: the iPhone's 46 over 20 of its home indicator), so the bar sits lower. The system takes only swipes there: the items'
    // lower edge in it still gets taps, and the padding below them isn't tappable, so a swipe home
    // doesn't press a tab. Buttons (3-button navigation, a taskbar) are tappable insets: all of it.
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val buttons = WindowInsets.tappableElement.asPaddingValues().calculateBottomPadding()
    val bottom = maxOf(buttons, minOf(nav, 18.dp))
    val hairline = with(LocalDensity.current) { 1.toDp() }
    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        Box(Modifier.fillMaxWidth().height(hairline).background(palette.outline))
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                // A navigation bar on the side (landscape) isn't covered.
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
                // On a tablet the items stay together in the middle.
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(bottom = bottom)
                .heightIn(min = 48.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { t ->
                val selected = t == tab
                val color = if (selected) palette.primary else palette.textSecondary
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .pressable(
                            onClick = {
                                if (!selected) {
                                    haptics.selection()
                                    onSelect(t)
                                } else {
                                    onReselect(t)
                                }
                            },
                            scale = 0.92f,
                            role = Role.Tab,
                        )
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, null, Modifier.size(22.dp), tint = color)
                    Spacer(Modifier.height(2.dp))
                    Text(t.title, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
internal fun GradientButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    PrimaryButton(text, onClick, icon = icon)
}

internal fun android.content.Context.openUrl(url: String) {
    val opened = runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    // No browser (some car head units): fall back to the bot, where Telegram is installed.
    if (!opened && url != BuildConfig.TELEGRAM_URL) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.TELEGRAM_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val s = TimeUnit.MILLISECONDS.toSeconds(ms)
    return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}

internal fun formatBytes(bytes: Long): String {
    val gb = bytes / 1024.0 / 1024.0 / 1024.0
    return if (gb >= 1) "%.1f ГБ".format(gb) else "%.0f МБ".format(bytes / 1024.0 / 1024.0)
}

internal fun formatExpiry(expireAt: Long): String {
    if (expireAt <= 0) return "бессрочно"
    val date = SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(expireAt * 1000))
    val days = TimeUnit.SECONDS.toDays(expireAt - System.currentTimeMillis() / 1000)
    return if (days >= 0) "$date ($days дн.)" else "$date (истекла)"
}
