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

    val techMode = settings.techMode
    fun setTechMode(enabled: Boolean) = settings.setTechMode(enabled)

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

    /** Servers being measured right now (spinner, or their old value dimmed). */
    private val _measuring = MutableStateFlow<Set<String>>(emptySet())
    val measuring = _measuring.asStateFlow()

    /** The whole list is being measured (the Пинг button spins). */
    private val _pinging = MutableStateFlow(false)
    val pinging = _pinging.asStateFlow()

    /** Account status and devices from the bot (null until loaded or if unavailable). */
    private val _account = MutableStateFlow<com.titanvps.app.data.AccountApi.Info?>(null)
    val account = _account.asStateFlow()
    private val _accountLoading = MutableStateFlow(false)
    val accountLoading = _accountLoading.asStateFlow()

    init {
        if (repo.isStale()) refresh(silent = true)
        loadAccount()
    }

    fun loadAccount() {
        val url = subscription.value?.url ?: return
        if (_accountLoading.value) return
        viewModelScope.launch {
            _accountLoading.value = true
            runCatching { com.titanvps.app.data.AccountApi.fetch(url) }
                .onSuccess { _account.value = it }
                .onFailure { android.util.Log.w("Titan", "account info: $it") }
            _accountLoading.value = false
        }
    }

    // ------------------------------------------------------------ subscription

    /** Fired after a key is added: connect right away (Android then turns other VPNs off). */
    private val _connectRequests = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val connectRequests = _connectRequests.receiveAsFlow()

    fun activate(link: String) = launchBusy {
        val first = repo.subscription.value == null
        repo.activate(link)
        loadAccount()
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
     * Personal cabinet: the site's cabinet inside the app, signed in by the link the
     * subscription brings (profile-web-page-url); without one, the Telegram bot.
     */
    fun openCabinet() = viewModelScope.launch {
        val page = subscription.value?.info?.webPageUrl
        if (page != null) {
            val devices = _account.value?.let { it.devices.size to it.deviceLimit }
            CabinetActivity.open(getApplication<Application>(), page, devices)
        }
        else _openUrl.send(BuildConfig.TELEGRAM_URL)
    }

    fun disconnect() = TitanVpnService.stop(getApplication<Application>())

    fun logout() {
        disconnect()
        repo.logout()
        _pings.value = emptyMap()
        _account.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    /**
     * Pings every server, [firstIds] (the tab on screen, top to bottom) first. Results
     * appear one by one; old values stay until the new ones arrive.
     */
    fun pingAll(firstIds: List<String> = emptyList()) {
        if (_pinging.value) { stopPing(); return }
        val servers = subscription.value?.servers ?: return
        val ids = servers.map { it.id }
        val order = firstIds.filter { it in ids } + ids.filter { it !in firstIds }
        _pinging.value = true
        pingStopped = false
        // A new run measures everything again, also servers stopped by hand before.
        stoppedIds.clear()
        // Spinners right away: getting the ping core ready can take a few seconds.
        _measuring.value = _measuring.value + ids
        val run = ++pingRun
        ping(servers, order) { error ->
            // A stopped run finishing late mustn't end a newer one.
            if (run != pingRun || pingStopped) return@ping
            _pinging.value = false
            if (error != null) _message.value = "Не удалось проверить пинг. Попробуйте позже"
        }
    }

    @Volatile private var pingStopped = false
    private var pingRun = 0

    /** Stops measuring (the ping button pressed again); unmeasured servers keep their old value. */
    fun stopPing() {
        pingStopped = true
        PingClient.cancel(getApplication<Application>())
        _pinging.value = false
        _measuring.value = emptySet()
    }

    /** Servers whose measuring was stopped by hand: a late result for them is dropped. */
    private val stoppedIds = HashSet<String>()

    /**
     * Pings one server; works any time, also while the whole list is being measured. Pressed
     * again while it is being measured: stops it (the old value stays).
     */
    fun pingOne(serverId: String) {
        val servers = subscription.value?.servers ?: return
        if (servers.none { it.id == serverId }) return
        if (serverId in _measuring.value) {
            stoppedIds += serverId
            _measuring.value = _measuring.value - serverId
            return
        }
        stoppedIds -= serverId
        _measuring.value = _measuring.value + serverId
        ping(servers, listOf(serverId)) {}
    }

    private fun ping(servers: List<com.titanvps.app.data.Server>, targets: List<String>, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val answered = HashSet<String>()
            var error: String? = null
            try {
                withTimeoutOrNull(240_000) {
                    PingClient.ping(getApplication<Application>(), servers, targets).collect { event ->
                        when (event) {
                            is PingClient.Event.Started -> if (!pingStopped) _measuring.value = _measuring.value + (event.ids - stoppedIds)
                            is PingClient.Event.Partial -> {
                                answered += event.delays.keys
                                val kept = event.delays.filterKeys { it !in stoppedIds }
                                _pings.value = _pings.value + kept
                                _measuring.value = _measuring.value - event.delays.keys
                            }
                            is PingClient.Event.Done -> error = event.error
                        }
                    }
                }
            } finally {
                // Anything still unanswered counts as a timeout.
                val missing = targets.filter { it !in answered && it !in stoppedIds }
                if (!pingStopped) _pings.value = _pings.value + missing.associateWith { -1L }
                _measuring.value = _measuring.value - targets.toSet()
                error?.let { android.util.Log.w("Titan", "ping failed: $it") }
            }
            onDone(error)
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
