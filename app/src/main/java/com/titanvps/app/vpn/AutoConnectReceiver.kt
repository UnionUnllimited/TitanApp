package com.titanvps.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.titanvps.app.TitanApp

/**
 * "Автоподключение": turns the VPN on once when the device starts or wakes up (car head
 * units send their own ACC-on / quick-boot broadcasts instead of a real boot). Only if
 * the setting is on, a key is added and the VPN permission was already granted.
 */
class AutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = TitanApp.get(context)
        if (!app.settings.autoConnect.value) return
        if (app.repository.subscription.value == null) return
        if (VpnStatus.state.value is VpnState.Connected || VpnStatus.state.value == VpnState.Connecting) return
        if (VpnService.prepare(context) != null) return
        runCatching { TitanVpnService.start(context) }
    }
}
