package com.titanvps.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.titanvps.app.TitanApp
import com.titanvps.app.core.TcpPing
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TitanApp.get(app).repository

    val subscription = repo.subscription
    val linkFinder = repo.linkFinder
    val selectedId = repo.selectedId
    val vpnState = VpnStatus.state

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    /** serverId → delay ms (-1 = unreachable). */
    private val _pings = MutableStateFlow<Map<String, Long>>(emptyMap())
    val pings = _pings.asStateFlow()

    private val _pinging = MutableStateFlow(false)
    val pinging = _pinging.asStateFlow()

    init {
        if (repo.isStale()) refresh(silent = true)
        pingAll()
    }

    // ------------------------------------------------------------ subscription

    fun activate(link: String) = launchBusy {
        repo.activate(link)
        _message.value = "Подписка подключена"
        pingAll()
    }

    fun refresh(silent: Boolean = false) = launchBusy(silent) {
        repo.refresh()
        pingAll()
    }

    fun select(serverId: String?) {
        if (serverId == repo.selectedId.value) return
        repo.select(serverId)
        // Reconnect on the new server if we're online.
        if (vpnState.value is VpnState.Connected) TitanVpnService.start(getApplication<Application>())
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

    fun pingAll() {
        val servers = subscription.value?.servers ?: return
        if (_pinging.value) return
        viewModelScope.launch {
            _pinging.value = true
            try {
                _pings.value = TcpPing.pingAll(servers)
            } finally {
                _pinging.value = false
            }
        }
    }

    private fun launchBusy(silent: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
            } catch (e: Exception) {
                if (!silent) _message.value = e.message ?: "Ошибка"
            } finally {
                _busy.value = false
            }
        }
    }
}
