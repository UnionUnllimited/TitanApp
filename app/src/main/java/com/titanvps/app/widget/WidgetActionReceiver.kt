package com.titanvps.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.titanvps.app.MainActivity
import com.titanvps.app.TitanApp
import com.titanvps.app.data.ServerOrder
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus

/** The widget's buttons (not exported: only the widget's own PendingIntents reach it). */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TitanWidget.ACTION_TOGGLE -> toggle(context)
            TitanWidget.ACTION_PREV -> step(context, forward = false)
            TitanWidget.ACTION_NEXT -> step(context, forward = true)
        }
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
