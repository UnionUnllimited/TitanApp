package com.titanvps.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import com.titanvps.app.data.AppSettings
import com.titanvps.app.data.NetworkMonitor
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.SubscriptionRepository
import com.titanvps.app.reminders.Reminders
import com.titanvps.app.vpn.OffNotification
import com.titanvps.app.vpn.ShortcutActivity
import com.titanvps.app.vpn.TitanVpnService
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import com.titanvps.app.vpn.VpnTileService
import com.titanvps.app.widget.TitanWidget
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File

class TitanApp : Application() {

    lateinit var repository: SubscriptionRepository
        private set

    lateinit var settings: AppSettings
        private set

    lateinit var network: NetworkMonitor
        private set

    /** Directory with geoip.dat / geosite.dat for Xray routing rules. */
    val assetDir: String get() = File(filesDir, "xray").absolutePath

    /** Screens of ours started now (ShortcutActivity aside): the app is in the foreground. */
    private var started = 0
    /** Activities being recreated for a configuration change: not a return from the background. */
    private var recreating = 0

    /** One of our screens is on. */
    val isForeground: Boolean get() = started > 0

    override fun onCreate() {
        super.onCreate()
        repository = SubscriptionRepository(this)
        settings = AppSettings(this)
        network = NetworkMonitor(this)
        cleanOldGeoCopies()
        // Main process only: the ":ping" process has its own (always "off") VpnStatus.
        if (isMainProcess()) {
            registerActivityLifecycleCallbacks(foregroundWatcher)
            val scope = MainScope()
            // Home-screen widget, the Quick Settings tile and the notification while off follow
            // the VPN state, the selected server and the favourites (the order of ‹ ›); the
            // notification also «Уведомление с сервером» and the key.
            scope.launch {
                combine(
                    VpnStatus.state, repository.selectedId, settings.favorites, settings.liveNotification, repository.subscription,
                ) { state, _, _, _, _ -> state }
                    .collect {
                        runCatching { TitanWidget.update(this@TitanApp, it) }
                        runCatching {
                            TileService.requestListeningState(this@TitanApp, ComponentName(this@TitanApp, VpnTileService::class.java))
                        }
                        runCatching { OffNotification.update(this@TitanApp) }
                    }
            }
            // Напоминания: scheduled again on every start (alarms don't survive a force-stop),
            // subscription update and switch of the setting (also by «Сброс настроек»).
            scope.launch {
                combine(repository.subscription, settings.reminders) { sub, _ -> sub }
                    .collect { runCatching { Reminders.update(this@TitanApp, it) } }
            }
            // «Уведомление с сервером» switched (or reset): the VPN notification redrawn.
            scope.launch {
                settings.liveNotification.drop(1).collect { TitanVpnService.reshow(this@TitanApp) }
            }
            // The shade is about to be seen: «Выключено» on top again (while this process lives).
            val screen = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching { ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED) }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            OffNotification.refresh(context)
        }
    }

    private val foregroundWatcher = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            // The shortcut screen is invisible and closes at once: not an opening of the app.
            if (activity is ShortcutActivity) return
            if (recreating > 0) { recreating--; return }
            if (started++ == 0) {
                autoConnectOnOpen()
                // Swiped away: back with each opening of the app.
                OffNotification.opened(this@TitanApp)
                // Notifications allowed in Android's settings meanwhile: the VPN's numbers come back now
                // (the notification checks whether it is seen only on a change and when the screen comes on).
                if (VpnStatus.state.value is VpnState.Connected) TitanVpnService.reshow(this@TitanApp)
            }
        }

        override fun onActivityStopped(activity: Activity) {
            if (activity is ShortcutActivity) return
            if (activity.isChangingConfigurations) { recreating++; return }
            started = (started - 1).coerceAtLeast(0)
            // Left: «Выключено» on top again, above what came while in the app.
            if (started == 0) OffNotification.refresh(this@TitanApp)
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /**
     * Автоподключение: on every opening of the app — the first one and each return from the
     * background (not from our own screens such as Личный кабинет), like iOS. Only while off,
     * with a key and the VPN permission, and not onto a bypass server on Wi-Fi.
     */
    private fun autoConnectOnOpen() {
        if (!settings.autoConnect.value || repository.subscription.value == null) return
        val st = VpnStatus.state.value
        if (st != VpnState.Disconnected && st !is VpnState.Error) return
        if (VpnService.prepare(this) != null) return
        val server = repository.selectedServer() ?: return
        if (ServerGroups.isBypass(server) && !settings.techMode.value && !network.current()) return
        runCatching { TitanVpnService.start(this) }
    }

    private fun isMainProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().trim('\u0000').trim() == packageName
    }.getOrDefault(true)

    /** Removes full-size copies left by older versions (now trimmed in GeoFiles). */
    private fun cleanOldGeoCopies() {
        File(assetDir, ".version").takeIf { it.exists() }?.let { marker ->
            marker.delete()
            File(assetDir).listFiles()?.filter { it.name.endsWith(".dat") }?.forEach { it.delete() }
        }
    }

    companion object {
        fun get(context: Context): TitanApp = context.applicationContext as TitanApp
    }
}
