package com.titanvps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Sell
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.data.Fmt
import com.titanvps.app.data.Subscription
import com.titanvps.app.ui.kit.CircleIconButton
import com.titanvps.app.ui.kit.GradientBar
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.SecondaryButton
import com.titanvps.app.ui.kit.SettingsRow
import com.titanvps.app.ui.kit.TitanCard
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.DangerGradient
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.fixedSp

/**
 * "Подписка" tab: the expiry date, the bypass traffic and a "Личный кабинет" button;
 * renewing, buying GB etc. all happen in the cabinet. Two columns on a tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionScreen(viewModel: MainViewModel, sub: Subscription, onOpenDevices: () -> Unit) {
    val info = sub.info
    val context = LocalContext.current
    val palette = Titan.palette
    val account by viewModel.account.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val now = System.currentTimeMillis() / 1000
    val active = info.expireAt <= 0 || info.expireAt > now
    LaunchedEffect(Unit) { viewModel.loadAccount() }

    val expiry: @Composable (Modifier) -> Unit = { m ->
        TitanCard(m, contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val color = if (active) palette.green else palette.red
                Row(
                    Modifier.background(color.copy(alpha = 0.14f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(8.dp).background(color, CircleShape))
                    Text(
                        if (active) "Подписка активна" else "Подписка истекла",
                        color = color,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    if (info.expireAt > 0) "до " + Fmt.date(info.expireAt) else "Бессрочно",
                    fontSize = fixedSp(26f),
                    lineHeight = fixedSp(32f),
                    fontWeight = FontWeight.Bold,
                )
                if (info.expireAt > 0) {
                    Text("Осталось ${Fmt.daysLeft(info.expireAt, now)} дн.", color = palette.textSecondary)
                }
                Text("Обновлено " + Fmt.ago(sub.fetchedAt), color = palette.textTertiary, fontSize = 12.sp)
            }
        }
    }
    val traffic: @Composable (Modifier) -> Unit = { m ->
        TitanCard(m, contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Трафик на обходах", color = palette.textSecondary, fontSize = 14.sp)
                if (info.totalBytes > 0) {
                    val left = (info.totalBytes - info.usedBytes).coerceAtLeast(0)
                    Text(
                        "${Fmt.bytes(left)} из ${Fmt.bytes(info.totalBytes)}",
                        fontSize = fixedSp(26f),
                        lineHeight = fixedSp(32f),
                        fontWeight = FontWeight.Bold,
                    )
                    // Under a tenth left: orange to red.
                    GradientBar(
                        left.toFloat() / info.totalBytes,
                        if (left * 10 < info.totalBytes) DangerGradient else AccentGradient,
                    )
                } else {
                    Text("Безлимит", fontSize = fixedSp(26f), lineHeight = fixedSp(32f), fontWeight = FontWeight.Bold)
                }
                Text("На обычных серверах — безлимит", color = palette.textSecondary, fontSize = 13.sp)
                Text(
                    "Обходы — серверы для мобильного интернета, когда оператор пропускает только " +
                        "разрешённые сайты (белые списки) и обычные серверы не работают. " +
                        "Гигабайты списываются только пока вы подключены к обходу. " +
                        "На Wi-Fi обходы не нужны и недоступны. Закончились ГБ — докупите в личном кабинете.",
                    Modifier.padding(top = 4.dp),
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
            }
        }
    }

    PullToRefreshBox(isRefreshing = busy, onRefresh = { viewModel.refreshByHand() }, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth >= 720.dp
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    Modifier.widthIn(max = if (wide) 960.dp else 640.dp).fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Подписка", Modifier.weight(1f), fontSize = fixedSp(28f), lineHeight = fixedSp(34f), fontWeight = FontWeight.Bold)
                        // Not dimmed while it turns; a tap meanwhile does nothing.
                        CircleIconButton(
                            Icons.Filled.Refresh,
                            "Обновить подписку",
                            onClick = { if (!busy) viewModel.refreshByHand() },
                            size = 42.dp,
                            spinning = busy,
                        )
                    }

                    if (wide) {
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            expiry(Modifier.weight(1f).fillMaxHeight())
                            traffic(Modifier.weight(1f).fillMaxHeight())
                        }
                    } else {
                        expiry(Modifier)
                        traffic(Modifier)
                    }

                    PrimaryButton("Личный кабинет", { viewModel.openCabinet() }, icon = Icons.Filled.AccountCircle)

                    account?.takeIf { it.devicesEnabled }?.let { acc ->
                        TitanCard {
                            SettingsRow(
                                "Подключённые устройства",
                                Icons.Filled.Smartphone,
                                palette.primary,
                                onClick = onOpenDevices,
                                trailing = {
                                    Text(
                                        "${acc.devices.size}" + if (acc.deviceLimit > 0) " из ${acc.deviceLimit}" else "",
                                        color = palette.textSecondary,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                            )
                        }
                    }

                    TitanCard(contentPadding = PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("В личном кабинете", fontWeight = FontWeight.SemiBold)
                            Feature(Icons.Filled.Autorenew, "Продление подписки")
                            Feature(Icons.Outlined.PieChart, "Докупка ГБ для обходов")
                            Feature(Icons.Outlined.Sell, "Коды оплаты")
                        }
                    }

                    // What the in-app cabinet can't do: the bot or the full cabinet on the site.
                    TitanCard(contentPadding = PaddingValues(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Расширенные функции", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Изменение лимита устройств, отвязка устройств, рефералы и история платежей — в боте " +
                                    "или в полном кабинете на сайте (вход по почте или через Telegram).",
                                color = palette.textSecondary,
                                fontSize = 13.sp,
                            )
                            val site = fullCabinetUrl(info.webPageUrl)
                            SideBySideOrStacked(10.dp) {
                                SecondaryButton(
                                    "Открыть бота",
                                    { context.openUrl(BuildConfig.TELEGRAM_URL) },
                                    icon = Icons.AutoMirrored.Filled.Send,
                                    minHeight = 46.dp,
                                )
                                if (site != null) {
                                    SecondaryButton("Открыть сайт", { context.openUrl(site) }, icon = Icons.Filled.Language, minHeight = 46.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The site's full cabinet (login by email / Telegram): the host of the subscription's cabinet link. */
private fun fullCabinetUrl(link: String?): String? =
    link?.let { runCatching { java.net.URI(it) }.getOrNull() }?.takeIf { it.host != null }?.let { "${it.scheme}://${it.host}/auth" }

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = Titan.palette.primary)
        Text(text)
    }
}

/** Equal widths side by side where every item fits on one line, else one under another. */
@Composable
private fun SideBySideOrStacked(spacing: Dp, content: @Composable () -> Unit) {
    Layout(content) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val count = measurables.size
        if (count == 0) return@Layout layout(constraints.minWidth, 0) {}
        val width = constraints.maxWidth
        val share = ((width - gap * (count - 1)) / count).coerceAtLeast(0)
        val fits = measurables.all { it.maxIntrinsicWidth(Constraints.Infinity) <= share }
        if (fits) {
            val placeables = measurables.map { it.measure(Constraints(minWidth = share, maxWidth = share)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { i, p -> p.placeRelative(i * (share + gap), (height - p.height) / 2) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
            val height = placeables.sumOf { it.height } + gap * (count - 1)
            layout(width, height) {
                var y = 0
                placeables.forEach { p ->
                    p.placeRelative(0, y)
                    y += p.height + gap
                }
            }
        }
    }
}
