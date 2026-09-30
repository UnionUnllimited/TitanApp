package com.titanvps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Loyalty
import androidx.compose.material.icons.outlined.ReceiptLong
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.theme.Connected
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Подписка" tab: status, renew, bypass traffic, account actions (all via the bot). */
@Composable
internal fun SubscriptionScreen(viewModel: MainViewModel, sub: Subscription, onBack: () -> Unit) {
    val context = LocalContext.current
    val info = sub.info
    val bot = { context.openUrl(BuildConfig.TELEGRAM_URL) }
    val active = info.expireAt <= 0 || info.expireAt * 1000 > System.currentTimeMillis()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar("Подписка", onBack = onBack)

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (active) Connected else MaterialTheme.colorScheme.error))
                    Spacer(Modifier.width(8.dp))
                    Text(if (active) "Активна" else "Истекла", color = if (active) Connected else MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text("Titan VPS", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (info.expireAt > 0) "До " + SimpleDateFormat("d MMMM yyyy", Locale("ru")).format(Date(info.expireAt * 1000)) else "Бессрочно",
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("Обычные серверы — безлимит", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Button(onClick = bot, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(14.dp)) {
                    Text("Продлить подписку", fontWeight = FontWeight.SemiBold)
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Трафик на обходы", fontWeight = FontWeight.SemiBold)
                        if (info.totalBytes > 0) {
                            Text(formatBytes((info.totalBytes - info.usedBytes).coerceAtLeast(0)), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text("из ${formatBytes(info.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text("Безлимит", fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Row(Modifier.clickable(onClick = bot).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Докупить ГБ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (info.totalBytes > 0) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { ((info.totalBytes - info.usedBytes).toFloat() / info.totalBytes).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Трафик расходуется только на обходах", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionCard {
                NavRow("История платежей", Icons.Outlined.ReceiptLong, onClick = bot)
                NavRow("Промокод", Icons.Outlined.Loyalty, onClick = bot)
                NavRow("Управление устройствами", Icons.Outlined.Devices, onClick = bot)
                NavRow("Поддержка", Icons.Outlined.SupportAgent) {
                    context.openUrl(info.supportUrl ?: BuildConfig.TELEGRAM_URL)
                }
                NavRow("Личный кабинет", Icons.Outlined.AccountCircle, divider = false, onClick = viewModel::openCabinet)
            }

            InfoBanner("Обходы доступны при активной подписке")
        }
    }
}
