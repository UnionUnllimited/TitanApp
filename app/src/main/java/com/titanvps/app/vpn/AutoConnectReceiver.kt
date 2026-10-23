package com.titanvps.app.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.titanvps.app.TitanApp
import com.titanvps.app.reminders.Reminders

/**
 * The device started or woke up (car head units send their own ACC-on / quick-boot
 * broadcasts instead of a real boot), or the app was updated. Reminders are scheduled
 * again (alarms don't survive either), then the VPN comes on: by "Автоподключение" (boot /
 * wake-up only) or by «Не отключать VPN» when it was on before. Only with a key and the
 * VPN permission already granted.
 */
class AutoConnectReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { Reminders.reschedule(context) }
        val app = TitanApp.get(context)
        if (app.repository.subscription.value == null) return
        if (VpnStatus.state.value is VpnState.Connected || VpnStatus.state.value == VpnState.Connecting) return
        if (VpnService.prepare(context) != null) return
        val boot = intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        val revive = KeepAlive.shouldRevive(app.settings.keepAlive.value, KeepAlive.wanted(context))
        runCatching {
            when {
                revive -> TitanVpnService.revive(context)
                boot && app.settings.autoConnect.value -> TitanVpnService.start(context)
                else -> Unit
            }
        }
    }
}
