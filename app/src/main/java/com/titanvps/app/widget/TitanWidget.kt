package com.titanvps.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus

/** Home-screen widget: status, current server and a Вкл/Выкл button. */
class TitanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, VpnStatus.state.value)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
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

    companion object {
        private const val ACTION_TOGGLE = "com.titanvps.app.widget.TOGGLE"

        /** Redraws every widget for [state]; called on each VPN state change. */
        fun update(context: Context, state: VpnState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TitanWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_titan)
            val server = TitanApp.get(context).repository.selectedServer()?.name?.trim()
            val (status, button) = when (state) {
                is VpnState.Connected -> "Подключено" to R.drawable.widget_btn_on
                VpnState.Connecting -> "Подключаемся…" to R.drawable.widget_btn_busy
                VpnState.Disconnecting -> "Отключаемся…" to R.drawable.widget_btn_busy
                else -> "Не подключено" to R.drawable.widget_btn_off
            }
            views.setTextViewText(R.id.widget_status, status)
            views.setTextColor(R.id.widget_status, if (state is VpnState.Connected) 0xFF22C55E.toInt() else 0xFFE6EAF2.toInt())
            views.setTextViewText(R.id.widget_server, server ?: "Titan VPS")
            views.setInt(R.id.widget_button, "setBackgroundResource", button)

            val toggle = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, TitanWidget::class.java).setAction(ACTION_TOGGLE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_button, toggle)
            views.setOnClickPendingIntent(R.id.widget_root, open)
            manager.updateAppWidget(ids, views)
        }
    }
}
