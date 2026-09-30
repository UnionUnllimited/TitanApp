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
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("Настройки")

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
                ToggleRow("Автоподключение", autoConnect, subtitle = "Подключаться при запуске приложения") { viewModel.setAutoConnect(it) }
                NavRow("Выбор сервера", value = selected?.name ?: "—", onClick = onOpenHome)
                NavRow("Раздельное туннелирование", onClick = onOpenApps)
                ToggleRow(
                    "Автопереход на обходы",
                    autoBypass,
                    subtitle = "Если мобильный интернет включил белые списки",
                    divider = false,
                ) { viewModel.setAutoBypass(it) }
            }

            SectionCard(title = "Приложение") {
                NavRow("Язык", value = "Русский", onClick = {})
                ToggleRow("Уведомления", notifications) { viewModel.setNotifications(it) }
                NavRow("Поддержка") { context.openUrl(sub.info.supportUrl ?: BuildConfig.TELEGRAM_URL) }
                NavRow("О приложении") { about = true }
                NavRow("Сброс настроек") { confirmReset = true }
                NavRow("Выйти из аккаунта", titleColor = MaterialTheme.colorScheme.error, divider = false) { confirmLogout = true }
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
        AlertDialog(
            onDismissRequest = { about = false },
            title = { Text("Titan VPS") },
            text = { Text("Версия ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nЯдро: Xray") },
            confirmButton = { TextButton(onClick = { about = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { about = false; onOpenDiagnostics() }) { Text("Диагностика") } },
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
