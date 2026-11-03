package com.titanvps.app.vpn

import android.app.Notification
import android.app.NotificationChannel
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
 * The notification while the VPN is off (iOS: the Live Activity that stays, «Выключено»): the
 * picked server with its flag, «Выключено» and ‹ Включить ›. A channel of its own («VPN
 * выключен»): one a user silences or turns off here leaves the VPN's own as it is, and an app
 * can't raise a channel again. ‹ › move the pick (WidgetActionReceiver, as the widget's),
 * «Включить» turns the VPN on there. With «Уведомление с сервером» on, a key and notifications
 * allowed.
 *
 * Never next to the service's own: it goes once a start is asked for ([starting]) or that one
 * is up ([serviceShown]), and comes a moment after the VPN went off with the service's gone
 * ([serviceGone]). Each post has its own time, and it is posted again ([refresh]) when the
 * screen comes on, the phone is unlocked and the app is opened or left: always the newest, on
 * top of the shade like Happ (not a foreground service, it can't be colorized). Swiped away
 * (Android 14+ lets users): it stays away, also over a restart of the process, until the app
 * is opened ([opened]) or the VPN changes. TitanApp follows the VPN, the pick and the setting
 * with [update].
 */
internal object OffNotification {

    /** «Включить», to [WidgetActionReceiver]. */
    const val ACTION_ON = "com.titanvps.app.notification.ON"
    /** Swiped away (its delete intent), to [WidgetActionReceiver]. */
    const val ACTION_DISMISSED = "com.titanvps.app.notification.OFF_DISMISSED"

    /** 1 the VPN's, 2 the mobile network's alerts, 3 an update, 4 the way back, 10 / 11 reminders. */
    private const val ID = 5
    private const val CHANNEL_ID = "vpn_off"
    /** Off this long before it is shown. */
    private const val SETTLE_MS = 500L
    /** A start asked for with the VPN off ([starting]): «Выключено» waits this long for it. */
    private const val START_GRACE_MS = 5_000L
    /** In KeepAlive's runtime flags: swiped away, until the app is opened or the VPN changes. */
    private const val KEY_DISMISSED = "off_notification_dismissed"
    private const val REQUEST_OPEN = 20
    private const val REQUEST_ON = 21
    private const val REQUEST_PREV = 22
    private const val REQUEST_NEXT = 23
    private const val REQUEST_DISMISSED = 24

    private val scope = MainScope()
    private var pending: Job? = null
    /** When a start was asked for with the VPN off (elapsedRealtime); 0: none, or it is over. */
    private var startAskedAt = 0L
    /** Shown by this process (and not swiped away since). */
    private var shown = false
    /** The service's own notification is up. */
    private var serviceUp = false
    /** Its channel is set up in this process. */
    @Volatile private var channelReady = false
    /**
     * «Включить» found no VPN permission (WidgetActionReceiver): it opens the launcher shortcut's
     * screen now, which asks for it (a broadcast can't open a screen from a notification).
     * Until the VPN comes on.
     */
    @Volatile var needsConsent = false

    /**
     * Follows the VPN, the pick, the setting, the key and notifications being allowed: shown a
     * moment after the VPN goes off (at once while it is shown: another pick; [atOnce]: nothing
     * is stopping, after a reboot), gone at once while it is on or on its way.
     */
    @Synchronized
    fun update(context: Context, atOnce: Boolean = false) {
        runCatching { plan(TitanApp.get(context), atOnce) }.onFailure { Log.w("Titan", "off notification", it) }
    }

    /** The app is opened: one swiped away comes back. */
    @Synchronized
    fun opened(context: Context) {
        runCatching {
            val app = TitanApp.get(context)
            setDismissed(app, false)
            plan(app, atOnce = false)
        }.onFailure { Log.w("Titan", "off notification", it) }
    }

    /**
     * The screen came on, the phone was unlocked, the app was opened or left: shown, it goes on
     * top again with a fresh time.
     */
    @Synchronized
    fun refresh(context: Context) {
        runCatching { if (shown && pending?.isActive != true) show(TitanApp.get(context)) }
            .onFailure { Log.w("Titan", "off notification", it) }
    }

    /** Swiped away. */
    @Synchronized
    fun dismissed(context: Context) {
        runCatching {
            pending?.cancel()
            pending = null
            shown = false
            // Only the one saying «Выключено»: the VPN on since changes things anyway.
            if (isOff(VpnStatus.state.value)) setDismissed(context, true)
        }.onFailure { Log.w("Titan", "off notification", it) }
    }

    /**
     * A start is asked for (TitanVpnService.start, revive): with the VPN off «Выключено» goes now,
     * and comes back only if the start doesn't get under way.
     */
    @Synchronized
    fun starting(context: Context) {
        runCatching {
            if (!isOff(VpnStatus.state.value)) return@runCatching
            val app = TitanApp.get(context)
            startAskedAt = SystemClock.elapsedRealtime()
            setDismissed(app, false)
            cancel(app)
            plan(app, atOnce = false)
        }.onFailure { Log.w("Titan", "off notification", it) }
    }

    /** The service's notification is up (showStatus): this one goes. */
    @Synchronized
    fun serviceShown(context: Context) {
        runCatching {
            serviceUp = true
            pending?.cancel()
            pending = null
            cancel(TitanApp.get(context))
        }.onFailure { Log.w("Titan", "off notification", it) }
    }

    /** The service's notification has gone (off, or nothing came on): the start asked for is over. */
    @Synchronized
    fun serviceGone(context: Context) {
        runCatching {
            serviceUp = false
            startAskedAt = 0L
            plan(TitanApp.get(context), atOnce = false)
        }.onFailure { Log.w("Titan", "off notification", it) }
    }

    private fun plan(app: TitanApp, atOnce: Boolean) {
        pending?.cancel()
        pending = null
        if (!isOff(VpnStatus.state.value)) {
            // On, or on its way: a start asked for got under way; one swiped away may come
            // back after it.
            startAskedAt = 0L
            needsConsent = false
            setDismissed(app, false)
            cancel(app)
            return
        }
        if (serviceUp || !allowed(app)) {
            cancel(app)
            return
        }
        if (atOnce && graceLeft() <= 0) {
            show(app)
            return
        }
        val wait = if (shown) 0L else SETTLE_MS
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

    @Synchronized
    private fun graceLeft(): Long {
        if (startAskedAt == 0L || !isOff(VpnStatus.state.value)) return 0L
        val left = startAskedAt + START_GRACE_MS - SystemClock.elapsedRealtime()
        if (left <= 0) startAskedAt = 0L
        return left
    }

    @Synchronized
    private fun show(app: TitanApp) {
        val server = app.repository.selectedServer()
        if (!isOff(VpnStatus.state.value) || serviceUp || server == null || !allowed(app)) {
            cancel(app)
            return
        }
        ensureChannel(app)
        app.getSystemService(NotificationManager::class.java).notify(ID, build(app, server))
        shown = true
    }

    /** Also one a previous process left. */
    private fun cancel(app: TitanApp) {
        shown = false
        runCatching { app.getSystemService(NotificationManager::class.java).cancel(ID) }
    }

    private fun isOff(state: VpnState) = state is VpnState.Disconnected || state is VpnState.Error

    private fun allowed(app: TitanApp): Boolean =
        app.settings.liveNotification.value && app.repository.subscription.value != null && !isDismissed(app) &&
            NotificationManagerCompat.from(app).areNotificationsEnabled()

    private fun isDismissed(context: Context) = KeepAlive.prefs(context).getBoolean(KEY_DISMISSED, false)

    private fun setDismissed(context: Context, on: Boolean) {
        if (isDismissed(context) != on) KeepAlive.prefs(context).edit().putBoolean(KEY_DISMISSED, on).apply()
    }

    /**
     * Default importance (in the main section, on the lock screen) without sound, vibration or
     * badge, as the VPN's own; once per process.
     */
    private fun ensureChannel(context: Context) {
        if (channelReady) return
        runCatching {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_off_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
            channelReady = true
        }
    }

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
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            // ⏻, not the shield: nothing is protected now.
            .setSmallIcon(R.drawable.ic_power)
            .setContentTitle(Flags.nameWithoutFlag(server.name))
            .setContentText(app.getString(R.string.notification_status_off))
            .setOngoing(true)
            // A redraw never sounds, vibrates or pops up: its time only puts it on top.
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Ahead of others of the same importance, as the VPN's own.
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Now, not shown: always the newest, like Happ.
            .setShowWhen(false)
            .setWhen(System.currentTimeMillis())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .setDeleteIntent(receiver(app, REQUEST_DISMISSED, ACTION_DISMISSED))
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
