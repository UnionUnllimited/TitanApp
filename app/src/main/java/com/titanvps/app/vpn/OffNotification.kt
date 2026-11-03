package com.titanvps.app.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerOrder
import com.titanvps.app.widget.TitanWidget
import com.titanvps.app.widget.WidgetActionReceiver
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The VPN notification while the VPN is off (iOS: the Live Activity that stays, «Выключено»):
 * the picked server with its flag, «Выключено» and ‹ Включить ›, in the VPN notification's
 * own channel. ‹ › move the pick (WidgetActionReceiver, as the widget's), «Включить» turns the
 * VPN on there. With «Уведомление с сервером» on, a key and notifications allowed. Shown a
 * moment after the VPN settles off, so it doesn't flash next to the service's own while that
 * stops, nor at a start of the process that brings the VPN back; gone at once when it comes
 * on. Swiped away (Android 14+ lets users): back with the next opening of the app or change
 * of the VPN. TitanApp follows the VPN, the pick and the setting with [update].
 */
internal object OffNotification {

    /** «Включить», to [WidgetActionReceiver]. */
    const val ACTION_ON = "com.titanvps.app.notification.ON"

    /** 1 the VPN's, 2 the mobile network's alerts, 3 an update, 4 the way back, 10 / 11 reminders. */
    private const val ID = 5
    /** Off this long before it is shown. */
    private const val SETTLE_MS = 500L
    /** A start asked for with the VPN off ([starting]): «Выключено» waits this long for it. */
    private const val START_GRACE_MS = 5_000L
    private const val REQUEST_OPEN = 20
    private const val REQUEST_ON = 21
    private const val REQUEST_PREV = 22
    private const val REQUEST_NEXT = 23

    private val scope = MainScope()
    private var pending: Job? = null
    /** When a start was asked for with the VPN off (elapsedRealtime); 0: none, or it got under way. */
    private var startAskedAt = 0L
    /** Shown since (its time, steady so a redraw doesn't move it); 0: not shown by this process. */
    private var shownSince = 0L
    /**
     * «Включить» found no VPN permission (WidgetActionReceiver): it opens the launcher shortcut's
     * screen now, which asks for it (a broadcast can't open a screen from a notification).
     * Until the VPN comes on.
     */
    @Volatile var needsConsent = false

    /**
     * Follows the VPN, the pick, the setting, the key and notifications being allowed: shown a
     * moment after the VPN goes off (at once while it is shown: another pick), gone at once
     * while it is on or on its way.
     */
    @Synchronized
    fun update(context: Context) {
        runCatching { plan(TitanApp.get(context)) }.onFailure { Log.w("Titan", "off notification", it) }
    }

    private fun plan(app: TitanApp) {
        pending?.cancel()
        pending = null
        val state = VpnStatus.state.value
        if (!isOff(state)) {
            // On, or on its way: a start asked for got under way.
            startAskedAt = 0L
            needsConsent = false
            cancel(app)
            return
        }
        // An error ends the start asked for.
        if (state is VpnState.Error) startAskedAt = 0L
        if (!allowed(app)) {
            cancel(app)
            return
        }
        val wait = if (shownSince != 0L) 0L else SETTLE_MS
        pending = scope.launch {
            delay(wait)
            // A start asked for meanwhile: «Выключено» waits for it to get under way.
            while (true) {
                val left = graceLeft()
                if (left <= 0) break
                delay(left)
            }
            runCatching { show(app) }.onFailure { Log.w("Titan", "off notification", it) }
        }
    }

    /** A start is asked for (TitanVpnService.start, revive): «Выключено» waits for it. */
    @Synchronized
    fun starting() {
        if (isOff(VpnStatus.state.value)) startAskedAt = SystemClock.elapsedRealtime()
    }

    @Synchronized
    private fun graceLeft(): Long {
        if (startAskedAt == 0L || VpnStatus.state.value != VpnState.Disconnected) return 0L
        val left = startAskedAt + START_GRACE_MS - SystemClock.elapsedRealtime()
        if (left <= 0) startAskedAt = 0L
        return left
    }

    @Synchronized
    private fun show(app: TitanApp) {
        val server = app.repository.selectedServer()
        if (!isOff(VpnStatus.state.value) || server == null || !allowed(app)) {
            cancel(app)
            return
        }
        TitanVpnService.ensureChannel(app)
        if (shownSince == 0L) shownSince = System.currentTimeMillis()
        app.getSystemService(NotificationManager::class.java).notify(ID, build(app, server))
    }

    /** Also one a previous process left. */
    private fun cancel(app: TitanApp) {
        shownSince = 0L
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(ID) }
    }

    private fun isOff(state: VpnState) = state is VpnState.Disconnected || state is VpnState.Error

    private fun allowed(app: TitanApp): Boolean =
        app.settings.liveNotification.value && app.repository.subscription.value != null &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()

    private fun build(app: TitanApp, server: Server): Notification {
        val all = app.repository.subscription.value?.servers.orEmpty()
        val wifiLocked = !app.network.current() && !app.settings.techMode.value
        // ‹ › only when the server's group has another one.
        val steps = ServerOrder.neighbor(all, server.id, true, app.settings.favorites.value, wifiLocked) != null
        val open = PendingIntent.getActivity(
            app, REQUEST_OPEN, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        // Not VpnService.prepare to see whether the permission is there: with it given, that
        // takes the VPN over from another app that runs one now.
        val on = if (needsConsent) {
            PendingIntent.getActivity(
                app, REQUEST_ON,
                Intent(app, ShortcutActivity::class.java).setAction(ShortcutActivity.ACTION_CONNECT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            receiver(app, REQUEST_ON, ACTION_ON)
        }
        val builder = NotificationCompat.Builder(app, TitanVpnService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(Flags.nameWithoutFlag(server.name))
            .setContentText(app.getString(R.string.notification_status_off))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
            // When it went off, not now: a redraw doesn't reorder the shade.
            .setWhen(shownSince)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        TitanVpnService.flagBitmap(app, server.name, onBrand = false)?.let { builder.setLargeIcon(it) }
        if (steps) builder.addAction(0, app.getString(R.string.action_prev), receiver(app, REQUEST_PREV, TitanWidget.ACTION_PREV))
        builder.addAction(0, app.getString(R.string.action_connect), on)
        if (steps) builder.addAction(0, app.getString(R.string.action_next), receiver(app, REQUEST_NEXT, TitanWidget.ACTION_NEXT))
        return builder.build()
    }

    private fun receiver(context: Context, code: Int, action: String): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, WidgetActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
