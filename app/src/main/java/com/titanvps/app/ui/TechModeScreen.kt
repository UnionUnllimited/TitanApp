package com.titanvps.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titanvps.app.BuildConfig
import com.titanvps.app.TitanApp
import com.titanvps.app.core.Plugins
import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.data.NetworkMonitor
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.Subscription
import com.titanvps.app.data.UserAgent
import com.titanvps.app.ui.theme.ProvideTitanTextScale
import com.titanvps.app.vpn.BypassReturn
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hidden «Технический режим» for support: device, network, bypasses, reachability of our
 * and popular hosts, TCP to every server and the logs, all copyable as one report.
 * Our app is excluded from its own VPN (addDisallowedApplication in TitanVpnService), so
 * every check here goes straight to the network, as before connecting.
 */
@Composable
internal fun TechModeScreen(
    viewModel: MainViewModel,
    sub: Subscription,
    onOpenLogs: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val vpnState by viewModel.vpnState.collectAsState()
    val selectedId by viewModel.selectedId.collectAsState()
    val autoBypass by viewModel.autoBypass.collectAsState()
    val onMobile by viewModel.onMobile.collectAsState()
    val account by viewModel.account.collectAsState()
    val selected = sub.servers.firstOrNull { it.id == selectedId } ?: sub.servers.firstOrNull()

    var network by remember { mutableStateOf(networkRows(context)) }
    var ipDirect by remember { mutableStateOf("—") }
    var ipVpn by remember { mutableStateOf("—") }
    var access by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tcp by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var tcpRunning by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf("") }
    val endpoints = remember(sub) { TechChecks.endpoints(sub.servers) }

    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { logs = withContext(Dispatchers.IO) { readLogs(context.cacheDir, 60, 140) } }

    fun checkServers() {
        if (tcpRunning) return
        tcpRunning = true
        tcp = endpoints.filter { it.udp }.associate { it.key to "UDP — TCP-проверка не показательна" }
        val limit = Semaphore(16)
        val jobs = endpoints.filterNot { it.udp }.map { ep ->
            scope.launch {
                val r = limit.withPermit { withContext(Dispatchers.IO) { TechChecks.tcp(ep.host, ep.port) } }
                tcp = tcp + (ep.key to r)
            }
        }
        scope.launch { jobs.forEach { it.join() }; tcpRunning = false }
    }

    fun checkAll() {
        network = networkRows(context)
        ipDirect = "…"
        scope.launch { ipDirect = withContext(Dispatchers.IO) { TechChecks.trace(TechChecks.direct) } }
        val viaVpn = TechChecks.viaVpn()
        ipVpn = if (viaVpn == null) "VPN выключен" else "…"
        if (viaVpn != null) scope.launch { ipVpn = withContext(Dispatchers.IO) { TechChecks.trace(viaVpn) } }
        access = TechChecks.ACCESS_URLS.associateWith { "…" }
        TechChecks.ACCESS_URLS.forEach { url ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { TechChecks.probe(url) }
                access = access + (url to r)
            }
        }
        checkServers()
        scope.launch { logs = withContext(Dispatchers.IO) { readLogs(context.cacheDir, 60, 140) } }
    }

    val devRows = deviceRows(vpnState, sub, selected, account)
    val netRows = network + listOf(
        "Запросы приложения" to "напрямую, мимо VPN Titan (приложение исключено из своего туннеля); " +
            "если включён чужой VPN — через него",
        "Внешний IP (напрямую)" to ipDirect,
        "Внешний IP (через VPN)" to ipVpn,
    )
    val bypassCount = ServerGroups.split(sub.servers).toMap()[ServerGroups.Group.BYPASS].orEmpty().size
    val selectedGroup = selected?.let { ServerGroups.groupOf(it, sub.servers) }
    // Read on every redraw: a switch changes vpnState, which redraws this screen.
    val returnTo = BypassReturn.load(context)?.takeIf { it.bypassId == selected?.id }
        ?.let { saved -> sub.servers.firstOrNull { it.id == saved.returnTo } }
    val bypassRows = listOf(
        "Обходы сейчас" to (if (onMobile) "разрешены (мобильный интернет)" else "разрешены на Wi‑Fi (технический режим)"),
        "Серверов-обходов" to "$bypassCount",
        "Выбран" to (selectedGroup?.title ?: "—"),
        "Автопереход на обходы" to (if (autoBypass) "включён" else "выключен"),
        "Слежение за белыми списками" to when {
            vpnState !is VpnState.Connected -> "не работает (VPN выключен)"
            selectedGroup == ServerGroups.Group.BYPASS ->
                "на обходе: каждые 2 мин на мобильном — открываются ли зарубежные сайты напрямую и отвечает ли обычный сервер"
            else -> "работает: каждые 30 с на мобильном; 2 неответа сервера подряд, молчат и другие серверы, " +
                "ya.ru / vk.com / gosuslugi.ru открываются, а зарубежные сайты нет, и так же через 20 с — " +
                (if (autoBypass) "переход на обход" else "уведомление")
        },
        "Возврат с обхода" to (returnTo?.let { "на «${it.name.trim()}», когда ограничения снимут" } ?: "нет"),
    )
    val accessRows = TechChecks.ACCESS_URLS.map { it.removePrefix("https://") to (access[it] ?: "—") }
    val serverRows = endpoints.map { "${it.label} (${it.protocol}, ${it.host}:${it.port})" to (tcp[it.key] ?: "—") }

    fun report(): String = buildString {
        fun section(title: String, rows: List<Pair<String, String>>) {
            append("== $title ==\n")
            rows.forEach { (k, v) -> append("$k: $v\n") }
            append('\n')
        }
        section("Устройство", deviceRows(vpnState, sub, selected, account))
        section("Сеть", netRows)
        section("Обходы", bypassRows)
        section("Доступ (напрямую, мимо VPN)", accessRows)
        section("Серверы (TCP)", serverRows)
        append("== Журнал ==\n").append(logs)
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar("Технический режим", onBack = onBack)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { checkAll() }, modifier = Modifier.weight(1f)) { Text("Проверить всё") }
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(report())) }, modifier = Modifier.weight(1f)) {
                    Text("Копировать отчёт")
                }
            }
            OutlinedButton(onClick = { viewModel.setTechMode(false); onBack() }, modifier = Modifier.fillMaxWidth()) {
                Text("Выключить технический режим", color = MaterialTheme.colorScheme.error)
            }

            SectionCard(title = "Устройство") { Rows(devRows) }
            SectionCard(title = "Сеть") { Rows(netRows) }
            SectionCard(title = "Обходы") { Rows(bypassRows) }
            SectionCard(title = "Доступ") {
                Caption("HTTPS-запрос (HEAD, 6 с): код ответа и время. Идёт напрямую, мимо VPN Titan.")
                Rows(accessRows)
            }
            SectionCard(title = "Серверы") {
                Caption(
                    "TCP-подключение к каждому серверу подписки (4 с), напрямую. «нет TCP» — IP/порт заблокирован или " +
                        "сервер лежит; TCP есть, а пинг в приложении не проходит — протокол режется (DPI)."
                )
                OutlinedButton(onClick = { checkServers() }, enabled = !tcpRunning, modifier = Modifier.fillMaxWidth()) {
                    Text(if (tcpRunning) "Проверяем…" else "Проверить серверы")
                }
                Rows(serverRows)
            }
            SectionCard(title = "Журнал") {
                Caption("Журнал Xray текущего подключения (ошибки и последние соединения).")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { scope.launch { logs = withContext(Dispatchers.IO) { readLogs(context.cacheDir, 60, 140) } } },
                        modifier = Modifier.weight(1f),
                    ) { Text("Обновить") }
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text("Весь журнал") }
                }
                SelectionContainer(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp)
                        .clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(8.dp)
                ) {
                    Text(logs, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }
    }
}

/** Label above, monospace value below: long values (UA, hosts) stay readable and copyable. */
@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { (label, value) ->
                Column {
                    Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
}

private fun deviceRows(
    vpnState: VpnState,
    sub: Subscription,
    selected: Server?,
    account: com.titanvps.app.data.AccountApi.Info?,
): List<Pair<String, String>> {
    val info = sub.info
    val endpoint = selected?.let { s ->
        val tags = XrayConfigs.pingTags(s.xrayJson, s.proxyTag)
        if (tags.size > 1) "балансировщик, узлов: ${tags.size}"
        else TechChecks.endpointOf(s.name, s.xrayJson, s.proxyTag)?.let { "${it.protocol}, ${it.host}:${it.port}" } ?: "—"
    } ?: "—"
    return listOfNotNull(
        "Приложение" to "${BuildConfig.VERSION_NAME} (сборка ${BuildConfig.VERSION_CODE})",
        "Платформа" to "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        "Устройство" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "User-Agent" to UserAgent.titan,
        "VPN" to when (vpnState) {
            is VpnState.Connected -> "подключён: ${vpnState.serverName}, ${formatDuration(System.currentTimeMillis() - vpnState.since)}"
            VpnState.Connecting -> "подключается"
            VpnState.Disconnecting -> "отключается"
            VpnState.Disconnected -> "выключен"
            is VpnState.Error -> "ошибка: ${vpnState.message}"
        },
        "Сервер" to (selected?.name?.trim() ?: "—"),
        "Протокол / адрес" to endpoint,
        "Подписка до" to formatExpiry(info.expireAt),
        "Трафик" to (formatBytes(info.usedBytes) + " из " + (if (info.totalBytes > 0) formatBytes(info.totalBytes) else "∞")),
        account?.let { "Аккаунт" to "${it.status}, устройств ${it.devices.size}/${it.deviceLimit}" },
        "Время" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()),
    )
}

/** Physical network (our process isn't in our VPN, so activeNetwork is the real one). */
private fun networkRows(context: Context): List<Pair<String, String>> {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
    val type = when {
        caps == null -> "нет сети"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
            "Wi‑Fi" + (if (NetworkMonitor.isMobileLike(caps)) " (лимитный — как мобильный, например точка доступа)" else "")
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "другая"
    }
    @Suppress("DEPRECATION")
    val vpnUp = cm?.allNetworks.orEmpty().any { n -> cm?.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    val ours = VpnStatus.state.value is VpnState.Connected
    val vpn = when {
        ours -> "Titan VPN"
        vpnUp -> "чужой VPN"
        else -> "нет"
    }
    return listOf("Подключение" to type, "Активный VPN" to vpn)
}

/** Network checks of the tech screen. Only public hosts: the repo is public. */
private object TechChecks {

    val ACCESS_URLS = listOf(
        "https://api1.titanvps.su", "https://api2.titanvps.online",
        "https://github.com", "https://t.me",
        "https://www.google.com", "https://www.youtube.com", "https://www.instagram.com",
        "https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru",
    )
    private val TRACE_URLS = listOf("https://1.1.1.1/cdn-cgi/trace", "https://www.cloudflare.com/cdn-cgi/trace")

    val direct: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Through the running core's local proxy (as AccountApi), or null while the VPN is off. */
    fun viaVpn(): OkHttpClient? {
        val lp = VpnStatus.localProxy ?: return null
        return direct.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", lp.port)))
            .proxyAuthenticator { _, response ->
                response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(lp.user, lp.password)).build()
            }
            .build()
    }

    /** "IP · страна" from Cloudflare's trace, or the error. */
    fun trace(client: OkHttpClient): String {
        var error = "ошибка"
        for (url in TRACE_URLS) {
            try {
                client.newCall(Request.Builder().url(url).header("User-Agent", UserAgent.titan).build()).execute().use { r ->
                    val body = r.body.string()
                    val values = body.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 } }
                        .associate { it[0] to it[1] }
                    val ip = values["ip"]
                    if (ip != null) return "$ip · ${values["loc"] ?: "?"}"
                    error = "HTTP ${r.code}"
                }
            } catch (e: Exception) {
                error = e.describe()
            }
        }
        return error
    }

    fun probe(url: String): String {
        val start = System.nanoTime()
        return try {
            direct.newCall(Request.Builder().url(url).head().header("User-Agent", UserAgent.titan).build()).execute().use { r ->
                "HTTP ${r.code} · ${(System.nanoTime() - start) / 1_000_000} мс"
            }
        } catch (e: Exception) {
            e.describe()
        }
    }

    fun tcp(host: String, port: Int): String {
        val address = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return "нет DNS"
        }
        val start = System.nanoTime()
        return try {
            Socket().use { it.connect(InetSocketAddress(address, port), 4_000) }
            "${(System.nanoTime() - start) / 1_000_000} мс"
        } catch (e: Exception) {
            "нет TCP"
        }
    }

    private fun Exception.describe(): String = "${javaClass.simpleName}: ${message ?: "—"}"

    data class Endpoint(val label: String, val protocol: String, val host: String, val port: Int, val udp: Boolean) {
        val key: String get() = "${host.lowercase()}:$port"
    }

    /** Every distinct host:port of the subscription (balancer servers: each member). */
    fun endpoints(servers: List<Server>): List<Endpoint> = servers.flatMap { s ->
        val tags = XrayConfigs.pingTags(s.xrayJson, s.proxyTag)
        tags.mapNotNull { tag ->
            endpointOf(s.name, s.xrayJson, tag)?.let { if (tags.size > 1) it.copy(label = "${it.label} · $tag") else it }
        }
    }.distinctBy { it.key }

    /** Protocol and address of an outbound; plugin servers (Naive, TUIC, MASQUE…) as their own protocol. */
    fun endpointOf(name: String, xrayJson: String, tag: String): Endpoint? = runCatching {
        val label = name.trim()
        Plugins.endpoint(name, xrayJson, tag)?.let { ep ->
            val udp = ep.kind == Plugins.Kind.TUIC || (ep.kind == Plugins.Kind.MASQUE && !ep.http2)
            val protocol = ep.kind.name.lowercase() + if (ep.kind == Plugins.Kind.MASQUE && ep.http2) "-h2" else ""
            return@runCatching Endpoint(label, protocol, ep.host, ep.port, udp)
        }
        val outbounds = JSONObject(xrayJson).optJSONArray("outbounds") ?: return@runCatching null
        val ob = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }
            .firstOrNull { it.optString("tag") == tag } ?: return@runCatching null
        val protocol = ob.optString("protocol").lowercase()
        val network = ob.optJSONObject("streamSettings")?.optString("network").orEmpty().lowercase()
        val settings = ob.optJSONObject("settings") ?: return@runCatching null
        val target = settings.optJSONArray("vnext")?.optJSONObject(0) ?: settings.optJSONArray("servers")?.optJSONObject(0)
        var host = target?.optString("address").orEmpty().ifEmpty { settings.optString("address") }
        var port = target?.optInt("port") ?: settings.optInt("port")
        if (port == 0) port = settings.optInt("port")
        if (host.isEmpty()) {
            // WireGuard: peers[0].endpoint = "host:port".
            val endpoint = settings.optJSONArray("peers")?.optJSONObject(0)?.optString("endpoint").orEmpty()
            host = endpoint.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
            port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 0
        }
        if (host.isEmpty() || port <= 0) return@runCatching null
        val udp = protocol in UDP_PROTOCOLS || network in UDP_NETWORKS
        val shown = if (network.isNotEmpty() && network != "tcp" && network != "raw") "$protocol/$network" else protocol
        Endpoint(label, shown, host, port, udp)
    }.getOrNull()

    // Same as PingService.isUdp: these go over UDP, so a TCP connect says nothing.
    private val UDP_PROTOCOLS = setOf("hysteria", "hysteria2", "tuic", "wireguard")
    private val UDP_NETWORKS = setOf("hysteria", "quic", "kcp", "mkcp")
}

/** «Код» for the hidden mode; only its hash is stored here (the repo is public). */
@Composable
internal fun TechCodeDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val submit: () -> Unit = {
        if (techCodeMatches(code)) {
            onUnlocked()
        } else {
            wrong = true
            code = ""
        }
    }
    // A dialog is a window of its own, with the system's text size otherwise.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ProvideTitanTextScale { Text("Код") } },
        text = {
            ProvideTitanTextScale {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isDigit); wrong = false },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) { { Text("Неверный код") } } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { ProvideTitanTextScale { TextButton(onClick = submit) { Text("OK") } } },
        dismissButton = { ProvideTitanTextScale { TextButton(onClick = onDismiss) { Text("Отмена") } } },
    )
}

private fun techCodeMatches(code: String): Boolean {
    val hash = MessageDigest.getInstance("SHA-256").digest("titan-tech:$code".toByteArray())
        .joinToString("") { "%02x".format(it) }
    return hash == TECH_CODE_SHA256
}

private const val TECH_CODE_SHA256 = "bc060f10652adaefa8eaed330239a6e27ed27b65dceb56f55b000e1c73bcd667"
