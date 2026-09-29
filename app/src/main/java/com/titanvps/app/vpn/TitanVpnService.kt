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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TitanVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
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
            val server = repo.selectedServer() ?: error("В подписке нет серверов")

            val pfd = Builder()
                .setSession(getString(R.string.app_name))
                .setMtu(MTU)
                .addAddress("172.19.0.1", 30)
                .addRoute("0.0.0.0", 0)
                .addAddress("fdfe:dcba:9876::1", 126)
                .addRoute("::", 0)
                // Any address inside the tunnel works: port 53 is hijacked to Xray DNS.
                .addDnsServer("1.1.1.1")
                // Our own sockets (Xray, subscription fetch) bypass the tunnel → no loops.
                .addDisallowedApplication(packageName)
                .apply {
                    // Apps the user excluded from the VPN.
                    TitanApp.get(this@TitanVpnService).settings.excludedApps.value.forEach { pkg ->
                        runCatching { addDisallowedApplication(pkg) }
                    }
                }
                .setBlocking(false)
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish() ?: error("Нет разрешения на VPN")
            tun = pfd

            val config = XrayConfigs.buildRunConfig(server.xrayJson, pfd.fd, TitanApp.get(this).assetDir, MTU)
            XrayCore.start(config) { fd -> protect(fd) }

            VpnStatus.set(VpnState.Connected(server.name, System.currentTimeMillis()))
            startForegroundCompat(getString(R.string.notification_connected, server.name))
        } catch (e: Exception) {
            stopVpn()
            VpnStatus.set(VpnState.Error(e.message ?: "Ошибка подключения"))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopVpn() {
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
