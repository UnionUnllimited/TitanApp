package com.titanvps.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.core.XrayCore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TitanVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null
    private var watcherJob: Job? = null
    private val watcher by lazy { WhitelistWatcher(this) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SWITCH_BYPASS -> {
                scope.launch { lock.withLock { switchToBypass(auto = false) } }
                return START_STICKY
            }
            ACTION_STOP -> {
                scope.launch { lock.withLock { stopVpn() }; stopSelf() }
                return START_NOT_STICKY
            }
            // ACTION_START, or null/"android.net.VpnService" when started by Always-on VPN.
            else -> {
                startForegroundCompat(getString(R.string.notification_connecting))
                scope.launch { lock.withLock { startVpn() } }
            }
        }
        return START_STICKY
    }

    private suspend fun startVpn() {
        if (tun != null) stopVpn()
        VpnStatus.set(VpnState.Connecting)
        val repo = TitanApp.get(this).repository
        try {
            if (repo.subscription.value == null) error("Приложение не активировано")
            if (repo.isStale()) runCatching { repo.refresh() }
            var server = repo.selectedServer() ?: error("В подписке нет серверов")
            // Bypass servers only on mobile data (tile / always-on can start us on Wi-Fi).
            val all = repo.subscription.value!!.servers
            if (isBypass(server, all) && !TitanApp.get(this).network.current()) {
                regularServer(all)?.let { server = it; repo.select(it.id); notifyWifiSwitch(it.name) }
            }

            // Resolver for "direct" traffic: the carrier's/router's own DNS (like Happ), since
            // foreign resolvers such as 1.1.1.1 are often blocked on Russian mobile networks.
            val underlyingDns = underlyingDnsServer()

            val pfd = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
                // Port 53 is hijacked to Xray DNS. A private address (not 1.1.1.1) so Chrome
                // doesn't auto-upgrade to its own DoH and bypass the panel's DNS/routing.
                .addDnsServer("172.19.0.2")
                // Our own sockets (Xray, subscription fetch) bypass the tunnel → no loops.
                .addDisallowedApplication(packageName)
                .apply {
                    // User-excluded apps + (by default) Russian banks/marketplaces, which
                    // then don't see a VPN at all.
                    TitanApp.get(this@TitanVpnService).settings.bypassPackages().forEach { pkg ->
                        runCatching { addDisallowedApplication(pkg) }
                    }
                }
                .setBlocking(false)
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish() ?: error("Нет разрешения на VPN")
            tun = pfd

            val logDir = cacheDir.absolutePath
            java.io.File(logDir, XrayConfigs.ACCESS_LOG).writeText("")
            java.io.File(logDir, XrayConfigs.ERROR_LOG).writeText("")
            com.titanvps.app.core.GeoFiles.prepare(this, java.io.File(TitanApp.get(this).assetDir), all.map { it.xrayJson })
            val localProxy = LocalProxy(
                port = java.net.ServerSocket(0).use { it.localPort },
                user = java.util.UUID.randomUUID().toString(),
                password = java.util.UUID.randomUUID().toString(),
            )
            val config = XrayConfigs.buildRunConfig(server.xrayJson, pfd.fd, TitanApp.get(this).assetDir, MTU, logDir, server.proxyTag, localProxy)
            XrayCore.start(config, underlyingDns) { fd -> protect(fd) }
            VpnStatus.localProxy = localProxy

            VpnStatus.set(VpnState.Connected(server.name, System.currentTimeMillis()))
            startForegroundCompat(getString(R.string.notification_connected, server.name))
            startWatcher(server)
            watchWifi(server, all)
        } catch (e: Exception) {
            stopVpn()
            android.util.Log.w("Titan", "connect failed", e)
            // Our own messages (no subscription, no permission…) as is; core errors stay in the logs.
            val friendly = e is IllegalStateException || e is com.titanvps.app.data.SubscriptionRepository.SubscriptionException
            VpnStatus.set(VpnState.Error(
                if (friendly && e.message != null) e.message!! else "Не удалось подключиться. Попробуйте другой сервер"
            ))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private var wifiJob: Job? = null

    private fun isBypass(server: com.titanvps.app.data.Server, all: List<com.titanvps.app.data.Server>) =
        com.titanvps.app.data.ServerGroups.groupOf(server, all) == com.titanvps.app.data.ServerGroups.Group.BYPASS

    /** A regular server to fall back to: its АВТО entry if there is one. */
    private fun regularServer(all: List<com.titanvps.app.data.Server>): com.titanvps.app.data.Server? {
        val regular = com.titanvps.app.data.ServerGroups.split(all).toMap()[com.titanvps.app.data.ServerGroups.Group.SERVERS].orEmpty()
        return regular.firstOrNull { "авто" in it.name.lowercase() } ?: regular.firstOrNull()
    }

    /** Connected via a bypass server and the phone moved to Wi-Fi: move to a regular server. */
    private fun watchWifi(server: com.titanvps.app.data.Server, all: List<com.titanvps.app.data.Server>) {
        wifiJob?.cancel()
        if (!isBypass(server, all)) return
        wifiJob = scope.launch {
            TitanApp.get(this@TitanVpnService).network.onMobile.first { !it }
            val target = regularServer(all) ?: return@launch
            // Separate job: restarting the VPN cancels this one.
            scope.launch {
                lock.withLock {
                    TitanApp.get(this@TitanVpnService).repository.select(target.id)
                    startVpn()
                    notifyWifiSwitch(target.name)
                }
            }
        }
    }

    private fun notifyWifiSwitch(to: String) {
        if (!TitanApp.get(this).settings.notifications.value) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Ограничения мобильной сети", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            ALERT_ID,
            NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Обходы недоступны на Wi-Fi")
                .setContentText("Обходы работают только через мобильный интернет. Переключили на «$to».")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Обходы работают только через мобильный интернет. Переключили на «$to»."))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    /** First IPv4 DNS of the physical network (before our VPN is up), else Yandex DNS. */
    private fun underlyingDnsServer(): String {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val servers = cm?.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty()
        val v4 = servers.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
        return (v4?.hostAddress ?: "77.88.8.8") + ":53"
    }

    /** Watches for mobile whitelist mode while on a regular server. */
    private fun startWatcher(server: com.titanvps.app.data.Server) {
        watcherJob?.cancel()
        val all = TitanApp.get(this).repository.subscription.value?.servers ?: return
        watcherJob = scope.launch {
            if (!watcher.awaitWhitelist(server, all)) return@launch
            if (TitanApp.get(this@TitanVpnService).settings.autoBypass.value) {
                // Separate job: restarting the VPN cancels this watcher.
                scope.launch { lock.withLock { switchToBypass(auto = true) } }
            } else {
                notifyWhitelist(null)
            }
        }
    }

    private suspend fun switchToBypass(auto: Boolean) {
        val repo = TitanApp.get(this).repository
        val all = repo.subscription.value?.servers ?: return
        val best = watcher.bestBypass(all) ?: return
        repo.select(best.id)
        startForegroundCompat(getString(R.string.notification_connecting))
        startVpn()
        if (auto) notifyWhitelist(best.name)
        else getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    /** [switchedTo] = null: offer a switch; otherwise tell which bypass server we moved to. */
    private fun notifyWhitelist(switchedTo: String?) {
        if (!TitanApp.get(this).settings.notifications.value) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Ограничения мобильной сети", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Мобильный интернет ограничен")
            .setContentIntent(open)
            .setAutoCancel(true)
        if (switchedTo == null) {
            val switch = PendingIntent.getService(
                this, 3, Intent(this, TitanVpnService::class.java).setAction(ACTION_SWITCH_BYPASS), PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentText("Похоже, включены белые списки. Переключитесь на «Обходы».")
                .addAction(0, "Переключить на обход", switch)
        } else {
            builder.setContentText("Переключились на «$switchedTo»")
        }
        nm.notify(ALERT_ID, builder.build())
    }

    private fun stopVpn() {
        wifiJob?.cancel()
        wifiJob = null
        watcherJob?.cancel()
        watcherJob = null
        VpnStatus.localProxy = null
        if (tun != null) VpnStatus.set(VpnState.Disconnecting)
        XrayCore.stop()
        runCatching { tun?.close() }
        tun = null
        VpnStatus.set(VpnState.Disconnected)
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission.
        scope.launch { lock.withLock { stopVpn() }; stopSelf() }
    }

    override fun onDestroy() {
        if (tun != null) stopVpn()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TitanVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_disconnect), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val ACTION_START = "com.titanvps.app.START"
        private const val ACTION_STOP = "com.titanvps.app.STOP"
        private const val ACTION_SWITCH_BYPASS = "com.titanvps.app.SWITCH_BYPASS"
        private const val ALERT_CHANNEL_ID = "whitelist"
        private const val ALERT_ID = 2
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TitanVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TitanVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
