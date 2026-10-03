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

    fun setTheme(value: String) {
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
                    core.start(XrayConfigs.buildProxyConfig(server.xrayJson, socks, http, AppPaths.logDir.absolutePath.replace('\\', '/')), geo)
                    SystemProxy.enable(http)
                }
                vpn = VpnState.Connected(System.currentTimeMillis())
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
        core.stop()
        runCatching { SystemProxy.restore() }
    }
}
