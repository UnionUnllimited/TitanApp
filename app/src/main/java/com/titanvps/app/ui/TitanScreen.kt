package com.titanvps.app.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        // Back on Подписка or Профиль goes to Главная first.
        BackHandler(enabled = overlay == null && tab != MainTab.HOME) { tab = MainTab.HOME }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (overlay == null) BottomBar(tab) { tab = it }
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
                    tab == MainTab.HOME -> HomeScreen(
                        viewModel, sub, busy, onConnect,
                        onOpenSubscription = { tab = MainTab.SUBSCRIPTION },
                    )
                    tab == MainTab.SUBSCRIPTION -> SubscriptionScreen(viewModel, sub, onOpenDevices = { overlay = Overlay.DEVICES })
                    else -> ProfileScreen(
                        viewModel, sub,
                        onOpenApps = { overlay = Overlay.APPS },
                        onOpenDiagnostics = { overlay = Overlay.DIAGNOSTICS },
                        onOpenHome = { tab = MainTab.HOME },
                        onOpenTech = { overlay = Overlay.TECH },
                        onOpenSubscription = { tab = MainTab.SUBSCRIPTION },
                    )
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

private enum class MainTab(val title: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME("Главная", Icons.Outlined.Home, Icons.Filled.Home),
    SUBSCRIPTION("Подписка", TitanIcons.CrownOutlined, TitanIcons.Crown),
    PROFILE("Профиль", Icons.Outlined.Person, Icons.Filled.Person),
}

private enum class Overlay { APPS, DIAGNOSTICS, DEVICES, TECH }

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
 * Главная / Подписка / Профиль: a thin bar on the surface with a hairline on top, right above
 * the system navigation bar; the picked tab in the brand blue.
 */
@Composable
private fun BottomBar(tab: MainTab, onSelect: (MainTab) -> Unit) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    Column(Modifier.fillMaxWidth().background(palette.surface)) {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(palette.outline))
        Row(
            Modifier
                .align(Alignment.CenterHorizontally)
                // On a tablet the items stay together in the middle.
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .heightIn(min = 56.dp)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MainTab.entries.forEach { t ->
                val selected = t == tab
                val color = if (selected) palette.primary else palette.textSecondary
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .pressable(
                            onClick = {
                                if (!selected) {
                                    haptics.selection()
                                    onSelect(t)
                                }
                            },
                            scale = 0.92f,
                            role = Role.Tab,
                        )
                        .semantics { this.selected = selected },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(if (selected) t.selectedIcon else t.icon, null, Modifier.size(22.dp), tint = color)
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
