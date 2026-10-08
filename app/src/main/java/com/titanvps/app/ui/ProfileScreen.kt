package com.titanvps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.Subscription
import com.titanvps.app.data.ThemeMode

/** "Профиль" tab = settings, as in the mockup. */
@Composable
internal fun ProfileScreen(
    viewModel: MainViewModel,
    sub: Subscription,
    onOpenApps: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenHome: () -> Unit,
) {
    val context = LocalContext.current
    val theme by viewModel.theme.collectAsState()
    val autoConnect by viewModel.autoConnect.collectAsState()
    val autoBypass by viewModel.autoBypass.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar("Настройки", onBack = onOpenHome)

            SectionCard(title = "Оформление") {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    listOf(ThemeMode.LIGHT to "Светлая", ThemeMode.DARK to "Тёмная", ThemeMode.SYSTEM to "Системная").forEach { (mode, label) ->
                        val on = theme == mode
                        Box(
                            Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { viewModel.setTheme(mode) }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                label,
                                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            SectionCard(title = "Подключение") {
                NavRow(
                    "Выбор сервера",
                    value = selected?.let { if ("авто" in it.name.lowercase()) "Автоматически" else it.name.trim() } ?: "—",
                    onClick = onOpenHome,
                )
                ToggleRow(
                    "Автоподключение",
                    autoConnect,
                    subtitle = "VPN включится сам при запуске приложения, включении и после сна устройства",
                ) { viewModel.setAutoConnect(it) }
                NavRow("Раздельное туннелирование", onClick = onOpenApps)
                ToggleRow("Автопереход на обходы", autoBypass, divider = false) { viewModel.setAutoBypass(it) }
            }

            SectionCard(title = "Приложение") {
                NavRow("Язык", value = "Русский", onClick = {})
                ToggleRow("Уведомления", notifications) { viewModel.setNotifications(it) }
                NavRow("Поддержка") { context.openUrl(sub.info.supportUrl ?: BuildConfig.TELEGRAM_URL) }
                NavRow("Обновление приложения", value = "v${BuildConfig.VERSION_NAME}") { viewModel.checkUpdate() }
                NavRow("О приложении", divider = false) { about = true }
            }

            Text(
                "Titan VPS",
                Modifier.fillMaxWidth().padding(8.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
            )
        }
    }

    if (about) {
        // "О приложении": version plus the less frequent actions (not in the main list).
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Titan VPS") },
            text = {
                Column {
                    Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ядро Xray", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { about = false; onOpenDiagnostics() }) { Text("Диагностика") }
                    TextButton(onClick = { about = false; confirmReset = true }) { Text("Сброс настроек") }
                    TextButton(onClick = { about = false; confirmLogout = true }) {
                        Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { about = false }) { Text("Закрыть") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Сбросить настройки?") },
            text = { Text("Тема, исключения приложений, избранное и выбранный сервер вернутся к исходным. Ключ останется.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; viewModel.resetSettings() }) { Text("Сбросить") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Отмена") } },
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Выйти из аккаунта?") },
            text = { Text("VPN отключится, ключ нужно будет вставить заново.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; viewModel.logout() }) { Text("Выйти") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Отмена") } },
        )
    }
}

/** Check / download / install flow for "Обновление приложения". */
@Composable
internal fun UpdateDialog(viewModel: MainViewModel) {
    val state by viewModel.update.collectAsState()
    val close = { viewModel.dismissUpdate() }
    when (val st = state) {
        com.titanvps.app.data.Updater.State.Idle -> Unit
        com.titanvps.app.data.Updater.State.ConnectingVpn -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text("Включаем VPN — обновление скачивается только через него…") },
            confirmButton = {},
        )
        is com.titanvps.app.data.Updater.State.NeedPermission -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Разрешите установку") },
            text = { Text("В открывшихся настройках включите «Разрешить установку» для Titan VPS, вернитесь сюда и нажмите «Продолжить».") },
            confirmButton = { TextButton(onClick = { viewModel.downloadUpdate(st.release) }) { Text("Продолжить") } },
            dismissButton = { TextButton(onClick = { viewModel.openInstallPermission() }) { Text("Открыть настройки") } },
        )
        com.titanvps.app.data.Updater.State.Checking -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text("Проверяем новую версию…") },
            confirmButton = {},
        )
        com.titanvps.app.data.Updater.State.UpToDate -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text("У вас последняя версия ${BuildConfig.VERSION_NAME}") },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
        is com.titanvps.app.data.Updater.State.Available -> AlertDialog(
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
        is com.titanvps.app.data.Updater.State.Downloading -> AlertDialog(
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
        is com.titanvps.app.data.Updater.State.Error -> AlertDialog(
            onDismissRequest = close,
            title = { Text("Обновление") },
            text = { Text(st.message) },
            confirmButton = { TextButton(onClick = close) { Text("OK") } },
        )
    }
}
