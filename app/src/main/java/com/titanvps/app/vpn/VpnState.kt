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

object VpnStatus {
    private val _state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    internal fun set(value: VpnState) {
        _state.value = value
    }
}
