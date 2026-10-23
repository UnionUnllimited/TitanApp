package com.titanvps.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.AccountApi
import com.titanvps.app.ui.kit.RowDivider
import com.titanvps.app.ui.kit.SecondaryButton
import com.titanvps.app.ui.kit.TitanCard
import com.titanvps.app.ui.theme.Titan
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Devices that used this key (from the bot), newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val palette = Titan.palette
    val account by viewModel.account.collectAsState()
    val loading by viewModel.accountLoading.collectAsState()
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.loadAccount() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 700.dp).fillMaxSize()) {
            TopBar("Устройства", onBack = onBack)
            PullToRefreshBox(isRefreshing = loading, onRefresh = { viewModel.loadAccount() }, modifier = Modifier.fillMaxSize()) {
                val info = account
                // Scrollable in every state, so pull-to-refresh works on an empty screen too.
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when {
                        info == null && loading -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        info == null -> Message("Не удалось загрузить список. Потяните вниз, чтобы обновить")
                        !info.devicesEnabled -> Message("Список устройств сейчас недоступен")
                        else -> {
                            TitanCard {
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Подключено", Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        "${info.devices.size}" + if (info.deviceLimit > 0) " из ${info.deviceLimit}" else "",
                                        color = palette.textSecondary,
                                        fontSize = 15.sp,
                                    )
                                }
                            }
                            TitanCard {
                                if (info.devices.isEmpty()) {
                                    Text(
                                        "Пока ни одного устройства",
                                        Modifier.padding(16.dp),
                                        color = palette.textSecondary,
                                        fontSize = 15.sp,
                                    )
                                }
                                info.devices.forEachIndexed { i, d ->
                                    if (i > 0) RowDivider(inset = 54.dp)
                                    DeviceRow(d)
                                }
                            }
                            Text(
                                "Отвязать лишнее устройство или изменить лимит можно в боте.",
                                Modifier.padding(horizontal = 6.dp),
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                            )
                            SecondaryButton(
                                "Открыть бота",
                                { context.openUrl(BuildConfig.TELEGRAM_URL) },
                                icon = Icons.AutoMirrored.Filled.Send,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), color = Titan.palette.textSecondary)
}

@Composable
private fun DeviceRow(d: AccountApi.Device) {
    val palette = Titan.palette
    val os = d.os.lowercase()
    val model = d.model.lowercase()
    val phone = "ios" in os || "iphone" in model || "ipad" in model || "android" in os
    val thisDevice = AccountApi.appName(d.app).startsWith("Titan VPS") &&
        d.model.isNotEmpty() && android.os.Build.MODEL.isNotEmpty() &&
        (d.model.contains(android.os.Build.MODEL, true) || android.os.Build.MODEL.contains(d.model, true))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(if (phone) Icons.Filled.Smartphone else Icons.Filled.Computer, null, Modifier.size(26.dp), tint = palette.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                d.model.ifEmpty { "Устройство" } + if (thisDevice) " · это устройство" else "",
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val system = listOf(d.os, d.osVersion).filter { it.isNotEmpty() }.joinToString(" ")
            val app = if (d.app.isEmpty()) "" else AccountApi.appName(d.app)
            val meta = listOf(system, app).filter { it.isNotEmpty() }.joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, color = palette.textSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            lastSeen(d.lastSeen)?.let {
                Text(it, color = palette.textSecondary, fontSize = 12.sp)
            }
        }
    }
}

/** "Активность: 5 мин назад" from the bot's ISO time (UTC when no zone). */
private fun lastSeen(iso: String): String? {
    if (iso.isBlank()) return null
    val instant = runCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(iso.replace(' ', 'T')).atZone(ZoneId.of("UTC")).toInstant() }.getOrNull()
        ?: return null
    val min = Duration.between(instant, java.time.Instant.now()).toMinutes().coerceAtLeast(0)
    return "Активность: " + when {
        min < 2 -> "только что"
        min < 60 -> "$min мин назад"
        min < 60 * 24 -> "${min / 60} ч назад"
        else -> "${min / 60 / 24} дн. назад"
    }
}
