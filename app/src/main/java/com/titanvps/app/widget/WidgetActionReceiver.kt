package com.titanvps.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import com.titanvps.app.MainActivity
import com.titanvps.app.TitanApp
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerOrder
import com.titanvps.app.vpn.OffNotification
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus

/**
 * The widget's buttons, and those of the notification while the VPN is off ([OffNotification]:
 * ‹ › as the widget's, «Включить»). Not exported: only our own PendingIntents reach it.
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TitanWidget.ACTION_TOGGLE -> toggle(context)
            TitanWidget.ACTION_PREV -> step(context, forward = false)
            TitanWidget.ACTION_NEXT -> step(context, forward = true)
            OffNotification.ACTION_ON -> turnOn(context)
        }
    }

    /**
     * «Включить» in the notification while off: on with the server it shows. A bypass one on
     * Wi-Fi is refused as the launcher shortcut does. No VPN permission: a receiver can't open
     * a screen from a notification (Android 12+), so the button opens the one that asks for it.
     */
    private fun turnOn(context: Context) {
        val st = VpnStatus.state.value
        if (st !is VpnState.Disconnected && st !is VpnState.Error) return
        val app = TitanApp.get(context)
        // Logged out since: the notification goes with the key.
        if (app.repository.subscription.value == null) return
        val server = app.repository.selectedServer() ?: return
        if (ServerGroups.isBypass(server) && !app.settings.techMode.value && !app.network.current()) {
            toast(context, "Обходы работают только через мобильный интернет. На Wi-Fi выберите другой сервер в приложении")
            return
        }
        // Asked only now, to turn ours on: with the permission given it takes the VPN over
        // from another app.
        if (VpnService.prepare(context) != null) {
            OffNotification.needsConsent = true
            OffNotification.update(context)
            toast(context, "Нажмите «Включить» ещё раз, чтобы разрешить VPN")
            return
        }
        runCatching { TitanVpnService.start(context) }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
    }

    private fun toggle(context: Context) {
        when (VpnStatus.state.value) {
            is VpnState.Connected, VpnState.Connecting -> TitanVpnService.stop(context)
            VpnState.Disconnecting -> Unit
            else -> {
                val app = TitanApp.get(context)
                // No key yet or no VPN permission: open the app, it asks for both.
                if (app.repository.subscription.value == null || VpnService.prepare(context) != null) {
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    TitanVpnService.start(context)
                }
            }
        }
    }

    /** On: the VPN switches (like ‹ › in its notification). Off: only the pick moves. */
    private fun step(context: Context, forward: Boolean) {
        val st = VpnStatus.state.value
        if (st is VpnState.Connected || st == VpnState.Connecting) {
            TitanVpnService.step(context, forward)
            return
        }
        val app = TitanApp.get(context)
        val all = app.repository.subscription.value?.servers ?: return
        val wifiLocked = !app.network.current() && !app.settings.techMode.value
        val next = ServerOrder.neighbor(all, app.repository.selectedServer()?.id, forward, app.settings.favorites.value, wifiLocked)
            ?: return
        app.repository.select(next.id)
        TitanWidget.update(context, VpnStatus.state.value)
    }
}
