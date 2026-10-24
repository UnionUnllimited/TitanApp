package com.titanvps.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Personal cabinet in the app: the site's limited cabinet (signed in by the subscription's
 * link, no email or Telegram login) shown natively — the subscription, renewal tariffs,
 * payment codes. Only the payment page itself opens, in the browser. Same screen as on iPhone.
 */

@Composable
fun CabinetPage(link: String, devices: Pair<Int, Int>?, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val c = Palette.colors
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var api by remember { mutableStateOf<CabinetApi?>(null) }
    var data by remember { mutableStateOf<CabinetApi.Data?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf(0) }

    fun load() {
        loading = true
        error = null
        scope.launch {
            try {
                val current = api
                if (current == null) {
                    val (a, d) = withContext(Dispatchers.IO) { CabinetApi.open(link) }
                    api = a; data = d
                } else {
                    data = withContext(Dispatchers.IO) { current.load() }
                }
            } catch (e: CabinetApi.CabinetException) {
                error = e.message
            } catch (e: Exception) {
                error = "Не удалось загрузить кабинет. Проверьте интернет и попробуйте ещё раз"
            } finally {
                loading = false
            }
        }
    }
    LaunchedEffect(Unit) { load() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 640.dp).fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
            // Close and refresh as round buttons, the title between them.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack, size = 40.dp, tint = c.secondary)
                Text("Личный кабинет", fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                CircleIconButton(Icons.Default.Refresh, "Обновить", { load() }, spinning = loading, size = 40.dp)
            }
            Spacer(Modifier.height(14.dp))
            SegmentedTabs(listOf("Подписка" to Icons.Default.WorkspacePremium, "Оплатить" to Icons.Default.CreditCard), tab, { tab = it })
            Spacer(Modifier.height(14.dp))
            val d = data
            when {
                d == null && loading -> Skeleton()
                d == null -> Failure(error ?: "Ошибка") { load() }
                else -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    note?.let { n ->
                        LaunchedEffect(n) { delay(6000); if (note == n) note = null }
                        Banner(n, Icons.Default.Info, Brand)
                    }
                    error?.let { Banner(it, Icons.Default.Warning, Red) }
                    if (tab == 0) {
                        SubscriptionPane(d, devices) { id -> clipboard.setText(AnnotatedString(id)) }
                    } else {
                        PayPane(
                            d,
                            onPay = { tariff, method, done ->
                                val a = api ?: return@PayPane
                                scope.launch {
                                    try {
                                        val url = withContext(Dispatchers.IO) { a.pay(tariff, method) }
                                        // The payment page itself (bank / SBP) opens in the browser.
                                        uri.openUri(url)
                                        note = "Страница оплаты открыта в браузере. После оплаты нажмите «Обновить»"
                                    } catch (e: Exception) {
                                        note = (e as? CabinetApi.CabinetException)?.message ?: "Ошибка соединения"
                                    } finally {
                                        done()
                                    }
                                }
                            },
                            onCode = { code, done ->
                                val a = api ?: return@PayPane
                                scope.launch {
                                    try {
                                        note = withContext(Dispatchers.IO) { a.applyCode(code) }
                                        load()
                                    } catch (e: Exception) {
                                        note = (e as? CabinetApi.CabinetException)?.message ?: "Ошибка соединения"
                                    } finally {
                                        done()
                                    }
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, icon: ImageVector, color: Color) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color.copy(alpha = 0.12f)).padding(14.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** While the cabinet loads the first time: the shapes of what is coming, with a shine. */
@Composable
private fun Skeleton() {
    val c = Palette.colors
    Column(Modifier.fillMaxWidth().shimmer(true, c.outline, 1300), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(24.dp)).background(c.surface))
        Box(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(20.dp)).background(c.surface))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f).height(130.dp).clip(RoundedCornerShape(20.dp)).background(c.surface))
            Box(Modifier.weight(1f).height(130.dp).clip(RoundedCornerShape(20.dp)).background(c.surface))
        }
    }
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
    val c = Palette.colors
    Column(Modifier.fillMaxWidth().padding(32.dp).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(Brand.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.WifiOff, null, Modifier.size(40.dp), tint = Brand)
        }
        Spacer(Modifier.height(14.dp))
        Text("Кабинет не загрузился", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(message, fontSize = 14.sp, color = c.secondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        PrimaryButton("Повторить", onRetry, Modifier.widthIn(max = 240.dp), icon = Icons.Default.Refresh, height = 50.dp)
    }
}

@Composable
private fun SubscriptionPane(d: CabinetApi.Data, devices: Pair<Int, Int>?, onCopyId: (String) -> Unit) {
    val c = Palette.colors
    Hero(d)
    d.traffic?.let { traffic ->
        val used = ((d.trafficPercent ?: 0f) / 100f).coerceIn(0f, 1f)
        TitanCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Default.DataUsage, Brand, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Трафик", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                        Text(traffic, fontSize = 16.sp, fontWeight = FontWeight.Bold, style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                // Green while there's plenty, orange then red as it runs out.
                GradientBar(
                    used,
                    when {
                        used < 0.7f -> Palette.green
                        used < 0.9f -> Brush.horizontalGradient(listOf(Yellow, Orange))
                        else -> Brush.horizontalGradient(listOf(Orange, Red))
                    },
                )
                d.trafficNote?.let { Text(it, fontSize = 12.sp, color = c.secondary) }
            }
        }
    }
    val limit = d.deviceLimit ?: devices?.second?.takeIf { it > 0 }
    val showDevices = limit != null || devices != null
    if (showDevices || d.clientId != null) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showDevices) Tile(Modifier.weight(1f)) {
                IconTile(Icons.Default.Devices, Violet, 38.dp)
                Text("Устройства", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                val count = devices?.first
                Text(
                    when {
                        count != null && limit != null -> "$count из $limit"
                        count != null -> "$count"
                        else -> "до $limit"
                    },
                    fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, style = Tabular, maxLines = 1,
                )
                Text("Лимит: " + (limit?.let { devicesWord(it) } ?: "без ограничения"), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.secondary)
            }
            d.clientId?.let { id ->
                var copied by remember { mutableStateOf(false) }
                LaunchedEffect(copied) { if (copied) { delay(1600); copied = false } }
                Tile(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).press(scale = 0.97f) { onCopyId(id); copied = true }) {
                    IconTile(if (copied) Icons.Default.Check else Icons.Default.Badge, if (copied) c.green else Orange, 38.dp)
                    Text("Ваш ID", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                    Text(id, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, style = Tabular, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, null, Modifier.size(14.dp), tint = if (copied) c.green else c.secondary)
                        Spacer(Modifier.width(5.dp))
                        Text(if (copied) "Скопировано" else "Нажмите, чтобы скопировать", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (copied) c.green else c.secondary)
                    }
                }
            }
        }
    }
}

/** Дни подписки on the brand gradient (red when it's over), a crown in the corner. */
@Composable
private fun Hero(d: CabinetApi.Data) {
    val shape = RoundedCornerShape(24.dp)
    val glow = if (d.active) Brand else Red
    Box(
        Modifier.fillMaxWidth()
            .shadow(18.dp, shape, ambientColor = glow.copy(alpha = 0.35f), spotColor = glow.copy(alpha = 0.45f))
            .clip(shape).background(if (d.active) Palette.accent else Palette.red)
            .border(1.dp, Color.White.copy(alpha = 0.15f), shape),
    ) {
        Icon(
            Icons.Default.WorkspacePremium, null,
            Modifier.align(Alignment.TopEnd).offset(x = 26.dp, y = (-6).dp).size(150.dp).rotate(-14f),
            tint = Color.White.copy(alpha = 0.12f),
        )
        Column(Modifier.padding(20.dp)) {
            StatusPill(d.status.ifEmpty { if (d.active) "Активна" else "Неактивна" }, Color.White, onColor = true)
            Spacer(Modifier.height(10.dp))
            if (d.days.isNotEmpty()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(d.days, fontSize = 64.sp, fontWeight = FontWeight.Black, color = Color.White, lineHeight = 64.sp, style = Tabular)
                    Spacer(Modifier.width(6.dp))
                    Text(d.daysUnit, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(bottom = 10.dp))
                    if (d.hours.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(d.hours, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
                Text("ОСТАЛОСЬ", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = Color.White.copy(alpha = 0.75f))
            } else if (d.heroText.isNotEmpty()) {
                Text(d.heroText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            if (d.until.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.18f)).padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.CalendarMonth, null, Modifier.size(16.dp), tint = Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(d.until, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

/** A half-width card of the dashboard (devices, ID). */
@Composable
private fun Tile(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxHeight().clip(RoundedCornerShape(20.dp)).background(Palette.colors.surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun PayPane(
    d: CabinetApi.Data,
    onPay: (CabinetApi.Tariff, String, done: () -> Unit) -> Unit,
    onCode: (String, done: () -> Unit) -> Unit,
) {
    val c = Palette.colors
    var busy by remember { mutableStateOf(false) }
    var limit by remember(d) { mutableStateOf(d.limits.firstOrNull()) }
    for (kind in listOf("sub", "traffic")) {
        val all = d.tariffs.filter { it.kind == kind }
        if (all.isEmpty()) continue
        val visible = if (kind == "sub" && d.limits.isNotEmpty()) all.filter { it.limit == limit } else all.filter { it.shown }.ifEmpty { all }
        var pickedId by remember(d, kind, limit) { mutableStateOf(visible.singleOrNull()?.id) }
        val picked = visible.firstOrNull { it.id == pickedId }
        TitanCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconTile(if (kind == "sub") Icons.Default.WorkspacePremium else Icons.Default.DataUsage, if (kind == "sub") Brand else Violet, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(if (kind == "sub") "Продлить подписку" else "Докупить трафик", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(if (kind == "sub") "Дни добавятся к текущей подписке" else "Гигабайты для обходов", fontSize = 12.sp, color = c.secondary)
                    }
                }
                if (kind == "sub" && d.limits.isNotEmpty()) {
                    // Лимит устройств: how many at once; fixed after the first payment.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Devices, null, Modifier.size(16.dp), tint = c.secondary)
                        Spacer(Modifier.width(6.dp))
                        Text("Лимит устройств", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.secondary)
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.limits.forEach { l ->
                            val on = limit == l
                            val shape = RoundedCornerShape(12.dp)
                            Box(
                                Modifier.width(48.dp).height(42.dp)
                                    .then(if (on) Modifier.shadow(5.dp, shape, ambientColor = Brand.copy(alpha = 0.35f), spotColor = Brand.copy(alpha = 0.4f)) else Modifier)
                                    .clip(shape).background(if (on) Palette.accent else SolidColor(c.track))
                                    .press(scale = 0.9f) { limit = l },
                                contentAlignment = Alignment.Center,
                            ) { Text("$l", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (on) Color.White else c.text) }
                        }
                    }
                    Text("Сколько устройств одновременно. Фиксируется после первой оплаты.", fontSize = 12.sp, color = c.secondary)
                }
                visible.forEach { t -> TariffRow(t, t.id == picked?.id) { pickedId = t.id } }
                AnimatedVisibility(picked != null, enter = fadeIn(), exit = fadeOut()) {
                    val t = picked ?: return@AnimatedVisibility
                    val methods = d.methods[kind].orEmpty().filter { it.id in t.methods }
                        .ifEmpty { t.methods.map { CabinetApi.Method(it, "") } }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (methods.isEmpty()) Text("Оплата для этого тарифа недоступна", fontSize = 13.sp, color = Red)
                        methods.forEach { m ->
                            PrimaryButton(
                                "Оплатить ${price(t.price)}" + if (m.title.isNotEmpty()) " · ${m.title}" else "",
                                { busy = true; onPay(t, m.id) { busy = false } },
                                icon = Icons.Default.CreditCard, enabled = !busy, busy = busy,
                            )
                        }
                    }
                }
                if (picked == null && visible.isNotEmpty()) {
                    Text("Выберите тариф, чтобы оплатить", fontSize = 12.sp, color = c.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (d.tariffs.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CreditCardOff, null, Modifier.size(36.dp), tint = c.secondary)
            Spacer(Modifier.height(10.dp))
            Text("Сейчас оплата недоступна", color = c.secondary)
        }
    }
    // A payment code is rare: a quiet button at the bottom, the field in a dialog.
    if (d.promo) {
        var codeOpen by remember { mutableStateOf(false) }
        SecondaryButton("Есть код оплаты?", { codeOpen = true }, icon = Icons.Default.ConfirmationNumber, tint = c.secondary)
        if (codeOpen) {
            var code by remember { mutableStateOf("") }
            TitanDialog(
                { if (!busy) codeOpen = false }, "Код оплаты",
                "Продлевает подписку на указанное в коде число дней. Один код — один раз на аккаунт.",
                icon = Icons.Default.ConfirmationNumber, iconColor = Violet, width = 400.dp, dismissible = !busy,
                buttons = {
                    PrimaryButton("Применить", { busy = true; onCode(code.trim()) { busy = false; codeOpen = false } }, enabled = code.isNotBlank() && !busy, busy = busy, height = 48.dp)
                    SecondaryButton("Отмена", { codeOpen = false }, enabled = !busy, tint = c.secondary)
                },
            ) {
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.background)
                        .border(1.5.dp, if (code.isEmpty()) c.outline else Brand, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (code.isEmpty()) Text("Введите код", fontSize = 15.sp, color = c.tertiary)
                    BasicTextField(
                        code, { code = it.take(64).uppercase() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 15.sp, color = c.text, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Brand),
                    )
                }
            }
        }
    }
}

/** A tariff as a radio card: the name, what it gives, the price in bold. */
@Composable
private fun TariffRow(t: CabinetApi.Tariff, on: Boolean, onClick: () -> Unit) {
    val c = Palette.colors
    val shape = RoundedCornerShape(16.dp)
    val meta = listOf(t.limitLabel, t.trafficLabel).filter { it.isNotEmpty() }.joinToString(" · ")
    var hovered by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp).clip(shape)
            .background(if (on) Brand.copy(alpha = 0.1f) else c.track.copy(alpha = if (hovered) 0.9f else 0.55f))
            .border(1.5.dp, if (on) Brand else Color.Transparent, shape)
            .press(scale = 0.98f, onHover = { hovered = it }, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).border(2.dp, if (on) Brand else c.outline, CircleShape), contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(12.dp).clip(CircleShape).background(Brand))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = c.secondary)
        }
        Spacer(Modifier.width(8.dp))
        Text(price(t.price), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = if (on) Brand else c.text, style = Tabular)
    }
}

private fun devicesWord(n: Int): String = Fmt.plural(n.toLong(), "устройство", "устройства", "устройств")

private fun price(p: Double): String = (if (p % 1.0 == 0.0) p.toLong().toString() else "%.2f".format(p)) + " ₽"
