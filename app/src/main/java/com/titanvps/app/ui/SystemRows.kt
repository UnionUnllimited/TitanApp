package com.titanvps.app.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.ToggleOn
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.reminders.Reminders
import com.titanvps.app.ui.kit.RowDivider
import com.titanvps.app.ui.kit.SettingsRow
import com.titanvps.app.ui.kit.SettingsToggleRow
import com.titanvps.app.ui.kit.ToastKind
import com.titanvps.app.ui.kit.Toasts
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnTileService

// Rows of Настройки about the system (placed there by the settings screen). Each starts with
// its own divider and draws nothing where it doesn't apply.

/** «Не отключать VPN», and what Android itself needs for it. */
@Composable
internal fun KeepAliveRows() {
    val context = LocalContext.current
    val settings = TitanApp.get(context).settings
    val palette = Titan.palette
    val keepAlive by settings.keepAlive.collectAsState()
    // Re-read on return from the system screen.
    var unrestricted by remember { mutableStateOf(ignoresBatteryLimits(context)) }
    LifecycleResumeEffect(Unit) {
        unrestricted = ignoresBatteryLimits(context)
        onPauseOrDispose { }
    }

    RowDivider()
    SettingsToggleRow(
        title = "Не отключать VPN",
        icon = Icons.Filled.VerifiedUser,
        iconColor = palette.green,
        checked = keepAlive,
        onCheckedChange = { settings.setKeepAlive(it) },
        subtitle = "Сам переподключится при смене Wi‑Fi и мобильной сети, после сбоя и перезагрузки",
    )
    if (keepAlive && !unrestricted) {
        RowDivider()
        SettingsRow(
            title = "Работа в фоне",
            icon = Icons.Filled.BatteryChargingFull,
            iconColor = palette.orange,
            onClick = { openBatterySettings(context) },
            subtitle = "Разрешите Titan VPS работать без ограничений батареи, чтобы система его не закрывала",
        )
    }
    if (keepAlive) {
        RowDivider()
        SettingsRow(
            title = "Постоянный VPN Android",
            icon = Icons.Filled.Shield,
            iconColor = palette.gray,
            onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } },
            subtitle = "Системная настройка Android. «Блокировать соединения без VPN» отключит интернет приложениям из «Раздельного туннелирования»",
        )
    }
}

/** «Напоминания»: asks for notifications when they're needed and not allowed. */
@Composable
internal fun RemindersRow() {
    val context = LocalContext.current
    val settings = TitanApp.get(context).settings
    val reminders by settings.reminders.collectAsState()
    // On either way (as on iOS): once notifications are allowed later, the reminders come.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) TitanVpnService.reshow(context)
        else Toasts.show("Разрешите уведомления для Titan VPS в настройках телефона", ToastKind.WARNING)
        settings.setReminders(true)
        Reminders.reschedule(context)
    }

    RowDivider()
    SettingsToggleRow(
        title = "Напоминания",
        icon = Icons.Filled.NotificationsActive,
        iconColor = Titan.palette.orange,
        checked = reminders,
        onCheckedChange = { on ->
            when {
                !on -> {
                    settings.setReminders(false)
                    Reminders.cancelAll(context)
                }
                Build.VERSION.SDK_INT >= 33 &&
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else -> {
                    // Turned off for the app in Android: its settings, the switch goes on anyway.
                    if (!notificationsEnabled(context)) openNotificationSettings(context)
                    settings.setReminders(true)
                    Reminders.reschedule(context)
                }
            }
        },
        subtitle = "За 3 дня и за день до конца подписки и когда на обходах кончается трафик",
    )
}

/**
 * «Уведомление с сервером» (iOS: Live Activity / Dynamic Island): the VPN notification with the
 * server, ‹ › and the numbers, and one while the VPN is off. A hint when Android hides it: the
 * app's notifications or its channel turned off, or (Android 16) its Live Updates.
 */
@Composable
internal fun LiveNotificationRow() {
    val context = LocalContext.current
    val settings = TitanApp.get(context).settings
    val palette = Titan.palette
    val live by settings.liveNotification.collectAsState()
    var blocked by remember { mutableStateOf(notificationBlock(context)) }
    LifecycleResumeEffect(Unit) {
        blocked = notificationBlock(context)
        onPauseOrDispose { }
    }

    RowDivider()
    // TitanApp redraws the notification when it changes.
    SettingsToggleRow(
        title = "Уведомление с сервером",
        icon = Icons.Filled.Smartphone,
        iconColor = palette.violet,
        checked = live,
        onCheckedChange = { settings.setLiveNotification(it) },
        subtitle = "Сервер, скорость и пинг в шторке и на экране блокировки — и когда VPN выключен",
    )
    // Live Updates matter only to the look with the server.
    val hint = blocked.takeIf { it != NotificationBlock.LIVE_UPDATES || live }
    if (hint != null) {
        Column(Modifier.padding(start = 62.dp, end = 16.dp, bottom = 6.dp)) {
            Text(hint.text, fontSize = 12.sp, color = palette.orange)
            TextButton(onClick = { openNotificationSettings(context, hint) }, contentPadding = PaddingValues(0.dp)) {
                Text("Открыть настройки", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** What in Android's settings keeps the VPN notification from showing as it is set. */
private enum class NotificationBlock(val text: String) {
    APP("Уведомления Titan VPS выключены в настройках Android"),
    CHANNEL("Уведомление «VPN-подключение» выключено в настройках Android"),
    LIVE_UPDATES("Live Updates для Titan VPS выключены в настройках Android"),
}

private fun notificationBlock(context: Context): NotificationBlock? = when {
    !notificationsEnabled(context) -> NotificationBlock.APP
    TitanVpnService.channelBlocked(context) -> NotificationBlock.CHANNEL
    Build.VERSION.SDK_INT >= 36 && !TitanVpnService.liveUpdatesAllowed(context) -> NotificationBlock.LIVE_UPDATES
    else -> null
}

/** «Кнопка в шторке»: Android 13+ adds the Quick Settings tile from a system dialog. */
@Composable
internal fun QuickTileRow() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    RowDivider()
    SettingsRow(
        title = "Кнопка в шторке",
        icon = Icons.Filled.ToggleOn,
        iconColor = Titan.palette.primary,
        onClick = { requestTile(context) },
        subtitle = "Включайте VPN из быстрых настроек",
    )
}

private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    val bar = context.getSystemService(StatusBarManager::class.java) ?: return
    runCatching {
        bar.requestAddTileService(
            ComponentName(context, VpnTileService::class.java),
            "Titan VPS",
            Icon.createWithResource(context, R.drawable.ic_shield),
            context.mainExecutor,
        ) { result ->
            when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> Toasts.show("Кнопка добавлена", ToastKind.SUCCESS)
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> Toasts.show("Кнопка уже в шторке", ToastKind.INFO)
                else -> Unit
            }
        }
    }
}

private fun ignoresBatteryLimits(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

/** The system "allow in background" dialog; on ROMs without it, the list of apps. */
private fun openBatterySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
        )
    }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun notificationsEnabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        )
    }
}

/** The screen that lifts [block]; the app's notification settings where it isn't there. */
private fun openNotificationSettings(context: Context, block: NotificationBlock) {
    val intent = when (block) {
        NotificationBlock.APP -> null
        NotificationBlock.CHANNEL -> Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_CHANNEL_ID, TitanVpnService.CHANNEL_ID)
        // Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS (Android 16), by its value.
        NotificationBlock.LIVE_UPDATES -> Intent("android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS")
    }?.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    if (intent == null || runCatching { context.startActivity(intent) }.isFailure) openNotificationSettings(context)
}
