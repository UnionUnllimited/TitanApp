package com.titanvps.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.titanvps.app.BuildConfig
import com.titanvps.app.TitanApp
import com.titanvps.app.core.PingClient
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TitanApp.get(app).repository
    private val settings = TitanApp.get(app).settings

    val subscription = repo.subscription
    val selectedId = repo.selectedId
    val vpnState = VpnStatus.state
    val excludedApps = settings.excludedApps
    val autoBypass = settings.autoBypass
    val theme = settings.theme
    val autoConnect = settings.autoConnect
    val notifications = settings.notifications
    val favorites = settings.favorites
    val onMobile = TitanApp.get(app).network.onMobile

    // Updates turn the VPN on themselves (through MainActivity, which handles the VPN prompt).
    private val updater = com.titanvps.app.data.Updater(app) { _connectRequests.trySend(Unit) }
    val update = updater.state
    fun checkUpdate() = viewModelScope.launch { updater.check() }
    fun downloadUpdate(r: com.titanvps.app.data.Updater.Release) = viewModelScope.launch { updater.downloadAndInstall(r) }
    fun dismissUpdate() = updater.reset()
    fun openInstallPermission() = updater.openInstallPermission()

    /** Bypass servers are for mobile internet only; tells the user why otherwise. */
    fun canConnect(): Boolean {
        val sub = subscription.value ?: return true
        val server = sub.servers.firstOrNull { it.id == selectedId.value } ?: sub.servers.firstOrNull() ?: return true
        val bypass = com.titanvps.app.data.ServerGroups.groupOf(server, sub.servers) == com.titanvps.app.data.ServerGroups.Group.BYPASS
        if (bypass && !TitanApp.get(getApplication<Application>()).network.current()) {
            _message.value = com.titanvps.app.data.NetworkMonitor.BYPASS_WIFI_MESSAGE
            return false
        }
        return true
    }

    fun explainBypassOnWifi() {
        _message.value = com.titanvps.app.data.NetworkMonitor.BYPASS_WIFI_MESSAGE
    }

    fun setTheme(mode: com.titanvps.app.data.ThemeMode) = settings.setTheme(mode)
    fun setAutoConnect(enabled: Boolean) = settings.setAutoConnect(enabled)
    fun setNotifications(enabled: Boolean) = settings.setNotifications(enabled)
    fun toggleFavorite(name: String) = settings.toggleFavorite(name)
    val ruAppsBypass = settings.ruAppsBypass
    val ruAppsOff = settings.ruAppsOff

    fun setRuAppsBypass(enabled: Boolean) {
        settings.setRuAppsBypass(enabled)
    }

    fun setAutoBypass(enabled: Boolean) = settings.setAutoBypass(enabled)

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    /** URLs the UI should open in the browser. */
    private val _openUrl = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.BUFFERED)
    val openUrl = _openUrl.receiveAsFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** serverId → delay ms (-1 = unreachable). */
    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pings = _pings.asStateFlow()

    /** serverId → why its ping failed (shown in the long-press sheet only). */
    private val _pingErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val pingErrors = _pingErrors.asStateFlow()

    private val _pinging = MutableStateFlow(false)
    val pinging = _pinging.asStateFlow()

    init {
        if (repo.isStale()) refresh(silent = true)
    }

    // ------------------------------------------------------------ subscription

    /** Fired after a key is added: connect right away (Android then turns other VPNs off). */
    private val _connectRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val connectRequests = _connectRequests.receiveAsFlow()

    fun activate(link: String) = launchBusy {
        val first = repo.subscription.value == null
        repo.activate(link)
        _message.value = "Подписка подключена"
        if (first || vpnState.value !is VpnState.Connected) _connectRequests.send(Unit)
    }

    fun refresh(silent: Boolean = false) = launchBusy(silent) {
        repo.refresh()
    }

    fun select(serverId: String) {
        if (serverId == repo.selectedId.value) return
        repo.select(serverId)
        // Reconnect on the new server if we're online.
        if (vpnState.value is VpnState.Connected) TitanVpnService.start(getApplication<Application>())
    }

    /**
     * Personal cabinet: the panel's profile-web-page-url (the same link Happ opens with ⓘ),
     * otherwise the Telegram bot.
     * Re-fetched first, since it can be a short-lived magic link.
     */
    /** Fresh magic link if the server answers quickly, otherwise the saved one, else the bot. */
    fun openCabinet() = viewModelScope.launch {
        _busy.value = true
        runCatching { kotlinx.coroutines.withTimeout(6_000) { repo.refresh() } }
        _busy.value = false
        _openUrl.send(subscription.value?.info?.webPageUrl ?: BuildConfig.TELEGRAM_URL)
    }

    fun disconnect() = TitanVpnService.stop(getApplication<Application>())

    fun logout() {
        disconnect()
        repo.logout()
        _pings.value = emptyMap()
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** Real ping through every server; results arrive progressively. */
    fun pingAll() {
        val servers = subscription.value?.servers ?: return
        ping(servers, clear = true)
    }

    /** Ping a single server (long press on a location). */
    fun pingOne(serverId: String) {
        val server = subscription.value?.servers?.firstOrNull { it.id == serverId } ?: return
        _pings.value = _pings.value - serverId
        ping(listOf(server), clear = false)
    }

    private fun ping(servers: List<com.titanvps.app.data.Server>, clear: Boolean) {
        if (_pinging.value) return
        viewModelScope.launch {
            _pinging.value = true
            if (clear) _pings.value = emptyMap()
            _pingErrors.value = _pingErrors.value - servers.map { it.id }.toSet()
            try {
                withTimeoutOrNull(150_000) {
                    PingClient.ping(getApplication<Application>(), servers).collect { event ->
                        when (event) {
                            is PingClient.Event.Partial -> {
                                _pings.value = _pings.value + event.delays
                                _pingErrors.value = _pingErrors.value + event.errors
                            }
                            is PingClient.Event.Done -> if (clear) event.error?.let {
                                android.util.Log.w("Titan", "ping failed: $it")
                                _message.value = "Не удалось проверить пинг. Попробуйте позже"
                            }
                        }
                    }
                }
            } finally {
                // Anything still unanswered counts as a timeout.
                _pings.value = servers.associate { it.id to -1L } + _pings.value
                _pinging.value = false
            }
        }
    }

    // ------------------------------------------------------------ settings

    fun setExcluded(packageName: String, excluded: Boolean) = settings.setExcluded(packageName, excluded)

    /** Resets app settings (excluded apps, chosen server); the key stays. */
    fun resetSettings() {
        settings.reset()
        subscription.value?.servers?.firstOrNull()?.let { repo.select(it.id) }
        _message.value = "Настройки сброшены"
        if (vpnState.value is VpnState.Connected) TitanVpnService.start(getApplication<Application>())
    }

    /** Re-applies settings that need a reconnect (e.g. excluded apps). */
    fun reconnectIfConnected() {
        if (vpnState.value is VpnState.Connected) TitanVpnService.start(getApplication<Application>())
    }

    private fun launchBusy(silent: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.w("Titan", "request failed", e)
                // Only our own, human-readable messages reach the user.
                if (!silent) _message.value = (e as? com.titanvps.app.data.SubscriptionRepository.SubscriptionException)?.message
                    ?: "Что-то пошло не так. Попробуйте ещё раз"
            } finally {
                _busy.value = false
            }
        }
    }
}
