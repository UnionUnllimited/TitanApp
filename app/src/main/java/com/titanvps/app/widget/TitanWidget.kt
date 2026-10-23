package com.titanvps.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.RemoteViews
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.data.Flags
import com.titanvps.app.data.ServerOrder
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus

/**
 * Home-screen widget: the server's flag, status and server, ‹ › for the previous / next
 * server and a Вкл/Выкл button. The provider is exported (Android requires it) and only
 * draws; the buttons go to the non-exported [WidgetActionReceiver], so no other app can
 * switch the VPN through it.
 */
class TitanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, VpnStatus.state.value)
    }

    // Resized: ‹ › appear or go (see ARROWS_MIN_WIDTH_DP).
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?) {
        update(context, VpnStatus.state.value)
    }

    companion object {
        internal const val ACTION_TOGGLE = "com.titanvps.app.widget.TOGGLE"
        internal const val ACTION_PREV = "com.titanvps.app.widget.PREV"
        internal const val ACTION_NEXT = "com.titanvps.app.widget.NEXT"
        private const val FLAG_PX = 96
        private val CONNECTED_COLOR = 0xFF22C55E.toInt()
        /** Narrower than this (a widget placed at 3 cells before ‹ › came) the text needs the room. */
        private const val ARROWS_MIN_WIDTH_DP = 260

        /** Redraws every widget for [state]; called on each VPN state change. */
        fun update(context: Context, state: VpnState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TitanWidget::class.java))
            if (ids.isEmpty()) return
            val app = TitanApp.get(context)
            val server = app.repository.selectedServer()

            // The server's round flag; the logo without a server (or a flag) yet.
            val flag = server?.let { s ->
                val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
                runCatching {
                    if (night) Flags.circleBitmap(context, s.name, FLAG_PX, 0x1AFFFFFF, 0x26FFFFFF)
                    else Flags.circleBitmap(context, s.name, FLAG_PX, 0x14000000, 0x1F000000)
                }.getOrNull()
            }
            // ‹ › only when the server's group has another one.
            val all = app.repository.subscription.value?.servers.orEmpty()
            val wifiLocked = !app.network.current() && !app.settings.techMode.value
            val steps = server != null && ServerOrder.neighbor(all, server.id, true, app.settings.favorites.value, wifiLocked) != null

            val wide by lazy { views(context, state, server?.name, flag, arrows = steps) }
            val narrow by lazy { views(context, state, server?.name, flag, arrows = false) }
            for (id in ids) {
                // Portrait width in dp; 0 when the launcher doesn't tell.
                val width = runCatching { manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) }
                    .getOrDefault(0)
                manager.updateAppWidget(id, if (width in 1 until ARROWS_MIN_WIDTH_DP) narrow else wide)
            }
        }

        private fun views(context: Context, state: VpnState, serverName: String?, flag: Bitmap?, arrows: Boolean): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_titan)
            val (status, button) = when (state) {
                is VpnState.Connected -> "Подключено" to R.drawable.widget_btn_on
                VpnState.Connecting -> "Подключаемся…" to R.drawable.widget_btn_busy
                VpnState.Disconnecting -> "Отключаемся…" to R.drawable.widget_btn_busy
                else -> "Не подключено" to R.drawable.widget_btn_off
            }
            val connected = state is VpnState.Connected
            if (Build.VERSION.SDK_INT >= 31) {
                views.setTextViewText(R.id.widget_status, status)
                // Resolved by the launcher, so it follows a later switch of the system theme.
                if (connected) views.setTextColor(R.id.widget_status, CONNECTED_COLOR)
                else views.setColor(R.id.widget_status, "setTextColor", R.color.widget_text)
            } else {
                // No colour from here (it would keep this theme's): the layout's @color/widget_text,
                // which the launcher resolves, and a green «Подключено» as a span of the text.
                views.setTextViewText(R.id.widget_status, if (connected) green(status) else status)
            }
            views.setTextViewText(R.id.widget_server, serverName?.let { Flags.nameWithoutFlag(it) } ?: "Titan VPS")
            views.setInt(R.id.widget_button, "setBackgroundResource", button)

            if (flag != null) views.setImageViewBitmap(R.id.widget_flag, flag)
            else views.setImageViewResource(R.id.widget_flag, R.drawable.logo)

            val visibility = if (arrows) View.VISIBLE else View.GONE
            views.setViewVisibility(R.id.widget_prev, visibility)
            views.setViewVisibility(R.id.widget_next, visibility)

            val open = PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_button, action(context, 0, ACTION_TOGGLE))
            views.setOnClickPendingIntent(R.id.widget_prev, action(context, 2, ACTION_PREV))
            views.setOnClickPendingIntent(R.id.widget_next, action(context, 3, ACTION_NEXT))
            views.setOnClickPendingIntent(R.id.widget_root, open)
            return views
        }

        private fun green(text: String): CharSequence = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(CONNECTED_COLOR), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
        }

        private fun action(context: Context, code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
            context, code,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
