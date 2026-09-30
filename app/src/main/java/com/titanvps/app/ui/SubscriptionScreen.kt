package com.titanvps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.theme.Connected
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * "Подписка" tab: only the expiry date, the bypass traffic and a "Личный кабинет"
 * button; renewing, buying GB etc. all happen in the cabinet.
 */
@Composable
internal fun SubscriptionScreen(viewModel: MainViewModel, sub: Subscription, onBack: () -> Unit) {
    val info = sub.info
    val now = System.currentTimeMillis() / 1000
    val active = info.expireAt <= 0 || info.expireAt > now

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.widthIn(max = if (wide) 960.dp else 640.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar("Подписка", onBack = onBack)

            val expiry: @Composable (Modifier) -> Unit = { m ->
                SectionCard(m) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(
                            Modifier.size(10.dp).clip(CircleShape).background(if (active) Connected else MaterialTheme.colorScheme.error)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (active) "Подписка активна" else "Подписка истекла", color = if (active) Connected else MaterialTheme.colorScheme.error, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (info.expireAt > 0) "до " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(info.expireAt * 1000)) else "Бессрочно",
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (info.expireAt > 0) {
                        val days = TimeUnit.SECONDS.toDays(info.expireAt - now).coerceAtLeast(0)
                        Text("Осталось $days дн.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val traffic: @Composable (Modifier) -> Unit = { m ->
                SectionCard(m) {
                    Text("Трафик на обходах", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    if (info.totalBytes > 0) {
                        val left = (info.totalBytes - info.usedBytes).coerceAtLeast(0)
                        Text("${formatBytes(left)} из ${formatBytes(info.totalBytes)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { (left.toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        Text("Безлимит", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("На обычных серверах — безлимит", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    expiry(Modifier.weight(1f))
                    traffic(Modifier.weight(1f))
                }
            } else {
                expiry(Modifier.fillMaxWidth())
                traffic(Modifier.fillMaxWidth())
            }

            Button(
                onClick = viewModel::openCabinet,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Outlined.AccountCircle, null)
                Spacer(Modifier.width(10.dp))
                Text("Личный кабинет", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            }

            SectionCard {
                Text("В личном кабинете", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(10.dp))
                Feature(Icons.Outlined.Autorenew, "Продление подписки")
                Feature(Icons.Outlined.DataUsage, "Докупка ГБ для обходов")
                Feature(Icons.Outlined.Devices, "Управление устройствами")
                Feature(Icons.Outlined.Loyalty, "Промокоды и история платежей")
                Feature(Icons.Outlined.SupportAgent, "Поддержка")
            }
        }
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, fontSize = 15.sp)
    }
}
