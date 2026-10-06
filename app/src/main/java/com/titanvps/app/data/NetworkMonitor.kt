package com.titanvps.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone is on mobile data. Our app is excluded from its own VPN, so the
 * default network seen here is always the physical one (Wi-Fi / cellular).
 * Bypass ("Обходы") servers are meant for mobile internet only.
 */
class NetworkMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _onMobile = MutableStateFlow(current())
    val onMobile: StateFlow<Boolean> = _onMobile.asStateFlow()

    init {
        runCatching {
            cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _onMobile.value = isMobileLike(caps)
                }

                override fun onLost(network: Network) {
                    _onMobile.value = current()
                }
            })
        }
    }

    fun current(): Boolean {
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return true
        return isMobileLike(caps)
    }

    companion object {
        /**
         * Bypass servers are blocked only on ordinary (unmetered) Wi-Fi. Everything else
         * counts as mobile internet: SIM, a car head unit's USB/LTE modem (often shown as
         * Ethernet), or a phone hotspot (Wi-Fi that Android marks as metered).
         */
        fun isMobileLike(caps: NetworkCapabilities): Boolean =
            !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))

        const val BYPASS_WIFI_MESSAGE =
            "Вы подключены к Wi-Fi. Обходы работают только через мобильный интернет — на Wi-Fi используйте «Серверы»."
    }
}
