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

    var subscription by mutableStateOf<Subscription?>(null); private set
    var selectedId by mutableStateOf<String?>(null); private set
    var theme by mutableStateOf("system"); private set
    var vpn by mutableStateOf<VpnState>(VpnState.Off); private set
    var busy by mutableStateOf(false); private set
    var pinging by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)
    val pings = mutableStateMapOf<String, Long>()
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
        Runtime.getRuntime().addShutdownHook(Thread { shutdown() })
        if (subscription != null) refresh(silent = true)
    }

    private fun persist() = Repository.save(Repository.State(subscription, selectedId, theme))

    fun activate(text: String) {
        val url = Links.find(text.trim()) ?: run { message = "Это не ключ Titan VPS"; return }
        load(url, silent = false) { message = "Подписка подключена" }
    }

    fun refresh(silent: Boolean = false) {
        val url = subscription?.url ?: return
        load(url, silent)
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

    fun changeTheme(value: String) {
        theme = value
        scope.launch(Dispatchers.IO) { persist() }
    }

    fun connect() {
        val sub = subscription ?: return
        val server = selected ?: return
        vpn = VpnState.Connecting
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val geo = GeoFiles.prepare(sub.servers.map { it.xrayJson })
                    val (socks, http) = freePorts(2)
                    core.start(XrayConfigs.buildProxyConfig(server.xrayJson, socks, http, AppPaths.logDir.absolutePath.replace('\\', '/'), server.proxyTag), geo)
                    SystemProxy.enable(http)
                    proxyPort = http
                }
                vpn = VpnState.Connected(System.currentTimeMillis())
                watchUpdates()
            } catch (e: Exception) {
                withContext(Dispatchers.IO) { core.stop(); runCatching { SystemProxy.restore() } }
                vpn = VpnState.Error(e.message ?: "Не удалось подключиться")
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

    fun pingAll() {
        val servers = subscription?.servers ?: return
        if (pinging) return
        pinging = true
        pings.clear()
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Pinger.pingAll(servers) { id, ms -> scope.launch { pings[id] = ms } }
                }
            } catch (e: Exception) {
                message = "Пинг не выполнен: ${e.message}"
            } finally {
                pinging = false
            }
        }
    }

    fun logout() {
        disconnect()
        subscription = null
        selectedId = null
        pings.clear()
        scope.launch(Dispatchers.IO) { persist() }
    }

    @Synchronized
    fun shutdown() {
        updateJob?.cancel()
        proxyPort = 0
        core.stop()
        runCatching { SystemProxy.restore() }
    }
}
