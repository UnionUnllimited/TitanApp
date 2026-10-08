package com.titanvps.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.DevicesOther
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PhoneIphone
import androidx.compose.material.icons.outlined.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.data.AccountApi
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Devices that used this key (from the bot), newest first. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun DevicesScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val account by viewModel.account.collectAsState()
    val loading by viewModel.accountLoading.collectAsState()
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { viewModel.loadAccount() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 700.dp).fillMaxSize()) {
            TopBar("Мои устройства", onBack = onBack)
            PullToRefreshBox(isRefreshing = loading, onRefresh = { viewModel.loadAccount() }, modifier = Modifier.fillMaxSize()) {
                val info = account
                when {
                    info == null && loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    info == null -> Message("Не удалось загрузить список. Потяните вниз, чтобы обновить")
                    !info.devicesEnabled -> Message("Список устройств сейчас недоступен")
                    info.devices.isEmpty() -> Message("Пока нет подключённых устройств")
                    else -> LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item {
                            Text(
                                "Подключено: ${info.devices.size}" + if (info.deviceLimit > 0) " из ${info.deviceLimit}" else " · без лимита",
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(info.devices) { DeviceCard(it) }
                        item {
                            Text(
                                "Чтобы отвязать лишнее устройство, напишите в поддержку.",
                                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
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
    // Scrollable so pull-to-refresh works on an empty screen too.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
        item { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun DeviceCard(d: AccountApi.Device) {
    val os = d.os.lowercase()
    val icon = when {
        "ios" in os || "iphone" in d.model.lowercase() || "ipad" in d.model.lowercase() -> Icons.Outlined.PhoneIphone
        "android" in os && ("tv" in d.model.lowercase()) -> Icons.Outlined.Tv
        "android" in os -> Icons.Outlined.PhoneAndroid
        "windows" in os || "mac" in os || "linux" in os -> Icons.Outlined.Computer
        else -> Icons.Outlined.DevicesOther
    }
    val thisDevice = AccountApi.appName(d.app) == "Titan VPS" &&
        d.model.isNotEmpty() && android.os.Build.MODEL.isNotEmpty() &&
        (d.model.contains(android.os.Build.MODEL, true) || android.os.Build.MODEL.contains(d.model, true))
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    d.model.ifEmpty { "Устройство" } + if (thisDevice) " · это устройство" else "",
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(listOf(d.os, d.osVersion).filter { it.isNotEmpty() }.joinToString(" "), AccountApi.appName(d.app))
                        .filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                lastSeen(d.lastSeen)?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** "был в сети 5 мин назад" from the bot's ISO time (UTC when no zone). */
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
