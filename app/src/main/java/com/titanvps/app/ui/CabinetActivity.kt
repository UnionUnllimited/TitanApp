package com.titanvps.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.CreditCardOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.titanvps.app.TitanApp
import com.titanvps.app.data.CabinetApi
import com.titanvps.app.data.Fmt
import com.titanvps.app.ui.kit.CircleIconButton
import com.titanvps.app.ui.kit.GradientBar
import com.titanvps.app.ui.kit.IconTile
import com.titanvps.app.ui.kit.PrimaryButton
import com.titanvps.app.ui.kit.SecondaryButton
import com.titanvps.app.ui.kit.SegmentItem
import com.titanvps.app.ui.kit.SegmentedTabs
import com.titanvps.app.ui.kit.Skeleton
import com.titanvps.app.ui.kit.TitanCard
import com.titanvps.app.ui.kit.pressable
import com.titanvps.app.ui.kit.rememberHaptics
import com.titanvps.app.ui.theme.AccentGradient
import com.titanvps.app.ui.theme.DangerGradient
import com.titanvps.app.ui.theme.GreenGradient
import com.titanvps.app.ui.theme.HeroInactiveGradient
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.ui.theme.Titan
import com.titanvps.app.ui.theme.TitanTheme
import com.titanvps.app.ui.theme.WarnGradient
import com.titanvps.app.ui.theme.fixedSp
import com.titanvps.app.ui.theme.iosSpring
import com.titanvps.app.ui.theme.isDark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Personal cabinet in the app: the site's limited cabinet (signed in by the subscription's
 * link, no email or Telegram login) shown natively — the subscription, renewal tariffs,
 * payment codes. Only the payment page itself opens (in [PaymentActivity]).
 */
class CabinetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val link = intent.getStringExtra(EXTRA_LINK) ?: run { finish(); return }
        // Connected devices / limit from the subscription's account info (-1: not known).
        val devices = intent.getIntExtra(EXTRA_DEVICES, -1).takeIf { it >= 0 }?.let { it to intent.getIntExtra(EXTRA_LIMIT, 0) }
        setContent {
            val mode by TitanApp.get(this).settings.theme.collectAsState()
            val dark = isDark(mode)
            // Status/navigation bar icons follow the app theme, as on the main screen.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            TitanTheme(mode) {
                CabinetScreen(link, devices, onClose = ::finish)
            }
        }
    }

    companion object {
        private const val EXTRA_LINK = "link"
        private const val EXTRA_DEVICES = "devices"
        private const val EXTRA_LIMIT = "limit"

        /** [devices]: connected devices and the limit (0 = not known), if the app knows them. */
        fun open(context: Context, link: String, devices: Pair<Int, Int>? = null) {
            context.startActivity(
                Intent(context, CabinetActivity::class.java).putExtra(EXTRA_LINK, link)
                    .putExtra(EXTRA_DEVICES, devices?.first ?: -1).putExtra(EXTRA_LIMIT, devices?.second ?: 0)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private const val LOAD_FAILED = "Не удалось загрузить кабинет. Проверьте интернет и попробуйте ещё раз"
private const val CHECKING = "Проверяем оплату: кабинет обновится через несколько секунд"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CabinetScreen(link: String, devices: Pair<Int, Int>?, onClose: () -> Unit) {
    val context = LocalContext.current
    val palette = Titan.palette
    val scope = rememberCoroutineScope()
    var api by remember { mutableStateOf<CabinetApi?>(null) }
    var data by remember { mutableStateOf<CabinetApi.Data?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var pulling by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    // A payment page was opened: its result is read when the cabinet is back on screen.
    var awaitingPayment by remember { mutableStateOf(false) }
    var recheck by remember { mutableIntStateOf(0) }
    var loadJob by remember { mutableStateOf<Job?>(null) }
    // The site itself: coming back to it ends a payment.
    val home = remember(link) { runCatching { java.net.URI(link).host }.getOrNull() }

    // A newer load replaces one still running.
    fun load(): Job {
        loadJob?.cancel()
        loading = true
        error = null
        val job = scope.launch {
            try {
                val current = api
                if (current == null) {
                    val (a, d) = withContext(Dispatchers.IO) { CabinetApi.open(link) }
                    api = a
                    data = d
                } else {
                    data = withContext(Dispatchers.IO) { current.load() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CabinetApi.CabinetException) {
                error = e.message
            } catch (e: Exception) {
                error = LOAD_FAILED
            } finally {
                if (loadJob === coroutineContext[Job]) loading = false
            }
        }
        loadJob = job
        return job
    }
    LaunchedEffect(Unit) { load() }
    // Back from a payment: the cabinet now, and again a little later (the provider may tell
    // the site a moment after the user is back).
    LifecycleResumeEffect(Unit) {
        if (awaitingPayment) {
            awaitingPayment = false
            note = CHECKING
            load()
            recheck++
        }
        onPauseOrDispose { }
    }
    LaunchedEffect(recheck) {
        if (recheck == 0) return@LaunchedEffect
        for (wait in listOf(4_000L, 8_000L)) {
            delay(wait)
            load().join()
        }
        if (note == CHECKING) note = null
    }

    Column(Modifier.fillMaxSize().background(palette.background).safeDrawingPadding()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp)) {
            CloseCircle(onClose, Modifier.align(Alignment.CenterStart))
            Text(
                "Личный кабинет",
                Modifier.align(Alignment.Center),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // Not dimmed while it turns; a tap meanwhile does nothing.
            CircleIconButton(
                Icons.Filled.Refresh,
                "Обновить",
                onClick = { if (!loading) load() },
                modifier = Modifier.align(Alignment.CenterEnd),
                size = 32.dp,
                spinning = loading,
            )
        }
        SegmentedTabs(
            items = listOf(SegmentItem("Подписка", TitanIcons.Crown), SegmentItem("Оплатить", Icons.Filled.CreditCard)),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
        )
        val d = data
        when {
            d != null -> PullToRefreshBox(
                isRefreshing = pulling,
                // Its own spinner for a pull only; the bar's arrow turns for any load.
                onRefresh = {
                    pulling = true
                    scope.launch {
                        load().join()
                        pulling = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                    Column(
                        Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        note?.let { Banner(it, Icons.Filled.Info, palette.primary) }
                        error?.let { Banner(it, Icons.Filled.Warning, palette.red) }
                        Crossfade(targetState = tab, label = "cabinetTab") { shown ->
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                if (shown == 0) {
                                    SubscriptionPane(d, devices) { id -> copy(context, id) }
                                } else {
                                    PayPane(
                                        d,
                                        onPay = { tariff, method, done ->
                                            val a = api
                                            if (a == null) {
                                                done()
                                            } else {
                                                scope.launch {
                                                    try {
                                                        val url = withContext(Dispatchers.IO) { a.pay(tariff, method) }
                                                        PaymentActivity.open(
                                                            context,
                                                            url,
                                                            title = tariff.name + " · " + Fmt.price(tariff.price),
                                                            homeHost = home,
                                                        )
                                                        awaitingPayment = true
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        error = (e as? CabinetApi.CabinetException)?.message ?: "Ошибка соединения"
                                                    } finally {
                                                        done()
                                                    }
                                                }
                                            }
                                        },
                                        onCode = { code, done ->
                                            val a = api
                                            if (a == null) {
                                                done(false)
                                            } else {
                                                scope.launch {
                                                    var ok = false
                                                    try {
                                                        note = withContext(Dispatchers.IO) { a.applyCode(code) }
                                                        ok = true
                                                        load()
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (e: Exception) {
                                                        error = (e as? CabinetApi.CabinetException)?.message ?: "Ошибка соединения"
                                                    } finally {
                                                        done(ok)
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            loading -> LoadingShapes()
            else -> Failure(error ?: LOAD_FAILED) { load() }
        }
    }
}

/** The round close button of a bar: a cross on the track colour. */
@Composable
private fun CloseCircle(onClick: () -> Unit, modifier: Modifier) {
    val palette = Titan.palette
    Box(
        modifier
            .pressable(onClick = onClick, scale = 0.9f)
            .size(32.dp)
            .background(palette.track, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Close, "Закрыть", Modifier.size(13.dp), tint = palette.textSecondary)
    }
}

/** A note (a code applied, a payment being checked) or an error, above the cabinet. */
@Composable
private fun Banner(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = color)
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** While the cabinet loads the first time: the shapes of what is coming, with a shine. */
@Composable
private fun LoadingShapes() {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(16.dp).semantics { contentDescription = "Загрузка" },
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Skeleton(Modifier.fillMaxWidth().height(200.dp), RoundedCornerShape(24.dp))
            Skeleton(Modifier.fillMaxWidth().height(110.dp), RoundedCornerShape(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Skeleton(Modifier.weight(1f).height(130.dp), RoundedCornerShape(20.dp))
                Skeleton(Modifier.weight(1f).height(130.dp), RoundedCornerShape(20.dp))
            }
        }
    }
}

@Composable
private fun Failure(message: String, onRetry: () -> Unit) {
    val palette = Titan.palette
    Column(
        Modifier.fillMaxWidth().padding(32.dp).padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(88.dp).background(palette.primary.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.WifiOff, null, Modifier.size(38.dp), tint = palette.primary)
        }
        Text("Кабинет не загрузился", fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(message, color = palette.textSecondary, fontSize = 14.sp, textAlign = TextAlign.Center)
        PrimaryButton(
            "Повторить",
            onRetry,
            Modifier.widthIn(max = 240.dp).padding(top = 4.dp),
            icon = Icons.Filled.Refresh,
            minHeight = 50.dp,
        )
    }
}

@Composable
private fun SubscriptionPane(d: CabinetApi.Data, devices: Pair<Int, Int>?, onCopyId: (String) -> Unit) {
    val palette = Titan.palette
    Hero(d)
    d.traffic?.let { traffic ->
        // The page gives the share used in percent.
        val used = ((d.trafficPercent ?: 0f) / 100f).coerceIn(0f, 1f)
        TitanCard(contentPadding = PaddingValues(18.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconTile(Icons.Filled.PieChart, palette.primary, size = 38.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Трафик", color = palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            traffic,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                // Green while there's plenty, orange then red as it runs out.
                GradientBar(
                    used,
                    when {
                        used < 0.7f -> GreenGradient
                        used < 0.9f -> WarnGradient
                        else -> DangerGradient
                    },
                )
                d.trafficNote?.let { Text(it, color = palette.textSecondary, fontSize = 12.sp) }
            }
        }
    }
    val limit = d.deviceLimit ?: devices?.second?.takeIf { it > 0 }
    val showDevices = limit != null || devices != null
    if (showDevices || d.clientId != null) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (showDevices) {
                Tile(Modifier.weight(1f)) {
                    IconTile(Icons.Filled.Smartphone, palette.violet, size = 38.dp)
                    Text("Устройства", color = palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    val count = devices?.first
                    Text(
                        when {
                            count != null && limit != null -> "$count из $limit"
                            count != null -> "$count"
                            else -> "до $limit"
                        },
                        fontSize = fixedSp(28f),
                        lineHeight = fixedSp(34f),
                        fontWeight = FontWeight.ExtraBold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                    Text(
                        "Лимит: " + (limit?.let { "$it " + Fmt.devicesWord(it) } ?: "без ограничения"),
                        color = palette.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            d.clientId?.let { id -> IdTile(id, Modifier.weight(1f)) { onCopyId(id) } }
        }
    }
}

/** Дни подписки on the brand gradient (red when it's over), a crown in the corner. */
@Composable
private fun Hero(d: CabinetApi.Data) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(24.dp)
    val glow = (if (d.active) palette.primary else palette.red).copy(alpha = 0.35f)
    val white = Color.White
    Box(
        Modifier
            .fillMaxWidth()
            .shadow(18.dp, shape, clip = false, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(if (d.active) AccentGradient else HeroInactiveGradient)
            .border(1.dp, white.copy(alpha = 0.15f), shape),
    ) {
        Box(Modifier.matchParentSize()) {
            Icon(
                TitanIcons.Crown,
                null,
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 26.dp, y = (-6).dp)
                    .requiredSize(130.dp)
                    .rotate(-14f),
                tint = white.copy(alpha = 0.12f),
            )
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier
                    .padding(bottom = 4.dp)
                    .background(white.copy(alpha = 0.2f), CircleShape)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(white, CircleShape))
                Text(
                    d.status.ifEmpty { if (d.active) "Активна" else "Неактивна" },
                    color = white,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (d.days.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        d.days,
                        Modifier.alignByBaseline(),
                        color = white,
                        fontSize = fixedSp(64f),
                        lineHeight = fixedSp(74f),
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                    Text(
                        d.daysUnit,
                        Modifier.alignByBaseline(),
                        color = white.copy(alpha = 0.85f),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    if (d.hours.isNotEmpty()) {
                        Text(
                            d.hours,
                            Modifier.alignByBaseline(),
                            color = white.copy(alpha = 0.85f),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
                Text(
                    "ОСТАЛОСЬ",
                    color = white.copy(alpha = 0.75f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.6.sp,
                )
            } else if (d.heroText.isNotEmpty()) {
                Text(d.heroText, color = white, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            if (d.until.isNotEmpty()) {
                Row(
                    Modifier
                        .padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.18f), CircleShape)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(Icons.Filled.CalendarMonth, null, Modifier.size(16.dp), tint = white)
                    Text(d.until, color = white, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A half-width card of the dashboard (devices, ID); as tall as its neighbour. */
@Composable
private fun Tile(modifier: Modifier, content: @Composable () -> Unit) {
    TitanCard(modifier.fillMaxHeight(), contentPadding = PaddingValues(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** Ваш ID: a tap copies it, the tile says so for a moment. */
@Composable
private fun IdTile(id: String, modifier: Modifier, onCopy: () -> Unit) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_600)
            copied = false
        }
    }
    Tile(
        modifier.pressable(
            onClick = {
                onCopy()
                haptics.success()
                copied = true
            },
            scale = 0.97f,
            onClickLabel = "Скопировать",
        ),
    ) {
        IconTile(if (copied) Icons.Filled.Check else Icons.Filled.Badge, if (copied) palette.green else palette.orange, size = 38.dp)
        Text("Ваш ID", color = palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(
            id,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val color = if (copied) palette.green else palette.textSecondary
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(if (copied) Icons.Filled.CheckCircle else Icons.Filled.ContentCopy, null, Modifier.size(14.dp), tint = color)
            Text(
                if (copied) "Скопировано" else "Нажмите, чтобы скопировать",
                color = color,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun PayPane(
    d: CabinetApi.Data,
    onPay: (CabinetApi.Tariff, String, done: () -> Unit) -> Unit,
    onCode: (String, done: (Boolean) -> Unit) -> Unit,
) {
    val palette = Titan.palette
    var busy by remember { mutableStateOf(false) }
    // Лимит устройств chosen (first purchase), and the tariff picked in each section.
    var limit by remember(d.limits) { mutableStateOf(d.limits.firstOrNull()) }
    var picked by remember { mutableStateOf(mapOf<String, String>()) }
    for (kind in listOf("sub", "traffic")) {
        val all = d.tariffs.filter { it.kind == kind }
        if (all.isEmpty()) continue
        val visible = if (kind == "sub" && d.limits.isNotEmpty()) all.filter { it.limit == limit } else all.filter { it.shown }.ifEmpty { all }
        // A single tariff is picked already.
        val selected = visible.firstOrNull { it.id == picked[kind] } ?: visible.singleOrNull()
        key(kind) {
            PaySection(
                kind = kind,
                limits = if (kind == "sub") d.limits else emptyList(),
                limit = limit,
                onLimit = {
                    limit = it
                    picked = picked - kind
                },
                visible = visible,
                selected = selected,
                onPick = { picked = picked + (kind to it.id) },
                methods = d.methods[kind].orEmpty(),
                busy = busy,
                onPay = { t, method ->
                    busy = true
                    onPay(t, method) { busy = false }
                },
            )
        }
    }
    if (d.tariffs.isEmpty()) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.CreditCardOff, null, Modifier.size(34.dp), tint = palette.textSecondary)
            Text("Сейчас оплата недоступна", color = palette.textSecondary)
        }
    }
    // A payment code is rare: a quiet button at the bottom, the field in a dialog.
    if (d.promo) {
        var codeOpen by remember { mutableStateOf(false) }
        var code by remember { mutableStateOf("") }
        SecondaryButton(
            "Есть код оплаты?",
            { codeOpen = true },
            icon = Icons.Filled.ConfirmationNumber,
            tint = palette.textSecondary,
            minHeight = 46.dp,
        )
        if (codeOpen) {
            AlertDialog(
                onDismissRequest = { if (!busy) codeOpen = false },
                title = { ProvideTitanTextScale { Text("Код оплаты") } },
                text = {
                    ProvideTitanTextScale {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                code,
                                { code = it.take(64) },
                                Modifier.fillMaxWidth(),
                                singleLine = true,
                                placeholder = { Text("Введите код") },
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Characters,
                                    autoCorrectEnabled = false,
                                ),
                            )
                            Text(
                                "Продлевает подписку на указанное в коде число дней. Один код — один раз на аккаунт.",
                                color = palette.textSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                confirmButton = {
                    ProvideTitanTextScale {
                        TextButton(
                            onClick = {
                                busy = true
                                onCode(code.trim()) { ok ->
                                    busy = false
                                    codeOpen = false
                                    if (ok) code = ""
                                }
                            },
                            enabled = code.isNotBlank() && !busy,
                        ) { Text("Применить") }
                    }
                },
                dismissButton = {
                    ProvideTitanTextScale {
                        TextButton(onClick = { codeOpen = false }, enabled = !busy) { Text("Отмена") }
                    }
                },
            )
        }
    }
}

/** Продлить подписку / Докупить трафик: the device limit, the tariffs, the pay buttons. */
@Composable
private fun PaySection(
    kind: String,
    limits: List<Int>,
    limit: Int?,
    onLimit: (Int) -> Unit,
    visible: List<CabinetApi.Tariff>,
    selected: CabinetApi.Tariff?,
    onPick: (CabinetApi.Tariff) -> Unit,
    methods: List<CabinetApi.Method>,
    busy: Boolean,
    onPay: (CabinetApi.Tariff, String) -> Unit,
) {
    val palette = Titan.palette
    val haptics = rememberHaptics()
    val sub = kind == "sub"
    TitanCard(contentPadding = PaddingValues(18.dp)) {
        Column(Modifier.animateContentSize(tween(250)), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(if (sub) TitanIcons.Crown else Icons.Filled.PieChart, if (sub) palette.primary else palette.violet, size = 38.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (sub) "Продлить подписку" else "Докупить трафик", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (sub) "Дни добавятся к текущей подписке" else "Гигабайты для обходов",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
            if (limits.isNotEmpty()) {
                LimitPicker(limits, limit) {
                    haptics.selection()
                    onLimit(it)
                }
            }
            visible.forEach { t ->
                TariffRow(t, on = selected?.id == t.id) {
                    haptics.selection()
                    onPick(t)
                }
            }
            if (selected != null) {
                val list = methods.filter { it.id in selected.methods }
                    .ifEmpty { selected.methods.map { CabinetApi.Method(it, "") } }
                if (list.isEmpty()) {
                    Text("Оплата для этого тарифа недоступна", color = palette.red, fontSize = 13.sp)
                }
                list.forEach { m ->
                    PrimaryButton(
                        "Оплатить ${Fmt.price(selected.price)}" + if (m.title.isNotEmpty()) " · ${m.title}" else "",
                        {
                            haptics.medium()
                            onPay(selected, m.id)
                        },
                        icon = Icons.Filled.CreditCard,
                        busy = busy,
                        minHeight = 52.dp,
                    )
                }
            } else if (visible.isNotEmpty()) {
                Text(
                    "Выберите тариф, чтобы оплатить",
                    Modifier.fillMaxWidth(),
                    color = palette.textSecondary,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Лимит устройств: how many at once; fixed after the first payment. */
@Composable
private fun LimitPicker(limits: List<Int>, limit: Int?, onPick: (Int) -> Unit) {
    val palette = Titan.palette
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Filled.Smartphone, null, Modifier.size(15.dp), tint = palette.textSecondary)
            Text("Лимит устройств", color = palette.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            limits.forEach { l ->
                val on = limit == l
                val fill by animateFloatAsState(if (on) 1f else 0f, iosSpring(0.3f, 0.8f), label = "limitFill")
                val text by animateColorAsState(if (on) Color.White else MaterialTheme.colorScheme.onSurface, tween(200), label = "limitText")
                val shape = RoundedCornerShape(12.dp)
                val glow = palette.primary.copy(alpha = 0.35f)
                Box(
                    Modifier
                        .pressable(onClick = { if (!on) onPick(l) }, scale = 0.9f, role = Role.RadioButton)
                        .semantics { selected = on }
                        .size(46.dp, 42.dp)
                        .then(if (on) Modifier.shadow(6.dp, shape, clip = false, ambientColor = glow, spotColor = glow) else Modifier)
                        .background(palette.track, shape)
                        .background(AccentGradient, shape, alpha = fill.coerceIn(0f, 1f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$l", color = text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(
            "Сколько устройств одновременно. Фиксируется после первой оплаты.",
            color = palette.textSecondary,
            fontSize = 12.sp,
        )
    }
}

/** A tariff to pick: a radio, its name and what it gives, the price. */
@Composable
private fun TariffRow(t: CabinetApi.Tariff, on: Boolean, onClick: () -> Unit) {
    val palette = Titan.palette
    val shape = RoundedCornerShape(16.dp)
    val dot by animateFloatAsState(if (on) 1f else 0f, iosSpring(0.3f, 0.8f), label = "tariffDot")
    val meta = listOf(t.limitLabel, t.trafficLabel).filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = onClick, scale = 0.98f, role = Role.RadioButton)
            .semantics { selected = on }
            .heightIn(min = 62.dp)
            .background(if (on) palette.primary.copy(alpha = 0.1f) else palette.track.copy(alpha = 0.55f), shape)
            .then(if (on) Modifier.border(1.5.dp, palette.primary, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(22.dp).border(2.dp, if (on) palette.primary else palette.outline, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        scaleX = dot
                        scaleY = dot
                    }
                    .background(palette.primary, CircleShape),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(t.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (meta.isNotEmpty()) Text(meta, color = palette.textSecondary, fontSize = 12.sp)
        }
        Text(
            Fmt.price(t.price),
            color = if (on) palette.primary else MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            style = TextStyle(fontFeatureSettings = "tnum"),
        )
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("ID", text))
}
