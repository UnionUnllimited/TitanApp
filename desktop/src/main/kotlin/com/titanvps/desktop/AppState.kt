package com.titanvps.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

sealed interface UpdateState {
    data object Idle : UpdateState
    data object ConnectingVpn : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Updater.Release) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Error(val message: String) : UpdateState
}

sealed interface VpnState {
    data object Off : VpnState
    data object Connecting : VpnState
    data class Connected(val since: Long) : VpnState
    data object Disconnecting : VpnState
    data class Error(val message: String) : VpnState
}

/** Everything the UI shows; work runs on IO, state is changed on the UI thread. */
class AppState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
    private val core = XrayProcess("core")
    private val tun = SingBoxProcess()
    /** Naive / Mieru client of the connected server, if it is one. */
    private var plugin: PluginProcess? = null

    var subscription by mutableStateOf<Subscription?>(null); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var theme by mutableStateOf("system"); private set
    /** "proxy" (system proxy) or "tun" (whole PC, needs administrator rights). */
    var mode by mutableStateOf("proxy"); private set
    var excludedApps by mutableStateOf<Set<String>>(emptySet()); private set
    var autoConnect by mutableStateOf(false); private set
    var autostart by mutableStateOf(false); private set
    /** Account status and devices from the bot (null until loaded). */
    var account by mutableStateOf<Account.Info?>(null); private set
    var accountLoading by mutableStateOf(false); private set
    /** Asks to restart as administrator (TUN). */
    var needAdmin by mutableStateOf(false)
    var vpn by mutableStateOf<VpnState>(VpnState.Off); private set
    var busy by mutableStateOf(false); private set
    var pinging by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    val pings = mutableStateMapOf<String, Long>()
    /** Servers unreachable directly that work through another one: server id → relay id. */
    val relayed = mutableStateMapOf<String, String>()
    var update by mutableStateOf<UpdateState>(UpdateState.Idle); private set
    /** Shown as a dialog: set by a manual check or when a new version is found. */
    var updateDialog by mutableStateOf(false)
    /** Local HTTP inbound of the running core (updates download through it). */
    private var proxyPort = 0
    private var updateJob: kotlinx.coroutines.Job? = null
    private var notifiedBuild = 0

    val selected: Server? get() = subscription?.let { s -> s.servers.firstOrNull { it.id == selectedId } ?: s.servers.firstOrNull() }

    init {
        // Crash recovery: a proxy we set earlier must not stay on without a running core.
        runCatching { SystemProxy.restore() }
        val st = Repository.load()
        subscription = st.subscription
        selectedId = st.selectedId
        theme = st.theme
        mode = st.mode
        excludedApps = st.excludedApps
        autoConnect = st.autoConnect
        scope.launch { autostart = withContext(Dispatchers.IO) { Autostart.isEnabled } }
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        if (subscription != null) {
            refresh(silent = true)
            loadAccount()
            if (autoConnect) connect()
        }
    }

    private fun persist() = Repository.save(Repository.State(subscription, selectedId, theme, mode, excludedApps, autoConnect))

    fun activate(text: String) {
        val url = Links.find(text.trim()) ?: run { message = "Это не ключ Titan VPS"; return }
        load(url, silent = false) { message = "Подписка подключена"; loadAccount() }
    }

    fun refresh(silent: Boolean = false) {
        val url = subscription?.url ?: return
        load(url, silent) { if (!silent) message = "Подписка обновлена" }
    }

    private fun load(url: String, silent: Boolean, onDone: () -> Unit = {}) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val sub = withContext(Dispatchers.IO) { Repository.fetch(url) }
                subscription = sub
                if (sub.servers.none { it.id == selectedId }) selectedId = sub.servers.first().id
                withContext(Dispatchers.IO) { persist() }
                onDone()
            } catch (e: Exception) {
                if (!silent) message = e.message ?: "Ошибка"
            } finally {
                busy = false
            }
        }
    }

    fun select(id: String) {
        if (id == selectedId) return
        selectedId = id
        scope.launch(Dispatchers.IO) { persist() }
        if (vpn is VpnState.Connected) connect()
    }

    /** System proxy ↔ TUN; reconnects if on. TUN needs administrator rights. */
    fun changeMode(value: String) {
        if (value == mode) return
        if (value == "tun" && !Admin.isAdmin) {
            mode = value
            scope.launch(Dispatchers.IO) { persist() }
            needAdmin = true
            return
        }
        mode = value
        scope.launch(Dispatchers.IO) { persist() }
        if (vpn is VpnState.Connected) connect()
    }

    fun setExcluded(exe: String, excluded: Boolean) {
        excludedApps = if (excluded) excludedApps + exe else excludedApps.filterNot { it.equals(exe, true) }.toSet()
        scope.launch(Dispatchers.IO) { persist() }
    }

    /** Applies the exclusion list (TUN restarts with it). */
    fun applyExclusions() {
        if (vpn is VpnState.Connected && mode == "tun") connect()
    }

    /** Restart as administrator for TUN; on refusal fall back to the system proxy. */
    fun restartAsAdmin() {
        needAdmin = false
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                persist()
                Admin.relaunchElevated()
            }
            if (ok) {
                withContext(Dispatchers.IO) { shutdown() }
                kotlin.system.exitProcess(0)
            } else {
                message = "Без прав администратора режим TUN недоступен"
            }
        }
    }

    fun cancelAdmin() {
        needAdmin = false
        mode = "proxy"
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun changeAutoConnect(value: Boolean) {
        autoConnect = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    /** Start with Windows (a logon task with admin rights when we have them, for TUN). */
    fun changeAutostart(value: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Autostart.set(value, Admin.isAdmin) }.isSuccess }
            autostart = withContext(Dispatchers.IO) { Autostart.isEnabled }
            if (!ok) message = "Не удалось изменить автозапуск"
        }
    }

    fun loadAccount() {
        val url = subscription?.url ?: return
        if (accountLoading) return
        accountLoading = true
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { Account.fetch(url, proxyPort) } }
                .onSuccess { account = it }
            accountLoading = false
        }
    }

    fun changeTheme(value: String) {
        theme = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun connect() {
        val sub = subscription ?: return
        val server = selected ?: return
        if (mode == "tun" && !Admin.isAdmin) { needAdmin = true; return }
        vpn = VpnState.Connecting
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val geo = GeoFiles.prepare(sub.servers.map { it.xrayJson })
                    val (socks, http) = freePorts(2)
                    val logDir = AppPaths.logDir.absolutePath.replace('\\', '/')
                    // Naive / Mieru: their own client as a local SOCKS, Xray's proxy outbound
                    // points at it; MASQUE: Xray's own client.
                    plugin?.close()
                    plugin = null
                    val (prepared, p) = Plugins.prepare(server)
                    plugin = p
                    // Blocked directly: through the relay the ping found (or the best one now).
                    val relay = relayed[server.id]?.let { id -> sub.servers.firstOrNull { it.id == id } }
                        ?: if (pings[server.id] == -1L && Relay.canChain(server)) Relay.pick(sub.servers, pings.toMap(), server) else null
                    val serverJson = relay?.let { Relay.chain(prepared, server.proxyTag, it) } ?: prepared
                    if ("\"tcpFastOpen\":true" in serverJson) Admin.enableTcpFastOpen()
                    core.start(XrayConfigs.buildProxyConfig(serverJson, socks, http, logDir, server.proxyTag), geo)
                    if (mode == "tun") {
                        // The whole PC through the tunnel; no system proxy then.
                        runCatching { SystemProxy.restore() }
                        tun.start(TunConfig.build(socks, excludedApps, logDir))
                    } else {
                        tun.stop()
                        SystemProxy.enable(http)
                    }
                    proxyPort = http
                }
                vpn = VpnState.Connected(System.currentTimeMillis())
                watchUpdates()
            } catch (e: Exception) {
                System.err.println("connect failed: $e")
                withContext(Dispatchers.IO) { tun.stop(); core.stop(); plugin?.close(); plugin = null; runCatching { SystemProxy.restore() } }
                // No technical details for clients.
                vpn = VpnState.Error("Не удалось подключиться. Попробуйте другой сервер или ещё раз")
            }
        }
    }

    fun disconnect() {
        if (vpn !is VpnState.Connected && vpn != VpnState.Connecting) return
        vpn = VpnState.Disconnecting
        scope.launch {
            withContext(Dispatchers.IO) { shutdown() }
            vpn = VpnState.Off
        }
    }

    // ------------------------------------------------------------ updates

    /** While connected: check 15 s after connecting, then every 12 h; tell once per version. */
    private fun watchUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            kotlinx.coroutines.delay(15_000)
            while (true) {
                if (vpn is VpnState.Connected && update !is UpdateState.Downloading && update != UpdateState.Installing) {
                    val found = runCatching { withContext(Dispatchers.IO) { Updater.findNewer(proxyPort) } }.getOrNull()
                    if (found != null) {
                        update = UpdateState.Available(found)
                        if (found.build != notifiedBuild) { notifiedBuild = found.build; updateDialog = true }
                    }
                }
                kotlinx.coroutines.delay(12 * 60 * 60 * 1000L)
            }
        }
    }

    /** "Обновление приложения": turns the VPN on if needed (GitHub only through it), then checks. */
    fun checkUpdate() {
        if (update is UpdateState.Downloading || update == UpdateState.Installing) return
        updateDialog = true
        scope.launch {
            if (!ensureVpn()) { update = UpdateState.Error("Не удалось включить VPN. Попробуйте ещё раз"); return@launch }
            update = UpdateState.Checking
            update = runCatching { withContext(Dispatchers.IO) { Updater.findNewer(proxyPort) } }
                .fold({ it?.let(UpdateState::Available) ?: UpdateState.UpToDate }, { UpdateState.Error("Не удалось проверить обновление") })
        }
    }

    fun installUpdate() {
        val release = (update as? UpdateState.Available)?.release ?: return
        scope.launch {
            if (!ensureVpn()) { update = UpdateState.Error("Не удалось включить VPN. Попробуйте ещё раз"); return@launch }
            update = UpdateState.Downloading(0f)
            try {
                val msi = withContext(Dispatchers.IO) {
                    Updater.download(release, proxyPort) { p -> scope.launch { update = UpdateState.Downloading(p) } }
                }
                update = UpdateState.Installing
                withContext(Dispatchers.IO) {
                    Updater.installAndRestart(msi)
                    shutdown()
                }
                kotlin.system.exitProcess(0)
            } catch (e: Exception) {
                update = UpdateState.Error("Не удалось скачать обновление. Попробуйте ещё раз")
            }
        }
    }

    fun dismissUpdate() {
        updateDialog = false
        if (update !is UpdateState.Available && update !is UpdateState.Downloading && update != UpdateState.Installing) update = UpdateState.Idle
    }

    private suspend fun ensureVpn(): Boolean {
        if (vpn is VpnState.Connected) return true
        if (subscription == null) return false
        update = UpdateState.ConnectingVpn
        if (vpn != VpnState.Connecting) connect()
        repeat(90) {
            if (vpn is VpnState.Connected) return true
            if (vpn is VpnState.Error) return false
            kotlinx.coroutines.delay(500)
        }
        return vpn is VpnState.Connected
    }

    /** Servers being measured right now (spinner, or the old value dimmed). */
    var measuring by mutableStateOf<Set<String>>(emptySet()); private set

    /** Every server, [firstIds] (the tab on screen) first; old values stay until new ones arrive. */
    fun pingAll(firstIds: List<String> = emptyList()) {
        val servers = subscription?.servers ?: return
        if (pinging) return
        val ids = servers.map { it.id }
        pinging = true
        ping(servers, firstIds.filter { it in ids } + ids.filter { it !in firstIds }) { pinging = false }
    }

    /** One server; works any time, also while the whole list is being measured. */
    fun pingOne(id: String) {
        val servers = subscription?.servers ?: return
        if (servers.none { it.id == id } || id in measuring) return
        measuring = measuring + id
        ping(servers, listOf(id)) {}
    }

    private fun ping(servers: List<Server>, targets: List<String>, onDone: () -> Unit) {
        scope.launch {
            val answered = HashSet<String>()
            try {
                withContext(Dispatchers.IO) {
                    val direct = java.util.concurrent.ConcurrentHashMap<String, Long>()
                    Pinger.ping(
                        servers, targets,
                        onStart = { ids -> scope.launch { measuring = measuring + ids } },
                        onResult = { id, ms ->
                            direct[id] = ms
                            scope.launch { answered += id; pings[id] = ms; if (ms >= 0) relayed.remove(id); measuring = measuring - id }
                        },
                    )
                    tryRelays(servers, direct)
                }
            } catch (e: Exception) {
                if (targets.size > 1) message = "Не удалось проверить пинг. Попробуйте ещё раз"
            } finally {
                // Let queued results land first.
                kotlinx.coroutines.yield()
                targets.filter { it !in answered && it !in pings }.forEach { pings[it] = -1 }
                measuring = measuring - targets.toSet()
                onDone()
            }
        }
    }

    /**
     * Servers that timed out directly (an IP blocked on this network): measured again through
     * the fastest server that answered; the ones that work are connected that way.
     */
    private fun tryRelays(servers: List<Server>, direct: Map<String, Long>) {
        val known = HashMap<String, Long>(pings.toMap()).apply { putAll(direct) }
        val probes = direct.filterValues { it < 0 }.keys
            .mapNotNull { id -> servers.firstOrNull { it.id == id } }
            .filter(Relay::canChain)
            .mapNotNull { s ->
                val relay = Relay.pick(servers, known, s) ?: return@mapNotNull null
                val (json, p) = runCatching { Plugins.prepare(s) }.getOrNull() ?: return@mapNotNull null
                p?.close()
                s.copy(xrayJson = Relay.chain(json, s.proxyTag, relay)) to relay.id
            }
        if (probes.isEmpty()) return
        val via = probes.associate { (s, relayId) -> s.id to relayId }
        runCatching {
            RelayPinger.ping(
                probes.map { it.first }, probes.map { it.first.id },
                onStart = { ids -> scope.launch { measuring = measuring + ids } },
                onResult = { id, ms ->
                    scope.launch {
                        if (ms >= 0) { pings[id] = ms; relayed[id] = via.getValue(id) }
                        measuring = measuring - id
                    }
                },
            )
        }
    }

    /** Личный кабинет: the Telegram bot (renewal, extra GB). */
    fun openCabinetUrl(): String = Config.TELEGRAM_URL

    fun logout() {
        disconnect()
        subscription = null
        selectedId = null
        account = null
        pings.clear()
        relayed.clear()
        measuring = emptySet()
        scope.launch(Dispatchers.IO) { persist() }
    }

    @Synchronized
    fun shutdown() {
        updateJob?.cancel()
        proxyPort = 0
        runCatching { Pinger.stop() }
        runCatching { RelayPinger.stop() }
        tun.stop()
        core.stop()
        plugin?.close()
        plugin = null
        runCatching { SystemProxy.restore() }
    }
}
