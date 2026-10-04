package com.titanvps.app.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VpnState {
    data object Disconnected : VpnState
    data object Connecting : VpnState
    data class Connected(val serverName: String, val since: Long) : VpnState
    data object Disconnecting : VpnState
    data class Error(val message: String) : VpnState
}

/**
 * Local HTTP proxy into the running core (127.0.0.1, random port and password), so our
 * own app — excluded from the tunnel — can still send traffic through the server
 * (app updates from GitHub). Null while the VPN is off.
 */
data class LocalProxy(val port: Int, val user: String, val password: String)

object VpnStatus {
    @Volatile
    var localProxy: LocalProxy? = null
        internal set

    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    internal fun set(value: VpnState) {
        _state.value = value
    }
}
