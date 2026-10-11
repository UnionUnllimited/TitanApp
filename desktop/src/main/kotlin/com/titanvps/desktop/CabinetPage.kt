package com.titanvps.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Personal cabinet in the app: the site's limited cabinet (signed in by the subscription's
 * link, no email or Telegram login) shown natively — the subscription, renewal tariffs,
 * payment codes. Only the payment page itself opens, in the browser. Same screen as on Android.
 */

@Composable
fun CabinetPage(link: String, devices: Pair<Int, Int>?, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var note by remember { mutableStateOf<String?>(null) }
    fun toast(text: String) { note = text }
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
            Text("Личный кабинет", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { load() }, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Обновить") }
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface).padding(4.dp),
        ) {
            listOf("Подписка", "Оплатить").forEachIndexed { i, title ->
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(if (tab == i) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent)
                        .clickable { tab = i }.padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(title, fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Normal) }
            }
        }
        val d = data
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when {
                d == null && loading -> CircularProgressIndicator(Modifier.padding(top = 48.dp))
                d == null -> CabError(error ?: "Ошибка") { load() }
                else -> Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { Text(it, color = Red, fontSize = 13.sp) }
                    note?.let { n ->
                        LaunchedEffect(n) { kotlinx.coroutines.delay(4000); if (note == n) note = null }
                        Text(n, color = Brand, fontSize = 13.sp)
                    }
                    if (tab == 0) SubscriptionPane(d, devices) { id -> clipboard.setText(AnnotatedString(id)); toast("ID скопирован") }
                    else PayPane(
                        d,
                        onPay = { tariff, method, done ->
                            val a = api ?: return@PayPane
                            scope.launch {
                                try {
                                    val url = withContext(Dispatchers.IO) { a.pay(tariff, method) }
                                    // The payment page itself (bank / SBP) opens in the browser.
                                    uri.openUri(url)
                                    toast("Страница оплаты открыта в браузере. После оплаты нажмите «Обновить»")
                                } catch (e: Exception) {
                                    toast((e as? CabinetApi.CabinetException)?.message ?: "Ошибка соединения")
                                } finally {
                                    done()
                                }
                            }
                        },
                        onCode = { code, done ->
                            val a = api ?: return@PayPane
                            scope.launch {
                                try {
                                    toast(withContext(Dispatchers.IO) { a.applyCode(code) })
                                    load()
                                } catch (e: Exception) {
                                    toast((e as? CabinetApi.CabinetException)?.message ?: "Ошибка соединения")
                                } finally {
                                    done()
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CabError(message: String, onRetry: () -> Unit) {
    Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(message, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
private fun CabCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun SubscriptionPane(d: CabinetApi.Data, devices: Pair<Int, Int>?, onCopyId: (String) -> Unit) {
    CabCard {
        val color = if (d.active) Green else Red
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(color))
            Spacer(Modifier.width(6.dp))
            Text(d.status.ifEmpty { if (d.active) "Активна" else "Неактивна" }, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(6.dp))
        if (d.days.isNotEmpty()) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(d.days, fontSize = 52.sp, fontWeight = FontWeight.Black, lineHeight = 52.sp)
                Spacer(Modifier.width(6.dp))
                Text(d.daysUnit, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                if (d.hours.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text(d.hours, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            Text("ОСТАЛОСЬ", fontSize = 11.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (d.heroText.isNotEmpty()) {
            Text(d.heroText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        if (d.until.isNotEmpty()) Text(d.until, fontSize = 14.sp)
    }
    d.traffic?.let { traffic ->
        CabCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ТРАФИК", fontSize = 11.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(traffic, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            LinearProgressIndicator(
                progress = { ((d.trafficPercent ?: 0f) / 100f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Green,
            )
            d.trafficNote?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    val limit = d.deviceLimit ?: devices?.second?.takeIf { it > 0 }
    if (limit != null || devices != null) CabCard {
        Text("УСТРОЙСТВА", fontSize = 11.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val count = devices?.first
        Text(
            when {
                count != null && limit != null -> "$count из $limit"
                count != null -> "$count"
                else -> "до $limit"
            },
            fontSize = 24.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "Лимит: " + (limit?.let { devicesWord(it) } ?: "без ограничения"),
            fontSize = 14.sp, fontWeight = FontWeight.Medium,
        )
    }
    d.clientId?.let { id ->
        CabCard {
            Row(Modifier.fillMaxWidth().clickable { onCopyId(id) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ВАШ ID", fontSize = 11.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(id, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Icon(Icons.Outlined.ContentCopy, "Скопировать", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PayPane(
    d: CabinetApi.Data,
    onPay: (CabinetApi.Tariff, String, done: () -> Unit) -> Unit,
    onCode: (String, done: () -> Unit) -> Unit,
) {
    var busy by remember { mutableStateOf(false) }
    for (kind in listOf("sub", "traffic")) {
        val all = d.tariffs.filter { it.kind == kind }
        if (all.isEmpty()) continue
        CabCard {
            Text(if (kind == "sub") "Продлить подписку" else "Докупить трафик", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            var limit by remember(d) { mutableStateOf(d.limits.firstOrNull()) }
            if (kind == "sub" && d.limits.isNotEmpty()) {
                Text("Лимит устройств — сколько одновременных подключений нужно. Фиксируется после первой оплаты.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.limits.forEach { l -> FilterChip(selected = limit == l, onClick = { limit = l }, label = { Text("$l") }) }
                }
            }
            val visible = if (kind == "sub" && d.limits.isNotEmpty()) all.filter { it.limit == limit } else all.filter { it.shown }.ifEmpty { all }
            var picked by remember(d, limit) { mutableStateOf(visible.singleOrNull()) }
            visible.forEach { t ->
                val on = picked?.id == t.id
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .border(BorderStroke(if (on) 1.5.dp else 1.dp, if (on) Brand else MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(14.dp))
                        .clickable { picked = t }.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.Medium)
                        val meta = listOf(t.limitLabel, t.trafficLabel).filter { it.isNotEmpty() }.joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(price(t.price), fontWeight = FontWeight.Bold)
                }
            }
            picked?.let { t ->
                val methods = d.methods[kind].orEmpty().filter { it.id in t.methods }
                    .ifEmpty { t.methods.map { CabinetApi.Method(it, "") } }
                if (methods.isEmpty()) Text("Оплата для этого тарифа недоступна", fontSize = 13.sp, color = Red)
                methods.forEach { m ->
                    Button(
                        onClick = { busy = true; onPay(t, m.id) { busy = false } },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        else Text("Оплатить ${price(t.price)}" + if (m.title.isNotEmpty()) " · ${m.title}" else "", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    if (d.tariffs.isEmpty()) Text("Сейчас оплата недоступна", color = MaterialTheme.colorScheme.onSurfaceVariant)
    // A payment code is rare: a quiet link at the bottom, the field in a dialog.
    if (d.promo) {
        var codeOpen by remember { mutableStateOf(false) }
        Text(
            "Есть код оплаты?",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().clickable { codeOpen = true }.padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
        if (codeOpen) {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!busy) codeOpen = false },
                title = { Text("Код оплаты") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(code, { code = it.take(64) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Введите код") })
                        Text("Продлевает подписку на указанное в коде число дней. Один код — один раз на аккаунт.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                confirmButton = {
                    Button(onClick = { busy = true; onCode(code.trim()) { busy = false; codeOpen = false } }, enabled = code.isNotBlank() && !busy) {
                        Text("Применить")
                    }
                },
                dismissButton = { TextButton(onClick = { codeOpen = false }, enabled = !busy) { Text("Отмена") } },
            )
        }
    }
}

private fun devicesWord(n: Int): String = "$n " + when {
    n % 100 in 11..14 -> "устройств"
    n % 10 == 1 -> "устройство"
    n % 10 in 2..4 -> "устройства"
    else -> "устройств"
}

private fun price(p: Double): String = (if (p % 1.0 == 0.0) p.toLong().toString() else "%.2f".format(p)) + " ₽"

