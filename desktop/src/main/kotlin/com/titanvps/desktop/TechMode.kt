@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.titanvps.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * «Технический режим»: hidden diagnostics for support. Opened by 7 taps on the version line
 * in Настройки and a code (only its hash is here). Checks run from this PC, directly or
 * through the running core's local HTTP inbound.
 */
object TechMode {
    /** sha256("titan-tech:" + code). */
    private const val CODE_HASH = "bc060f10652adaefa8eaed330239a6e27ed27b65dceb56f55b000e1c73bcd667"

    fun checkCode(code: String): Boolean = MessageDigest.getInstance("SHA-256")
        .digest("titan-tech:${code.trim()}".toByteArray())
        .joinToString("") { "%02x".format(it) } == CODE_HASH

    val ACCESS = listOf(
        "https://api1.titanvps.su", "https://api2.titanvps.online",
        "https://github.com", "https://t.me",
        "https://www.google.com", "https://www.youtube.com", "https://www.instagram.com",
        "https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru",
    )
    private val TRACE = listOf("https://1.1.1.1/cdn-cgi/trace", "https://www.cloudflare.com/cdn-cgi/trace")

    private fun client(proxyPort: Int) = OkHttpClient.Builder()
        .proxy(if (proxyPort > 0) Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)) else Proxy.NO_PROXY)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    private fun errorText(e: Throwable): String = when (e) {
        is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> "таймаут"
        is java.net.UnknownHostException -> "DNS: адрес не найден"
        is java.net.ConnectException -> "соединение отклонено"
        is javax.net.ssl.SSLException -> "TLS: ${e.message ?: e.javaClass.simpleName}"
        else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trimEnd(' ', ':')
    }

    /** "HTTP 200 · 123 мс" or the error; [proxyPort] 0 = directly. */
    fun probe(url: String, proxyPort: Int): String {
        val start = System.nanoTime()
        return runCatching {
            client(proxyPort).newCall(Request.Builder().url(url).head().header("User-Agent", Config.TITAN_UA).build())
                .execute().use { "HTTP ${it.code} · ${(System.nanoTime() - start) / 1_000_000} мс" }
        }.getOrElse(::errorText)
    }

    /** External IP and country from Cloudflare's trace. */
    fun externalIp(proxyPort: Int): String {
        var error = ""
        for (url in TRACE) {
            runCatching {
                client(proxyPort).newCall(Request.Builder().url(url).header("User-Agent", Config.TITAN_UA).build()).execute().use { r ->
                    val map = r.body.string().lines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
                    val ip = map["ip"] ?: throw IllegalStateException("нет ip= в ответе")
                    return "$ip · ${map["loc"] ?: "?"}"
                }
            }.onFailure { error = errorText(it) }
        }
        return "не удалось: $error"
    }

    // ------------------------------------------------------------------ servers

    data class Target(val name: String, val protocol: String, val host: String, val port: Int, val udp: Boolean) {
        val address: String get() = "$host:$port"
    }

    /** host:port and protocol of [server]'s outbounds (balancer members for АВТО). */
    fun targets(server: Server): List<Target> {
        val cfg = runCatching { JSONObject(server.xrayJson) }.getOrNull() ?: return emptyList()
        val outbounds = cfg.optJSONArray("outbounds") ?: return emptyList()
        val byTag = (0 until outbounds.length()).mapNotNull { outbounds.optJSONObject(it) }.associateBy { it.optString("tag") }
        val tags = XrayConfigs.pingTags(server.xrayJson, server.proxyTag)
        return tags.mapNotNull { tag ->
            val ob = byTag[tag] ?: return@mapNotNull null
            val (host, port) = hostPort(ob) ?: return@mapNotNull null
            val protocol = ob.optString("protocol").lowercase()
            val network = ob.optJSONObject("streamSettings")?.optString("network").orEmpty().lowercase()
            // Naive / Mieru / TUIC / MASQUE… are named by the panel (Plugins.spec), not by Xray's protocol.
            val plugin = Plugins.spec(server.name, ob)
            val label = plugin?.let { (k, h2) -> k.name.lowercase() + if (k == Plugins.Kind.MASQUE) (if (h2) "/h2" else "/h3") else "" }
                ?: (protocol + if (network.isNotEmpty() && network != "tcp" && network != "raw") "/$network" else "")
            // Same rule as PingEngine.isUdp, plus the UDP plugins.
            val udp = protocol in setOf("hysteria", "hysteria2", "tuic", "wireguard") || network in setOf("hysteria", "quic", "kcp", "mkcp") ||
                plugin?.let { (k, h2) -> k == Plugins.Kind.TUIC || (k == Plugins.Kind.MASQUE && !h2) } == true
            Target(if (tags.size > 1) "${server.name} · $tag" else server.name, label, host, port, udp)
        }
    }

    private fun hostPort(ob: JSONObject): Pair<String, Int>? {
        val s = ob.optJSONObject("settings") ?: return null
        val first = s.optJSONArray("vnext")?.optJSONObject(0) ?: s.optJSONArray("servers")?.optJSONObject(0)
        first?.let { if (it.optString("address").isNotEmpty()) return it.optString("address") to it.optInt("port") }
        if (s.optString("address").isNotEmpty()) return s.optString("address") to s.optInt("port")
        // WireGuard: "endpoint": "host:port".
        s.optJSONArray("peers")?.optJSONObject(0)?.optString("endpoint")?.takeIf { ':' in it }?.let { e ->
            return e.substringBeforeLast(':').trim('[', ']') to (e.substringAfterLast(':').toIntOrNull() ?: 0)
        }
        return null
    }

    /** Plain TCP connect, directly (past any proxy). */
    fun tcp(host: String, port: Int): String {
        val start = System.nanoTime()
        return runCatching {
            Socket(Proxy.NO_PROXY).use { it.connect(InetSocketAddress(host, port), 4000) }
            "${(System.nanoTime() - start) / 1_000_000} мс"
        }.getOrElse { "нет TCP (${errorText(it)})" }
    }

    // ------------------------------------------------------------------ network

    data class Net(val type: String, val adapters: List<String>, val otherVpn: List<String>)

    private val VPN_WORDS = listOf("wintun", "wireguard", "openvpn", "tap-windows", "tap-", "vpn", "amnezia", "outline", "zerotier", "tailscale", "tun")
    private val SKIP_WORDS = listOf("teredo", "isatap", "6to4", "pseudo", "loopback", "bluetooth", "virtualbox", "vmware", "hyper-v", "vethernet", "wsl")
    private val WIRELESS = listOf("wi-fi", "wifi", "wireless", "wlan", "802.11")
    private val MOBILE = listOf("mobile", "wwan", "cellular", "broadband", "lte", "rndis")

    private fun text(n: NetworkInterface) = "${n.name} ${n.displayName}".lowercase()
    private fun kind(n: NetworkInterface): String = text(n).let { t ->
        when {
            WIRELESS.any { it in t } -> "Wi‑Fi"
            MOBILE.any { it in t } -> "мобильная"
            else -> "Ethernet"
        }
    }

    fun network(): Net {
        val up = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { n -> runCatching { n.isUp && !n.isLoopback }.getOrDefault(false) }
            .filter { n -> n.inetAddresses.toList().any { !it.isLinkLocalAddress && !it.isLoopbackAddress } }
        val vpnLike = up.filter { n -> text(n).let { t -> t.contains(TunConfig.INTERFACE.lowercase()) || (VPN_WORDS.any { it in t } && SKIP_WORDS.none { it in t }) } }
        val physical = up.filter { n -> n !in vpnLike && SKIP_WORDS.none { it in text(n) } }
        // The adapter the default route uses: a UDP "connect" sends nothing, only picks the source address.
        val primary = runCatching {
            DatagramSocket().use { s -> s.connect(InetAddress.getByName("1.1.1.1"), 53); NetworkInterface.getByInetAddress(s.localAddress) }
        }.getOrNull()?.takeIf { it in physical } ?: physical.firstOrNull()
        val type = primary?.let { "${kind(it)} — ${it.displayName}" } ?: "нет сети"
        return Net(
            type,
            physical.map { "${kind(it)}: ${it.displayName}" },
            vpnLike.filterNot { it.displayName.equals(TunConfig.INTERFACE, true) || it.name.equals(TunConfig.INTERFACE, true) }.map { it.displayName },
        )
    }

    /** The app's own requests (subscription, whitelist check) and whether they go into our VPN. */
    fun ownTraffic(state: AppState): String {
        val exe = ProcessHandle.current().info().command().orElse("").substringAfterLast('\\').substringAfterLast('/')
        return when {
            state.vpn !is VpnState.Connected -> "напрямую (VPN выключен)"
            state.mode == "tun" && !exe.equals("Titan VPS.exe", true) -> "через VPN (TUN, процесс $exe не в исключениях)"
            state.mode == "tun" -> "напрямую — Titan VPS.exe исключён из TUN"
            else -> "напрямую — запросы приложения не используют системный прокси"
        }
    }

    // ------------------------------------------------------------------ device

    fun deviceModel(): String {
        if (isWindows) runCatching {
            val key = "HARDWARE\\DESCRIPTION\\System\\BIOS"
            val maker = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "SystemManufacturer")
            val product = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, "SystemProductName")
            return "$maker $product (${Hwid.model()})".trim()
        }
        return Hwid.model()
    }

    // ------------------------------------------------------------------ journal

    fun logFiles(): List<File> =
        AppPaths.logDir.listFiles { f -> f.isFile && f.length() > 0 }?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Last [lines] lines (reads at most the last 256 KB). */
    fun tail(file: File, lines: Int = 200): String = runCatching {
        RandomAccessFile(file, "r").use { f ->
            val from = (f.length() - 256 * 1024).coerceAtLeast(0)
            f.seek(from)
            val bytes = ByteArray((f.length() - from).toInt())
            f.readFully(bytes)
            String(bytes, Charsets.UTF_8).lines().let { if (from > 0) it.drop(1) else it }.takeLast(lines).joinToString("\n").trimEnd()
        }
    }.getOrElse { "не удалось прочитать: ${errorText(it)}" }
}

private val stamp get() = SimpleDateFormat("dd.MM.yyyy HH:mm:ss Z", Locale.forLanguageTag("ru"))

/** Results of the checks; lives while the screen is open. */
private class TechChecks {
    var net by mutableStateOf<TechMode.Net?>(null)
    var ipDirect by mutableStateOf<String?>(null)
    var ipVpn by mutableStateOf<String?>(null)
    /** url → direct result / through the VPN. */
    val access = mutableStateMapOf<String, String>()
    val accessVpn = mutableStateMapOf<String, String>()
    var accessRunning by mutableStateOf(false)
    var accessVia by mutableStateOf("")
    /** host:port → result. */
    val tcp = mutableStateMapOf<String, String>()
    var tcpRunning by mutableStateOf(false)
}

private fun CoroutineScope.runNetwork(state: AppState, c: TechChecks) = launch {
    val port = state.localProxyPort
    c.ipDirect = "проверяем…"
    c.ipVpn = if (port > 0) "проверяем…" else null
    c.net = withContext(Dispatchers.IO) { TechMode.network() }
    val direct = async(Dispatchers.IO) { TechMode.externalIp(0) }
    val vpn = if (port > 0) async(Dispatchers.IO) { TechMode.externalIp(port) } else null
    c.ipDirect = direct.await()
    c.ipVpn = vpn?.await()
}

private fun CoroutineScope.runAccess(state: AppState, c: TechChecks) = launch {
    if (c.accessRunning) return@launch
    c.accessRunning = true
    val port = state.localProxyPort
    c.accessVia = TechMode.ownTraffic(state) + if (port > 0) "; вторая колонка — через VPN (локальный прокси ядра 127.0.0.1:$port)" else ""
    c.access.clear()
    c.accessVpn.clear()
    TechMode.ACCESS.flatMap { url ->
        listOfNotNull(
            async { c.access[url] = withContext(Dispatchers.IO) { TechMode.probe(url, 0) } },
            if (port > 0) async { c.accessVpn[url] = withContext(Dispatchers.IO) { TechMode.probe(url, port) } } else null,
        )
    }.awaitAll()
    c.accessRunning = false
}

private fun uniqueTargets(state: AppState): List<TechMode.Target> =
    state.subscription?.servers.orEmpty().flatMap(TechMode::targets).distinctBy { it.address.lowercase() }

private fun CoroutineScope.runTcp(state: AppState, c: TechChecks) = launch {
    if (c.tcpRunning) return@launch
    c.tcpRunning = true
    c.tcp.clear()
    val limit = Semaphore(16)
    uniqueTargets(state).filterNot { it.udp }.map { t ->
        async { limit.withPermit { c.tcp[t.address] = withContext(Dispatchers.IO) { TechMode.tcp(t.host, t.port) } } }
    }.awaitAll()
    c.tcpRunning = false
}

// ------------------------------------------------------------------ sections (UI and report)

private fun deviceRows(state: AppState): List<Pair<String, String>> {
    val sub = state.subscription
    val server = state.selected
    val target = server?.let(TechMode::targets).orEmpty()
    val vpn = when (val v = state.vpn) {
        VpnState.Off -> "выключен"
        VpnState.Connecting -> "подключается… ${state.connectStep}"
        is VpnState.Connected -> "подключён с ${SimpleDateFormat("HH:mm:ss").format(Date(v.since))}"
        VpnState.Disconnecting -> "отключается…"
        is VpnState.Error -> "ошибка: ${v.message}"
    }
    val info = sub?.info
    return listOf(
        "Версия" to "${System.getProperty("jpackage.app-version") ?: "dev"} (сборка ${Updater.currentBuild})",
        "Платформа" to "Windows desktop · Java ${System.getProperty("java.version")}",
        "ОС" to "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})",
        "Устройство" to TechMode.deviceModel(),
        "User-Agent" to Config.TITAN_UA,
        "VPN" to vpn,
        "Режим" to (if (state.mode == "tun") "Весь компьютер (TUN)" else "Системный прокси") +
            " · права администратора: ${if (Admin.isAdmin) "да" else "нет"}",
        "Сервер" to (server?.name ?: "не выбран"),
        "Протокол / адрес" to when {
            server == null -> "—"
            target.isEmpty() -> "не удалось разобрать"
            target.size == 1 -> "${target[0].protocol} · ${target[0].address}"
            else -> "балансировщик, узлов: ${target.size} (${target.joinToString { "${it.protocol} ${it.address}" }})"
        },
        "Подписка" to when {
            sub == null || info == null -> "нет"
            else -> (if (info.expireAt > 0) "до ${SimpleDateFormat("dd.MM.yyyy HH:mm").format(Date(info.expireAt * 1000))}" else "бессрочно") +
                " · трафик ${formatBytes(info.usedBytes)} из ${if (info.totalBytes > 0) formatBytes(info.totalBytes) else "∞"}" +
                " · серверов ${sub.servers.size} · обновлена ${SimpleDateFormat("dd.MM HH:mm").format(Date(sub.fetchedAt))}"
        },
        "Время" to stamp.format(Date()),
    )
}

private fun networkRows(state: AppState, c: TechChecks): List<Pair<String, String>> {
    val net = c.net
    val ours = if (state.vpn is VpnState.Connected) "наш — ${if (state.mode == "tun") "TUN-адаптер «${TunConfig.INTERFACE}»" else "системный прокси"}" else "наш выключен"
    return listOf(
        "Тип подключения" to (net?.type ?: "не проверено"),
        "Адаптеры" to (net?.adapters?.joinToString("; ")?.ifEmpty { "нет" } ?: "—"),
        "VPN активен" to ours + " · другие: " + (net?.otherVpn?.joinToString("; ")?.ifEmpty { "нет" } ?: "не проверено"),
        "Запросы приложения" to TechMode.ownTraffic(state),
        "Внешний IP (напрямую)" to (c.ipDirect ?: "не проверено"),
        "Внешний IP (через VPN)" to (c.ipVpn ?: if (state.localProxyPort > 0) "не проверено" else "VPN выключен"),
    )
}

private fun bypassRows(state: AppState): List<Pair<String, String>> {
    val servers = state.subscription?.servers.orEmpty()
    val bypass = ServerGroups.split(servers)[ServerGroups.Group.BYPASS].orEmpty()
    return listOf(
        "Белые списки" to when (state.whitelist) {
            true -> "да — открываются только RU-сайты"
            false -> "нет — интернет обычный"
            null -> "не определено (ничего не ответило)"
        } + if (state.whitelistChecking) " · проверяем…" else "",
        "Обходы сейчас" to if (state.bypassLocked) "заблокированы (интернет обычный)" else "разрешены",
        "Серверов-обходов" to bypass.size.toString(),
        "Выбран обход" to if (state.selected?.let(ServerGroups::isBypass) == true) "да" else "нет",
        "Правило" to "На Windows обходы открываются, только когда интернет работает по белым спискам " +
            "(ya.ru / vk.com открываются, google.com / cloudflare.com — нет; напрямую, раз в 2 мин). Тип сети (Wi‑Fi) не учитывается.",
    )
}

private fun accessRows(state: AppState, c: TechChecks): List<Pair<String, String>> {
    val vpn = state.localProxyPort > 0 || c.accessVpn.isNotEmpty()
    return TechMode.ACCESS.map { url ->
        url.removePrefix("https://") to (c.access[url] ?: if (c.accessRunning) "…" else "—") +
            if (vpn) "   |   VPN: ${c.accessVpn[url] ?: if (c.accessRunning) "…" else "—"}" else ""
    }
}

private fun serverRows(state: AppState, c: TechChecks): List<Pair<String, String>> =
    uniqueTargets(state).map { t ->
        "${t.name} (${t.protocol} ${t.address})" to
            if (t.udp) "UDP — TCP-проверка не показательна" else c.tcp[t.address] ?: if (c.tcpRunning) "…" else "—"
    }

private fun buildReport(state: AppState, c: TechChecks, logName: String?, log: String): String = buildString {
    fun section(title: String, rows: List<Pair<String, String>>) {
        append("== ").append(title).append(" ==\n")
        rows.forEach { (k, v) -> append(k).append(": ").append(v).append('\n') }
        append('\n')
    }
    append("Titan VPS — технический отчёт (Windows)\n\n")
    section("Устройство", deviceRows(state))
    section("Сеть", networkRows(state, c))
    section("Обходы", bypassRows(state))
    section("Доступ", accessRows(state, c))
    if (c.accessVia.isNotEmpty()) append("Проверки доступа: ").append(c.accessVia).append("\n\n")
    section("Серверы (TCP)", serverRows(state, c))
    append("== Журнал").append(logName?.let { " ($it)" } ?: "").append(" ==\n").append(log.ifEmpty { "пусто" }).append('\n')
}

// ------------------------------------------------------------------ UI

/** Version line in Настройки: 7 taps (≤ 3 s apart) ask for the code; afterwards a visible row. */
@Composable
fun TechModeEntry(state: AppState, onOpen: () -> Unit) {
    var taps by remember { mutableIntStateOf(0) }
    var lastTap by remember { mutableLongStateOf(0L) }
    var askCode by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Версия ${System.getProperty("jpackage.app-version") ?: "dev"}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                val now = System.currentTimeMillis()
                taps = if (now - lastTap <= 3000) taps + 1 else 1
                lastTap = now
                if (taps >= 7) {
                    taps = 0
                    if (state.techMode) onOpen() else askCode = true
                }
            },
        )
        if (state.techMode) TextButton(onClick = onOpen) { Text("Технический режим", fontSize = 12.sp) }
    }
    if (askCode) CodeDialog({ askCode = false }) { code ->
        askCode = false
        if (TechMode.checkCode(code)) {
            state.changeTechMode(true)
            onOpen()
        } else {
            state.message = "Неверный код"
        }
    }
}

@Composable
private fun CodeDialog(onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Код") },
        text = {
            OutlinedTextField(
                code, { code = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit(code) }),
            )
        },
        confirmButton = { Button(onClick = { onSubmit(code) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
fun TechModePage(state: AppState, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val c = remember { TechChecks() }
    var logs by remember { mutableStateOf<List<File>>(emptyList()) }
    var logFile by remember { mutableStateOf<File?>(null) }
    var log by remember { mutableStateOf("") }
    fun reloadLog() = scope.launch {
        val files = withContext(Dispatchers.IO) { TechMode.logFiles() }
        logs = files
        val pick = logFile?.takeIf { it in files } ?: files.firstOrNull { it.name == "xray-error.log" } ?: files.firstOrNull()
        logFile = pick
        log = pick?.let { withContext(Dispatchers.IO) { TechMode.tail(it) } }.orEmpty()
    }
    fun checkAll() {
        state.checkWhitelist()
        scope.runNetwork(state, c)
        scope.runAccess(state, c)
        scope.runTcp(state, c)
        reloadLog()
    }
    LaunchedEffect(Unit) {
        scope.runNetwork(state, c)
        reloadLog()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                Text("Технический режим", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { checkAll() }) { Text("Проверить всё") }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(buildReport(state, c, logFile?.name, log)))
                    state.message = "Отчёт скопирован"
                }) { Text("Копировать отчёт") }
                OutlinedButton(onClick = { state.changeTechMode(false); onBack() }) { Text("Выключить технический режим", color = Red) }
            }
            TechCard("Устройство") { Rows(deviceRows(state)) }
            TechCard("Сеть") {
                Rows(networkRows(state, c))
                TextButton(onClick = { scope.runNetwork(state, c) }) { Text("Проверить сеть") }
            }
            TechCard("Обходы") {
                Rows(bypassRows(state))
                TextButton(onClick = { state.checkWhitelist() }, enabled = !state.whitelistChecking) { Text("Проверить белые списки") }
            }
            TechCard("Доступ") {
                Caption(
                    "HEAD-запрос, таймаут 6 с, без перехода по редиректам. " +
                        (c.accessVia.ifEmpty { "Запросы: " + TechMode.ownTraffic(state) + if (state.localProxyPort > 0) "; вторая колонка — через VPN" else "" }),
                )
                Spacer(Modifier.height(6.dp))
                Rows(accessRows(state, c))
                TextButton(onClick = { scope.runAccess(state, c) }, enabled = !c.accessRunning) { Text("Проверить доступ") }
            }
            TechCard("Серверы") {
                Caption(
                    "TCP-подключение к host:port каждого сервера напрямую, таймаут 4 с. «нет TCP» — IP/порт заблокирован или сервер " +
                        "лежит; TCP есть, а пинг в приложении не проходит — протокол режется (DPI).",
                )
                Spacer(Modifier.height(6.dp))
                Rows(serverRows(state, c))
                TextButton(onClick = { scope.runTcp(state, c) }, enabled = !c.tcpRunning) { Text("Проверить серверы") }
            }
            TechCard("Журнал") {
                Caption("Логи ядра из ${AppPaths.logDir.absolutePath}, последние 200 строк.")
                if (logs.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    logs.forEach { f ->
                        FilterChip(selected = f == logFile, onClick = { logFile = f; reloadLog() }, label = { Text(f.name, fontSize = 12.sp) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp).clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(10.dp),
                ) {
                    SelectionContainer {
                        Text(log.ifEmpty { "Логов нет" }, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                TextButton(onClick = { reloadLog() }) { Text("Обновить журнал") }
            }
        }
    }
}

@Composable
private fun TechCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Rows(rows: List<Pair<String, String>>) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (k, v) ->
                Row {
                    Text(k, Modifier.width(200.dp).padding(end = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(v, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
