package com.titanvps.app.ui

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.R
import com.titanvps.app.data.AccountApi
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Fmt
import com.titanvps.app.data.Subscription
import com.titanvps.app.data.ThemeMode
import com.titanvps.app.ui.kit.FlagCircle
import com.titanvps.app.ui.kit.RowDivider
import com.titanvps.app.ui.kit.SectionTitle
import com.titanvps.app.ui.kit.SegmentItem
import com.titanvps.app.ui.kit.SegmentedTabs
import com.titanvps.app.ui.kit.SettingsRow
import com.titanvps.app.ui.kit.SettingsToggleRow
import com.titanvps.app.ui.kit.TitanCard
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp

/** "Профиль" tab = Настройки (iOS ProfileView), with the rows only Android has. */
@Composable
internal fun ProfileScreen(
    viewModel: MainViewModel,
    sub: Subscription,
    onOpenApps: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenTech: () -> Unit,
    onOpenSubscription: () -> Unit,
) {
    val context = LocalContext.current
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val theme by viewModel.theme.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()
    val autoBypass by viewModel.autoBypass.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val account by viewModel.account.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val techMode by viewModel.techMode.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var askCode by remember { mutableStateOf(false) }
    // Hidden entry: 7 taps on Версия, at most 3 s apart.
    var versionTaps by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }
    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()
    val supportUrl = sub.info.supportUrl ?: BuildConfig.TELEGRAM_URL

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Настройки",
                Modifier.padding(start = 4.dp, top = 8.dp),
                fontSize = fixedSp(28f),
                lineHeight = fixedSp(34f),
                fontWeight = FontWeight.Bold,
            )

            AccountCard(sub, account) {
                haptics.selection()
                onOpenSubscription()
            }

            SectionTitle("Оформление")
            // The tiles are a card of their own.
            SegmentedTabs(
                items = listOf(
                    SegmentItem("Системная", Icons.Filled.BrightnessMedium),
                    SegmentItem("Светлая", Icons.Filled.LightMode),
                    SegmentItem("Тёмная", Icons.Filled.DarkMode),
                ),
                selectedIndex = when (theme) {
                    ThemeMode.SYSTEM -> 0
                    ThemeMode.LIGHT -> 1
                    ThemeMode.DARK -> 2
                },
                onSelect = { viewModel.setTheme(listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK)[it]) },
                tiles = true,
            )

            SectionTitle("Подключение")
            TitanCard {
                SettingsRow(
                    "Сервер",
                    Icons.Filled.Storage,
                    palette.primary,
                    onClick = {
                        haptics.selection()
                        onOpenHome()
                    },
                    trailing = {
                        Row(
                            Modifier.widthIn(max = 170.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (selected != null) FlagCircle(selected.name, 20.dp)
                            Text(
                                selected?.takeUnless { "авто" in it.name.lowercase() }?.let { Flags.nameWithoutFlag(it.name) }
                                    ?: "Автоматически",
                                Modifier.weight(1f, fill = false),
                                color = palette.textSecondary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
                RowDivider()
                SettingsToggleRow(
                    "Автоподключение",
                    Icons.Filled.Bolt,
                    palette.green,
                    checked = autoConnect,
                    onCheckedChange = { viewModel.setAutoConnect(it) },
                    subtitle = "VPN включится сам при открытии приложения, включении и после сна устройства",
                )
                KeepAliveRows()
                RemindersRow()
                LiveNotificationRow()
                RowDivider()
                SettingsRow(
                    "Раздельное туннелирование",
                    Icons.AutoMirrored.Filled.CallSplit,
                    palette.violet,
                    onClick = onOpenApps,
                    subtitle = "Какие приложения идут мимо VPN",
                )
                RowDivider()
                SettingsToggleRow(
                    "Автопереход на обходы",
                    Icons.Filled.Shuffle,
                    palette.orange,
                    checked = autoBypass,
                    onCheckedChange = { viewModel.setAutoBypass(it) },
                    subtitle = "При белых списках — на обход, после их снятия — обратно",
                )
            }

            SectionTitle("Приложение")
            TitanCard {
                SettingsRow(
                    "Поддержка",
                    Icons.Filled.HeadsetMic,
                    palette.primary,
                    onClick = { context.openUrl(supportUrl) },
                    subtitle = "Ответим в Telegram",
                    showChevron = false,
                    trailing = {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(13.dp), tint = palette.textTertiary)
                    },
                )
                RowDivider()
                SettingsRow(
                    "Обновить подписку",
                    Icons.Filled.Refresh,
                    palette.green,
                    onClick = { if (!busy) viewModel.refreshByHand() },
                    subtitle = "Обновлено " + Fmt.ago(sub.fetchedAt),
                    showChevron = false,
                    trailing = if (busy) {
                        { CircularProgressIndicator(Modifier.size(18.dp), color = palette.textSecondary, strokeWidth = 2.dp) }
                    } else {
                        null
                    },
                )
                RowDivider()
                SettingsRow(
                    "Обновление приложения",
                    Icons.Filled.SystemUpdate,
                    palette.primary,
                    onClick = { viewModel.checkUpdate() },
                    showChevron = false,
                    trailing = {
                        Text("v${BuildConfig.VERSION_NAME}", color = palette.textSecondary, fontSize = 15.sp)
                    },
                )
                RowDivider()
                SettingsToggleRow(
                    "Уведомления",
                    Icons.Filled.Notifications,
                    palette.orange,
                    checked = notifications,
                    onCheckedChange = { viewModel.setNotifications(it) },
                    subtitle = "Белые списки, Wi‑Fi и новые версии",
                )
                QuickTileRow()
                RowDivider()
                SettingsRow("О приложении", Icons.Filled.Info, palette.violet, onClick = { about = true })
                RowDivider()
                // No highlight: the way in to Технический режим stays hidden.
                SettingsRow(
                    "Версия",
                    Icons.Filled.Tag,
                    palette.gray,
                    onClick = null,
                    modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        val now = SystemClock.elapsedRealtime()
                        versionTaps = if (now - lastTapAt <= 3_000) versionTaps + 1 else 1
                        lastTapAt = now
                        if (versionTaps >= 7) {
                            versionTaps = 0
                            if (techMode) onOpenTech() else askCode = true
                        }
                    },
                    trailing = {
                        Text(
                            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            color = palette.textSecondary,
                            fontSize = 15.sp,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                        )
                    },
                )
                if (techMode) {
                    RowDivider()
                    SettingsRow("Технический режим", Icons.Filled.Build, palette.orange, onClick = onOpenTech)
                }
                RowDivider()
                SettingsRow("Диагностика", Icons.Filled.Description, palette.gray, onClick = onOpenDiagnostics)
            }

            TitanCard(Modifier.padding(top = 18.dp)) {
                SettingsRow(
                    "Сброс настроек",
                    Icons.Filled.RestartAlt,
                    palette.orange,
                    onClick = { confirmReset = true },
                    showChevron = false,
                )
                RowDivider()
                SettingsRow(
                    "Выйти из аккаунта",
                    Icons.AutoMirrored.Filled.Logout,
                    palette.red,
                    onClick = { confirmLogout = true },
                    titleColor = palette.red,
                    showChevron = false,
                )
            }
        }
    }

    if (about) AboutSheet(supportUrl) { about = false }
    if (askCode) {
        TechCodeDialog(
            onDismiss = { askCode = false },
            onUnlocked = { askCode = false; viewModel.setTechMode(true); onOpenTech() },
        )
    }
    if (confirmReset) {
        TitanAlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки?") },
            text = {
                Text(
                    "Тема, исключения приложений, избранное, автоподключение, «Не отключать VPN» и выбранный " +
                        "сервер вернутся к исходным. Ключ останется.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; viewModel.resetSettings() }) {
                    Text("Сбросить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
    if (confirmLogout) {
        TitanAlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("VPN отключится, ключ нужно будет вставить заново.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }
}

/** Who this is: the subscription's state and devices; a tap opens Подписка. */
@Composable
private fun AccountCard(sub: Subscription, account: AccountApi.Info?, onClick: () -> Unit) {
    val palette = Titan.palette
    val expireAt = sub.info.expireAt
    val active = expireAt <= 0 || expireAt > System.currentTimeMillis() / 1000
    TitanCard(
        Modifier.padding(top = 4.dp).pressable(onClick = onClick, scale = 0.98f),
        contentPadding = PaddingValues(16.dp),
        stroke = palette.primary.copy(alpha = 0.25f),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(palette.track).padding(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.logo), null, Modifier.size(44.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Titan VPS", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(8.dp).background(if (active) palette.green else palette.red, CircleShape))
                    Text(
                        when {
                            !active -> "Подписка истекла"
                            expireAt > 0 -> "Активна до " + Fmt.date(expireAt)
                            else -> "Подписка бессрочная"
                        },
                        color = if (active) MaterialTheme.colorScheme.onSurface else palette.red,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                account?.takeIf { it.devicesEnabled }?.let { acc ->
                    Text(
                        "Устройства: ${acc.devices.size}" + if (acc.deviceLimit > 0) " из ${acc.deviceLimit}" else "",
                        color = palette.textSecondary,
                        fontSize = 13.sp,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = palette.textTertiary)
        }
    }
}

/**
 * AlertDialog in the app's text size: a dialog is a window of its own, with the system's
 * text size otherwise.
 */
@Composable
private fun TitanAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ProvideTitanTextScale(confirmButton) },
        dismissButton = dismissButton?.let { slot -> { ProvideTitanTextScale(slot) } },
        title = title?.let { slot -> { ProvideTitanTextScale(slot) } },
        text = text?.let { slot -> { ProvideTitanTextScale(slot) } },
    )
}


/** Check / download / install flow for "Обновление приложения". */
@Composable
internal fun UpdateDialog(viewModel: MainViewModel) {
    val state by viewModel.update.collectAsState()
    val close = { viewModel.dismissUpdate() }
    when (val st = state) {
        com.titanvps.app.data.Updater.State.Idle -> Unit
        com.titanvps.app.data.Updater.State.ConnectingVpn -> TitanAlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text("Включаем VPN — обновление скачивается только через него…") },
            confirmButton = {},
        )
        is com.titanvps.app.data.Updater.State.NeedPermission -> TitanAlertDialog(
            onDismissRequest = close,
            title = { Text("Разрешите установку") },
            text = { Text("В открывшихся настройках включите «Разрешить установку» для Titan VPS, вернитесь сюда и нажмите «Продолжить».") },
            confirmButton = { TextButton(onClick = { viewModel.downloadUpdate(st.release) }) { Text("Продолжить") } },
            dismissButton = { TextButton(onClick = { viewModel.openInstallPermission() }) { Text("Открыть настройки") } },
        )
        com.titanvps.app.data.Updater.State.Checking -> TitanAlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text("Проверяем новую версию…") },
            confirmButton = {},
        )
        com.titanvps.app.data.Updater.State.UpToDate -> TitanAlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text("У вас последняя версия ${BuildConfig.VERSION_NAME}") },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
        is com.titanvps.app.data.Updater.State.Available -> TitanAlertDialog(
            onDismissRequest = close,
            title = { Text("Доступна версия ${st.release.versionName}") },
            text = {
                Text(
                    "Сейчас установлена ${BuildConfig.VERSION_NAME}. Обновление скачается через VPN (он включится сам)" +
                        (if (st.release.sizeBytes > 0) " (${st.release.sizeBytes / 1024 / 1024} МБ)" else "") +
                        " и установится поверх, настройки сохранятся."
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.downloadUpdate(st.release) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = close) { Text("Позже") } },
        )
        is com.titanvps.app.data.Updater.State.Downloading -> TitanAlertDialog(
            onDismissRequest = {},
            title = { Text("Скачиваем обновление") },
            text = {
                Column {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { st.progress },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Text(
                        "${(st.progress * 100).toInt()}% · через VPN. Не закрывайте приложение",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {},
        )
        is com.titanvps.app.data.Updater.State.Error -> TitanAlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
    }
}
