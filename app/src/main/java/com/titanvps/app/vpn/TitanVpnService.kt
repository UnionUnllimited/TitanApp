package com.titanvps.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.core.GeoFiles
import com.titanvps.app.core.PluginProcess
import com.titanvps.app.core.Plugins
import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.core.XrayCore
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import com.titanvps.app.data.ServerOrder
import com.titanvps.app.data.SubscriptionRepository
import com.titanvps.app.ui.kit.ToastKind
import com.titanvps.app.ui.kit.Toasts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class TitanVpnService : VpnService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Connects, switches, restarts and stops one at a time. Never held across a delay. */
    private val lock = Mutex()
    /** Bumped by every stop: a switch or restart queued before it does nothing. */
    private val generation = AtomicInteger()

    /** Our tun. The core reads a copy of it ([coreFd]), so a core restart keeps the interface up. */
    @Volatile private var tun: ParcelFileDescriptor? = null
    /** Apps left out of [tun]; leaving out other apps needs a new tun. */
    private var tunApps: Set<String> = emptySet()
    /** The copy of [tun] the running core reads: a raw fd, owned by nobody on our side; -1 when none. */
    private var coreFd = -1
    /** The same for the life of [tun]. */
    private var localProxy: LocalProxy? = null
    /** The server the core runs; null while the core is stopped. */
    @Volatile private var running: Server? = null
    /** Connected at (the notification's timer); network and plugin restarts keep it. */
    private var since = 0L
    /** When the core was last started (elapsed realtime): a restart asked for before that is moot. */
    @Volatile private var launchedAt = 0L

    private var watcherJob: Job? = null
    private val watcher by lazy { WhitelistWatcher(this) }
    /** On a bypass: waits for the restrictions to end ([watchReturn]). */
    private var returnJob: Job? = null
    /** Our own move to a bypass or back: outside the watchers, as the switch cancels them. */
    @Volatile private var bypassJob: Job? = null
    /** The way back from a bypass was offered on this connection (once per connection). */
    @Volatile private var returnOffered = false
    /** The bypass the «Вернуться на «X»» offer on screen is for; null: none shown. */
    @Volatile private var offerFor: String? = null
    /** How often we switch to a bypass on our own; saved by [BypassReturn]. */
    private val policy by lazy { BypassReturn.policy(this) }

    /** Naive / Mieru client of the current server, if it is one. */
    private var plugin: PluginProcess? = null

    private var wifiJob: Job? = null
    private var updateJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var restartJob: Job? = null
    @Volatile private var stepJob: Job? = null
    /** The server ‹ › stepped to and not connected yet: quick taps step on from it. */
    @Volatile private var stepTarget: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_SWITCH_BYPASS -> {
                KeepAlive.setWanted(this, true)
                if (tun == null) showStatus(Status.CONNECTING, null) else showStatus(status, statusServer, statusSince)
                val gen = generation.get()
                scope.launch {
                    // Measured outside the lock: an off by hand never waits for the pings.
                    val best = TitanApp.get(this@TitanVpnService).repository.subscription.value?.servers
                        ?.let { watcher.bestBypass(it) }
                    lock.withLock {
                        // Turned off while measuring: stays off.
                        if (gen != generation.get() || !KeepAlive.wanted(this@TitanVpnService)) return@withLock
                        when {
                            best != null -> switchToBypass(best, auto = false)
                            // Nothing to switch to and nothing on: no "Подключение…" left behind.
                            tun == null -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                        }
                    }
                }
                return START_STICKY
            }
            // «Вернуться на «X»» from the notification: back from a bypass to that regular server.
            ACTION_RETURN -> {
                // An action tap doesn't dismiss its notification.
                cancelReturnNote()
                // The VPN went off since the offer: the tap is moot, it doesn't turn it on.
                if (tun == null) {
                    val st = VpnStatus.state.value
                    if (st is VpnState.Disconnected || st is VpnState.Error) { stopSelf(startId); return START_NOT_STICKY }
                    return START_STICKY
                }
                showStatus(status, statusServer, statusSince)
                val gen = generation.get()
                val id = intent?.getStringExtra(EXTRA_SERVER)
                val from = intent?.getStringExtra(EXTRA_FROM)
                scope.launch {
                    lock.withLock {
                        if (!stillOn(gen)) return@withLock
                        val all = TitanApp.get(this@TitanVpnService).repository.subscription.value?.servers.orEmpty()
                        // Only from the bypass the offer was for: after another pick the tap is moot.
                        val on = running ?: return@withLock
                        if (!isBypass(on, all) || (from != null && on.id != from)) return@withLock
                        val target = all.firstOrNull { it.id == id && !isBypass(it, all) } ?: regularServer(all)
                        if (target != null) returnToRegular(target, auto = false)
                    }
                }
                return START_STICKY
            }
            // The status notification was swiped away (Android 14+ lets users): back while on.
            ACTION_RESHOW -> {
                val st = VpnStatus.state.value
                if (st !is VpnState.Connected && st != VpnState.Connecting) { stopSelf(startId); return START_NOT_STICKY }
                showStatus(status, statusServer, statusSince)
                return START_STICKY
            }
            ACTION_STOP -> {
                // Off by hand: nothing queued may turn the VPN back on, and keep-alive lets it be.
                KeepAlive.setWanted(this, false)
                cancelQueued()
                forgetReturn()
                warnAlwaysOn()
                scope.launch { lock.withLock { stopVpn() }; stopSelf(startId) }
                return START_NOT_STICKY
            }
            ACTION_PREV, ACTION_NEXT -> {
                onStep(forward = action == ACTION_NEXT)
                val st = VpnStatus.state.value
                if (st is VpnState.Disconnected || st is VpnState.Error) { stopSelf(startId); return START_NOT_STICKY }
                return START_STICKY
            }
            // Android restarted us after the process was killed (START_STICKY).
            null -> {
                if (!KeepAlive.shouldRevive(TitanApp.get(this).settings.keepAlive.value, KeepAlive.wanted(this))) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                begin(revive = true)
            }
            // ACTION_START, or "android.net.VpnService" when started by Always-on VPN.
            else -> {
                KeepAlive.setWanted(this, true)
                begin(revive = intent?.getBooleanExtra(EXTRA_REVIVE, false) == true)
            }
        }
        return START_STICKY
    }

    /** Foreground at once (Android's rule for a started service), then connect or switch. */
    private fun begin(revive: Boolean) {
        // While on, the notification stays as it is: startVpn shows a switch if there is one.
        if (tun != null) showStatus(status, statusServer, statusSince)
        else showStatus(Status.CONNECTING, TitanApp.get(this).repository.selectedServer()?.name)
        stepJob?.cancel()
        stepTarget = null
        scope.launch {
            lock.withLock {
                // Turned off by hand before this got its turn: stays off.
                if (!KeepAlive.wanted(this@TitanVpnService)) return@withLock
                startVpn(revive)
            }
        }
    }

    /**
     * A switch or restart queued under [gen] still applies: no stop since, the tun is up and
     * nobody turned the VPN off by hand. Under [lock].
     */
    private fun stillOn(gen: Int): Boolean =
        gen == generation.get() && tun != null && KeepAlive.wanted(this)

    /** Thrown when retrying can't help: no key, no servers, no VPN permission. */
    private class StartRefused(message: String) : Exception(message)

    /**
     * Connects to the selected server, or switches to it while on: on the same tun when the
     * apps left out are the same, otherwise on a new tun built before the old one closes, so
     * apps never go around the VPN in between. [revive]: Android or keep-alive brings the VPN
     * back; a core that fails then is tried again while the tun holds the traffic.
     * Under [lock].
     */
    private fun startVpn(revive: Boolean) {
        stepTarget = null
        val app = TitanApp.get(this)
        val repo = app.repository
        var server: Server? = null
        try {
            val sub = repo.subscription.value ?: throw StartRefused("Приложение не активировано")
            // Connect on the saved configs right away; the subscription is refreshed in the
            // background once connected, so a down subscription server never blocks connecting.
            val refreshAfter = repo.isStale()
            val all = sub.servers
            val target = pickServer(all) ?: throw StartRefused("В подписке нет серверов")
            server = target
            val apps = app.settings.bypassPackages()
            val current = tun
            if (current == null) {
                VpnStatus.set(VpnState.Connecting)
                showStatus(Status.CONNECTING, target.name)
                tun = establish(apps)
                tunApps = apps
                localProxy = LocalProxy(
                    port = java.net.ServerSocket(0).use { it.localPort },
                    user = java.util.UUID.randomUUID().toString(),
                    password = java.util.UUID.randomUUID().toString(),
                )
                // A revive keeps the logs: Диагностика shows what went wrong before.
                if (!revive) clearLogs()
                watchNetwork()
            } else {
                // Nothing changed (Always-on and Автоподключение both at boot, a reconnect
                // with the same settings): the core goes on.
                if (target == running && apps == tunApps && VpnStatus.state.value is VpnState.Connected) {
                    showStatus(Status.CONNECTED, target.name, since)
                    return
                }
                VpnStatus.set(VpnState.Connecting)
                showStatus(Status.SWITCHING, target.name)
                if (apps == tunApps) {
                    stopCore()
                } else {
                    // Other apps left out: the new tun first (Android moves the routes to it).
                    val fresh = establish(apps)
                    stopCore()
                    tun = fresh
                    tunApps = apps
                    runCatching { current.close() }
                }
                clearLogs()
            }
            // A new connection: a bypass may offer the way back once more.
            returnOffered = false
            GeoFiles.prepare(this, File(app.assetDir), all.map { it.xrayJson })
            launchCore(target, all, null)

            since = System.currentTimeMillis()
            VpnStatus.set(VpnState.Connected(target.name, since))
            showStatus(Status.CONNECTED, target.name, since)
            watchUpdates()
            if (refreshAfter) scope.launch { runCatching { repo.refresh() } }
        } catch (e: Exception) {
            Log.w("Titan", "connect failed", e)
            if (revive && e !is StartRefused && tun != null) {
                // Brought back by Android or keep-alive: the tun stays up, the core is tried again.
                VpnStatus.set(VpnState.Connecting)
                showStatus(Status.RECONNECTING, server?.name)
                restartCore(null, KeepAlive.BACKOFF_SEC.first() * 1000L, firstBackoff = 1)
                return
            }
            // Our own messages (no subscription, no permission…) as is; core errors stay in the logs.
            val friendly = e is StartRefused || e is IllegalStateException || e is SubscriptionRepository.SubscriptionException
            giveUp(if (friendly && e.message != null) e.message!! else "Не удалось подключиться. Попробуйте другой сервер")
        }
    }

    /** The VPN goes off with [message] and keep-alive lets it be. Under [lock]. */
    private fun giveUp(message: String) {
        KeepAlive.setWanted(this, false)
        stopVpn()
        VpnStatus.set(VpnState.Error(message))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** The selected server; a bypass one on Wi-Fi (without Технический режим) gives way to a regular one. */
    private fun pickServer(all: List<Server>): Server? {
        val app = TitanApp.get(this)
        var server = app.repository.selectedServer() ?: return null
        // Bypass servers only on mobile data (tile / always-on can start us on Wi-Fi), any network in Технический режим.
        if (isBypass(server, all) && !app.settings.techMode.value && !app.network.current()) {
            regularServer(all)?.let { server = it; app.repository.select(it.id); notifyWifiSwitch(it.name) }
        }
        return server
    }

    private fun establish(apps: Set<String>): ParcelFileDescriptor {
        val pfd = try {
            Builder()
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
                    apps.forEach { pkg -> runCatching { addDisallowedApplication(pkg) } }
                }
                .setBlocking(false)
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish()
        } catch (e: Exception) {
            Log.w("Titan", "establish failed", e)
            null
        }
        return pfd ?: throw StartRefused("Нет разрешения на VPN")
    }

    /**
     * Starts the core for [server] on a copy of the tun. [network]: the physical network
     * whose DNS the core gets (null: the current one). Under [lock].
     */
    private fun launchCore(server: Server, all: List<Server>, network: Network?) {
        val pfd = tun ?: throw StartRefused("Нет разрешения на VPN")
        launchedAt = SystemClock.elapsedRealtime()
        // Resolver for "direct" traffic: the carrier's/router's own DNS (like Happ), since
        // foreign resolvers such as 1.1.1.1 are often blocked on Russian mobile networks.
        val dns = underlyingDnsServer(network)
        // Go gets its own fd number; detached at once, so Android's fd owner checks never
        // see Go close a descriptor a ParcelFileDescriptor still owns.
        coreFd = pfd.dup().detachFd()
        try {
            // Naive / Mieru: their own client as a local SOCKS, Xray's proxy outbound points
            // at it; MASQUE: Xray's own client.
            val (serverJson, p) = Plugins.prepare(this, server.name, server.xrayJson, server.proxyTag)
            plugin = p
            val config = XrayConfigs.buildRunConfig(
                serverJson, coreFd, TitanApp.get(this).assetDir, MTU, cacheDir.absolutePath, server.proxyTag, localProxy,
            )
            XrayCore.start(config, dns) { fd -> protect(fd) }
        } catch (e: Exception) {
            stopCore()
            throw e
        }
        VpnStatus.localProxy = localProxy
        running = server
        // The way back belongs to the bypass we moved to: any other server (our way back, the
        // user's pick, Wi-Fi's regular one) drops it.
        val saved = BypassReturn.load(this)
        if (saved != null && saved.bypassId != server.id) BypassReturn.clear(this)
        // So does the offer to go back (a restart of the same bypass keeps it).
        val offeredOn = offerFor
        if (offeredOn != null && offeredOn != server.id) cancelReturnNote()
        startWatcher(server)
        watchWifi(server, all)
        watchReturn(server, all)
        watchPlugin()
    }

    /** Stops the core and its plugin; the tun stays. Under [lock] (or in onDestroy). */
    private fun stopCore() {
        wifiJob?.cancel()
        wifiJob = null
        watcherJob?.cancel()
        watcherJob = null
        returnJob?.cancel()
        returnJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        running = null
        XrayCore.stop()
        plugin?.close()
        plugin = null
        releaseCoreFd()
    }

    /**
     * The stopped core's copy of the tun. FD_SWAP: its number is never handed back to the
     * system, so Go closing it later (iOS: when the stopped instance is garbage-collected)
     * can't hit a reused number such as the next tun; it now points at a pipe nobody writes
     * to, which never reads as ready, so an old reader can't spin or take our packets.
     * Libxray v26.9.30 doesn't close it at all: one parked fd per core run.
     */
    private fun releaseCoreFd() {
        val fd = coreFd
        if (fd < 0) return
        coreFd = -1
        if (FD_SWAP && runCatching { Os.dup2(parking[0].fileDescriptor, fd) }.isSuccess) return
        // Without the swap (or if it failed): closed, so the tun never outlives an off.
        runCatching { ParcelFileDescriptor.adoptFd(fd).close() }
    }

    /**
     * Restarts the core on the same tun after a network change, a plugin exit or a failed
     * revive: [firstDelayMs], then on failure after [KeepAlive.BACKOFF_SEC] (from index
     * [firstBackoff]) and every 30 s while keep-alive is on (without it the VPN goes off after
     * the list). The tun stays up meanwhile: traffic waits, no app goes around the VPN. A new
     * call replaces the pending one (debounce). Never holds [lock] while waiting.
     */
    @Synchronized
    private fun restartCore(network: Network?, firstDelayMs: Long, firstBackoff: Int = 0) {
        val gen = generation.get()
        val askedAt = SystemClock.elapsedRealtime()
        restartJob?.cancel()
        restartJob = scope.launch {
            delay(firstDelayMs)
            var failures = firstBackoff
            while (true) {
                val done = lock.withLock {
                    // Stopped, or turned off by hand since.
                    if (!stillOn(gen)) return@launch
                    // A connect or switch since then started the core afresh.
                    if (running != null && launchedAt > askedAt) return@launch
                    try {
                        relaunch(network)
                        true
                    } catch (e: StartRefused) {
                        giveUp(e.message ?: "Не удалось подключиться. Попробуйте другой сервер")
                        return@launch
                    } catch (e: Exception) {
                        Log.w("Titan", "restart failed", e)
                        false
                    }
                }
                if (done) return@launch
                val waitSec = KeepAlive.BACKOFF_SEC.getOrNull(failures++)
                    ?: if (TitanApp.get(this@TitanVpnService).settings.keepAlive.value) 30 else {
                        lock.withLock {
                            if (stillOn(gen)) giveUp("Не удалось подключиться. Попробуйте другой сервер")
                        }
                        return@launch
                    }
                delay(waitSec * 1000L)
            }
        }
    }

    /** One restart of the core ("Переподключаемся…"); the timer goes on. Under [lock]. */
    private fun relaunch(network: Network?) {
        val app = TitanApp.get(this)
        val all = app.repository.subscription.value?.servers ?: throw StartRefused("Приложение не активировано")
        val server = pickServer(all) ?: throw StartRefused("В подписке нет серверов")
        VpnStatus.set(VpnState.Connecting)
        showStatus(Status.RECONNECTING, server.name)
        stopCore()
        GeoFiles.prepare(this, File(app.assetDir), all.map { it.xrayJson })
        launchCore(server, all, network)
        if (since == 0L) since = System.currentTimeMillis()
        VpnStatus.set(VpnState.Connected(server.name, since))
        showStatus(Status.CONNECTED, server.name, since)
        // A revive that failed at first connects here.
        if (updateJob == null) watchUpdates()
    }

    /**
     * Wi-Fi ↔ mobile data, another Wi-Fi, a new IPv4 address or DNS: the core's sockets and
     * DNS belong to the old network, so it restarts after things settle. Our app is left out of
     * the VPN, so the default network seen here is the physical one.
     */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var key: String? = null

            override fun onAvailable(network: Network) {
                if (network == current) return
                // The first one is the network we connected on.
                val first = current == null
                current = network
                key = null
                if (!first) restartCore(network, NETWORK_SETTLE_MS)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (network != current) return
                val next = linkKey(linkProperties)
                val old = key
                key = next
                if (old != null && old != next) restartCore(network, NETWORK_SETTLE_MS)
            }
        }
        runCatching {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }.onFailure { Log.w("Titan", "network callback", it) }
    }

    private fun unwatchNetwork() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    /**
     * IPv4 addresses and DNS only: Xray asks for IPv4 answers, and mobile networks rotate
     * their IPv6 privacy addresses, which mustn't drop every connection.
     */
    private fun linkKey(lp: LinkProperties): String {
        val addresses = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.sorted()
        val dns = lp.dnsServers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.sorted()
        return addresses.joinToString(",") + "|" + dns.joinToString(",")
    }

    /** A Naive / Mieru / sing-box client that exits takes the server with it: restart both. */
    private fun watchPlugin() {
        watchdogJob?.cancel()
        val p = plugin ?: return
        watchdogJob = scope.launch {
            while (true) {
                delay(PLUGIN_CHECK_MS)
                if (!p.isAlive) {
                    Log.w("Titan", "plugin client exited")
                    restartCore(null, 0)
                    return@launch
                }
            }
        }
    }

    /**
     * ‹ › from the notification (like iOS VPNIntents.switchServer): the neighbour of the
     * server asked for last; the VPN reconnects once quick taps pause. Off: only the pick.
     */
    private fun onStep(forward: Boolean) {
        val app = TitanApp.get(this)
        val repo = app.repository
        val all = repo.subscription.value?.servers ?: return
        val from = stepTarget ?: repo.selectedServer()?.id
        val target = ServerOrder.neighbor(all, from, forward, app.settings.favorites.value, wifiLocked()) ?: return
        repo.select(target.id)
        if (tun == null) return
        stepTarget = target.id
        showStatus(Status.SWITCHING, target.name)
        stepJob?.cancel()
        val gen = generation.get()
        stepJob = scope.launch {
            delay(STEP_SETTLE_MS)
            lock.withLock {
                if (!stillOn(gen)) return@withLock
                stepTarget = null
                startVpn(revive = false)
            }
        }
    }

    /** Bypass servers are locked on Wi-Fi without Технический режим. */
    private fun wifiLocked(): Boolean {
        val app = TitanApp.get(this)
        return !app.network.current() && !app.settings.techMode.value
    }

    /** Off by hand: switches and restarts already queued must not turn the VPN back on. */
    private fun cancelQueued() {
        generation.incrementAndGet()
        stepJob?.cancel()
        stepJob = null
        stepTarget = null
        restartJob?.cancel()
        restartJob = null
    }

    /** Android's own Always-on VPN starts us again right after an off by hand. */
    private fun warnAlwaysOn() {
        if (Build.VERSION.SDK_INT < 29 || !isAlwaysOn) return
        val text = "Включён системный «Постоянный VPN» — Android включит VPN снова. Выключите его в настройках VPN"
        // In the app: its own toast; from the tile, widget or notification: the system one.
        if (TitanApp.get(this).isForeground) Toasts.show(text, ToastKind.WARNING)
        else Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    private fun clearLogs() {
        runCatching {
            File(cacheDir, XrayConfigs.ACCESS_LOG).writeText("")
            File(cacheDir, XrayConfigs.ERROR_LOG).writeText("")
        }
    }

    /** While connected: look for a new app version (through the VPN) and notify once per version. */
    private fun watchUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            delay(15_000)
            while (true) {
                runCatching {
                    val release = com.titanvps.app.data.Updater(this@TitanVpnService) {}.findNewer()
                    val prefs = getSharedPreferences("updates", MODE_PRIVATE)
                    if (release != null && prefs.getInt("notified", 0) < release.versionCode) {
                        notifyUpdate(release.versionName)
                        prefs.edit().putInt("notified", release.versionCode).apply()
                    }
                }
                delay(12 * 60 * 60_000L)
            }
        }
    }

    private fun notifyUpdate(version: String) {
        if (!TitanApp.get(this).settings.notifications.value) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(UPDATE_CHANNEL_ID, "Обновления приложения", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            this, 5,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_UPDATE, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        nm.notify(
            UPDATE_ID,
            NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle("Вышла новая версия Titan VPS")
                .setContentText("Версия $version — нажмите, чтобы обновить")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }

    private fun isBypass(server: Server, all: List<Server>) =
        ServerGroups.groupOf(server, all) == ServerGroups.Group.BYPASS

    /** A regular server to fall back to: its АВТО entry if there is one. */
    private fun regularServer(all: List<Server>): Server? {
        val regular = ServerGroups.split(all).toMap()[ServerGroups.Group.SERVERS].orEmpty()
        return regular.firstOrNull { "авто" in it.name.lowercase() } ?: regular.firstOrNull()
    }

    /** Connected via a bypass server and the phone moved to Wi-Fi: move to a regular server. */
    private fun watchWifi(server: Server, all: List<Server>) {
        wifiJob?.cancel()
        if (!isBypass(server, all) || TitanApp.get(this).settings.techMode.value) return
        wifiJob = scope.launch {
            TitanApp.get(this@TitanVpnService).network.onMobile.first { !it }
            val target = regularServer(all) ?: return@launch
            val gen = generation.get()
            // Separate job: restarting the VPN cancels this one.
            scope.launch {
                lock.withLock {
                    if (!stillOn(gen)) return@withLock
                    TitanApp.get(this@TitanVpnService).repository.select(target.id)
                    startVpn(revive = false)
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

    /** First IPv4 DNS of the physical network ([network], else the current one), else Yandex DNS. */
    private fun underlyingDnsServer(network: Network?): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val servers = (network ?: cm?.activeNetwork)?.let { cm?.getLinkProperties(it) }?.dnsServers.orEmpty()
        val v4 = servers.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        return (v4?.hostAddress ?: "77.88.8.8") + ":53"
    }

    /**
     * Watches for mobile whitelist mode while on a regular server. [BypassPolicy] decides
     * whether we switch on our own, only offer it, or look again later (just back from a
     * bypass; our own switches used up this hour, then with the offer meanwhile).
     */
    private fun startWatcher(server: Server) {
        watcherJob?.cancel()
        val all = TitanApp.get(this).repository.subscription.value?.servers ?: return
        watcherJob = scope.launch {
            // Capped: the offer once, not on every look.
            var offered = false
            while (true) {
                if (!watcher.awaitWhitelist(server, all)) return@launch
                val auto = TitanApp.get(this@TitanVpnService).settings.autoBypass.value
                when (val decision = policy.onWhitelist(System.currentTimeMillis(), auto)) {
                    BypassPolicy.Decision.Switch -> {
                        val gen = generation.get()
                        // Separate job: restarting the VPN cancels this watcher. The pings run before the lock.
                        bypassJob = scope.launch {
                            val best = watcher.bestBypass(all) ?: return@launch
                            lock.withLock {
                                if (!stillOn(gen) || running?.id != server.id) return@withLock
                                // Wi-Fi came while measuring: no bypass there.
                                if (!TitanApp.get(this@TitanVpnService).network.current()) return@withLock
                                switchToBypass(best, auto = true)
                            }
                        }
                        return@launch
                    }
                    // «Автопереход на обходы» off: only the offer, nothing more on this connection.
                    BypassPolicy.Decision.Offer -> {
                        notifyWhitelist(null)
                        return@launch
                    }
                    // Our own switches used up this hour: the offer now, our own switch once the hour allows.
                    is BypassPolicy.Decision.Capped -> {
                        if (!offered) notifyWhitelist(null)
                        offered = true
                        delay(decision.retryInMs)
                    }
                    is BypassPolicy.Decision.Wait -> delay(decision.ms)
                }
            }
        }
    }

    /**
     * Moves to [best], the fastest bypass server (measured before taking [lock]), and keeps
     * the way back: the regular server we leave. [auto]: our own switch, counted by [policy].
     * Under [lock].
     */
    private fun switchToBypass(best: Server, auto: Boolean) {
        val app = TitanApp.get(this)
        val all = app.repository.subscription.value?.servers.orEmpty()
        val from = running ?: app.repository.selectedServer()
        // From one bypass to another: the way back stays the same.
        val returnTo = from?.takeUnless { isBypass(it, all) }?.id
            ?: BypassReturn.load(this)?.takeIf { it.bypassId == from?.id }?.returnTo
        if (returnTo != null) BypassReturn.save(this, returnTo, best.id)
        app.repository.select(best.id)
        startVpn(revive = false)
        val nm = getSystemService(NotificationManager::class.java)
        if (running?.id != best.id) {
            // Wi-Fi gave a regular server instead (its notice stays) or the VPN went off:
            // no switch to count or tell about.
            if (tun == null) { nm.cancel(ALERT_ID); cancelReturnNote() }
            return
        }
        cancelReturnNote()
        if (auto) {
            val now = System.currentTimeMillis()
            policy.recordSwitch(now)
            BypassReturn.savePolicy(this, policy, now)
            notifyWhitelist(best.name)
        } else {
            nm.cancel(ALERT_ID)
        }
    }

    /**
     * On a bypass server: once the restrictions end ([WhitelistWatcher.awaitLifted]) back to
     * the regular server the app moved the user from (another one if that one is down), or
     * (with «Автопереход на обходы» off) an offer to. A bypass the user picked himself gets
     * one offer per connection, never a switch. Mobile data only: on Wi-Fi it just waits.
     */
    private fun watchReturn(server: Server, all: List<Server>) {
        returnJob?.cancel()
        returnJob = null
        if (!isBypass(server, all)) return
        val saved = BypassReturn.load(this)?.takeIf { it.bypassId == server.id }
        // The user's own pick of a free bypass: it spends no GB.
        if (saved == null && !ServerGroups.isLimited(server)) return
        // Offered already and no way back of our own to follow (also after a restart): nothing to check.
        val autoNow = TitanApp.get(this).settings.autoBypass.value
        val next = BypassPolicy.onLifted(movedByUs = saved != null, autoBypass = autoNow, offered = returnOffered)
        if (next == BypassPolicy.Back.NONE) return
        val target = saved?.let { s -> all.firstOrNull { it.id == s.returnTo } } ?: regularServer(all) ?: return
        returnJob = scope.launch {
            // [target], or another regular server if that one is down.
            val back = watcher.awaitLifted(target, all)
            val auto = TitanApp.get(this@TitanVpnService).settings.autoBypass.value
            when (BypassPolicy.onLifted(movedByUs = saved != null, autoBypass = auto, offered = returnOffered)) {
                BypassPolicy.Back.RETURN -> {
                    val gen = generation.get()
                    // Separate job: switching cancels this one.
                    bypassJob = scope.launch {
                        lock.withLock {
                            if (!stillOn(gen) || running?.id != server.id) return@withLock
                            returnToRegular(back, auto = true)
                        }
                    }
                }
                BypassPolicy.Back.OFFER -> {
                    returnOffered = true
                    notifyReturn(back, if (saved != null) ReturnNote.OFFER else ReturnNote.OFFER_OWN, server.id)
                }
                BypassPolicy.Back.NONE -> Unit
            }
        }
    }

    /** From a bypass back to [target], a regular server. [auto]: our own way back. Under [lock]. */
    private fun returnToRegular(target: Server, auto: Boolean) {
        BypassReturn.clear(this)
        if (auto) {
            val now = System.currentTimeMillis()
            policy.recordReturn(now)
            BypassReturn.savePolicy(this, policy, now)
        }
        TitanApp.get(this).repository.select(target.id)
        startVpn(revive = false)
        val nm = getSystemService(NotificationManager::class.java)
        // «Мобильный интернет ограничен» is over.
        nm.cancel(ALERT_ID)
        if (auto && running?.id == target.id) notifyReturn(target, ReturnNote.RETURNED) else cancelReturnNote()
    }

    /** Off by hand: no way back is kept, and its offer goes. */
    private fun forgetReturn() {
        BypassReturn.clear(this)
        cancelReturnNote()
    }

    /** The notification about the end of the restrictions goes, the offer with it. */
    private fun cancelReturnNote() {
        offerFor = null
        getSystemService(NotificationManager::class.java).cancel(RETURN_ID)
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
            val text = "Переключились на «${switchedTo.trim()}». Вернёмся на обычный сервер, когда ограничения снимут"
            builder.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
        }
        nm.notify(ALERT_ID, builder.build())
    }

    /** What the notification about the end of the restrictions says. */
    private enum class ReturnNote {
        /** We went back on our own. */
        RETURNED,
        /** We moved the user to the bypass; «Автопереход на обходы» is off: an offer. */
        OFFER,
        /** The user picked the bypass himself: an offer. */
        OFFER_OWN,
    }

    /** [bypassId]: the bypass an offer is for; a tap on another server is moot. */
    private fun notifyReturn(to: Server, note: ReturnNote, bypassId: String? = null) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(ALERT_ID)
        if (!TitanApp.get(this).settings.notifications.value) return
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Ограничения мобильной сети", NotificationManager.IMPORTANCE_HIGH)
        )
        val name = to.name.trim()
        val (title, text) = when (note) {
            ReturnNote.RETURNED -> "Ограничения сняты" to "Вернулись на «$name». Обходы больше не тратят ГБ"
            ReturnNote.OFFER -> "Ограничения сняты" to "Обычные серверы снова работают. Обходы тратят ГБ"
            ReturnNote.OFFER_OWN -> "Обычные серверы снова работают" to "Обходы тратят ГБ. Вернуться на «$name»?"
        }
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
        if (note != ReturnNote.RETURNED) {
            val back = PendingIntent.getService(
                this, 8,
                Intent(this, TitanVpnService::class.java).setAction(ACTION_RETURN)
                    .putExtra(EXTRA_SERVER, to.id).putExtra(EXTRA_FROM, bypassId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Вернуться на «$name»", back)
        }
        offerFor = if (note == ReturnNote.RETURNED) null else bypassId
        nm.notify(RETURN_ID, builder.build())
    }

    /** Full stop: the core, the tun and everything queued. Under [lock] (or in onDestroy). */
    private fun stopVpn() {
        generation.incrementAndGet()
        stepJob?.cancel()
        stepJob = null
        stepTarget = null
        restartJob?.cancel()
        restartJob = null
        updateJob?.cancel()
        updateJob = null
        bypassJob?.cancel()
        bypassJob = null
        unwatchNetwork()
        VpnStatus.localProxy = null
        if (tun != null) VpnStatus.set(VpnState.Disconnecting)
        stopCore()
        runCatching { tun?.close() }
        tun = null
        tunApps = emptySet()
        localProxy = null
        since = 0L
        VpnStatus.set(VpnState.Disconnected)
    }

    override fun onRevoke() {
        // Another VPN took over or the user revoked permission: off, and it stays off.
        KeepAlive.setWanted(this, false)
        cancelQueued()
        forgetReturn()
        scope.launch { lock.withLock { stopVpn() }; stopSelf() }
    }

    override fun onDestroy() {
        if (tun != null) stopVpn() else unwatchNetwork()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------ notification

    /** What the VPN notification says (the Live Activity's states on iOS). */
    private enum class Status { CONNECTING, CONNECTED, SWITCHING, RECONNECTING }

    @Volatile private var status = Status.CONNECTING
    /** The server shown: the current one, or the one being switched to. */
    @Volatile private var statusServer: String? = null
    @Volatile private var statusSince = 0L

    private var iconKey: String? = null
    private var icon: Bitmap? = null

    /**
     * The VPN's own notification, kept at the top of the shade while on: a channel of
     * default importance (the main section, not "Silent") without sound, connection time on
     * a timer, and shown again if swiped away. «Уведомление с сервером» on: the server's
     * flag and name as the title, the status below and ‹ Отключить › (also on the lock
     * screen; on Android 16 asked to be promoted to a Live Update). [since]: connected at.
     */
    @Synchronized
    private fun showStatus(s: Status, server: String?, since: Long = 0L) {
        status = s
        statusServer = server
        statusSince = since
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type) }
            .onFailure { Log.w("Titan", "foreground", it) }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        // The old low-importance channel went to "Silent" at the bottom; a channel's
        // importance can't be raised, hence a new one.
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            }
        )
        val reshow = PendingIntent.getService(
            this, 2, Intent(this, TitanVpnService::class.java).setAction(ACTION_RESHOW), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val timer = status == Status.CONNECTED && statusSince > 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(timer)
            .setWhen(if (timer) statusSince else System.currentTimeMillis())
            .setUsesChronometer(timer)
            .setDeleteIntent(reshow)
            .setContentIntent(open)
        val name = statusServer
        val stop = serviceIntent(1, ACTION_STOP)
        if (TitanApp.get(this).settings.liveNotification.value && name != null) {
            builder.setContentTitle(Flags.nameWithoutFlag(name))
                .setContentText(statusText(live = true, name))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            flagIcon(name)?.let { builder.setLargeIcon(it) }
            val steps = canStep()
            if (steps) builder.addAction(0, getString(R.string.action_prev), serviceIntent(6, ACTION_PREV))
            builder.addAction(0, getString(R.string.action_disconnect), stop)
            if (steps) builder.addAction(0, getString(R.string.action_next), serviceIntent(7, ACTION_NEXT))
            // Android 16 Live Update (the Dynamic Island of Android): string keys, so any
            // compile SDK builds it; older versions ignore them.
            if (Build.VERSION.SDK_INT >= 36) {
                builder.addExtras(Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                    putString("android.shortCriticalText", Flags.codeOf(name)?.uppercase() ?: "VPN")
                })
            }
        } else {
            builder.setContentTitle(getString(R.string.app_name))
                .setContentText(statusText(live = false, name))
                .addAction(0, getString(R.string.action_disconnect), stop)
        }
        return builder.build()
    }

    private fun statusText(live: Boolean, name: String?): String = when (status) {
        Status.CONNECTING -> getString(if (live) R.string.notification_status_connecting else R.string.notification_connecting)
        Status.CONNECTED ->
            if (live || name == null) getString(R.string.notification_status_connected)
            else getString(R.string.notification_connected, name)
        Status.SWITCHING -> getString(R.string.notification_switching)
        Status.RECONNECTING -> getString(R.string.notification_reconnecting)
    }

    /** ‹ › only when the current group has another server to step to. */
    private fun canStep(): Boolean {
        val app = TitanApp.get(this)
        val all = app.repository.subscription.value?.servers ?: return false
        val current = stepTarget ?: app.repository.selectedServer()?.id
        return ServerOrder.neighbor(all, current, true, app.settings.favorites.value, wifiLocked()) != null
    }

    /** The round flag; the shade follows the system theme, so do the disc and the ring. */
    private fun flagIcon(name: String): Bitmap? {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val key = "$name|$night"
        if (key == iconKey) return icon
        val px = (64 * resources.displayMetrics.density).roundToInt()
        icon = runCatching {
            if (night) Flags.circleBitmap(this, name, px, 0x1AFFFFFF, 0x26FFFFFF)
            else Flags.circleBitmap(this, name, px, 0x14000000, 0x1F000000)
        }.getOrNull()
        iconKey = key
        return icon
    }

    private fun serviceIntent(code: Int, action: String): PendingIntent = PendingIntent.getService(
        this, code, Intent(this, TitanVpnService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        private const val ACTION_START = "com.titanvps.app.START"
        private const val ACTION_STOP = "com.titanvps.app.STOP"
        private const val ACTION_SWITCH_BYPASS = "com.titanvps.app.SWITCH_BYPASS"
        /** Back from a bypass ([EXTRA_FROM]) to the regular server [EXTRA_SERVER]. */
        private const val ACTION_RETURN = "com.titanvps.app.RETURN"
        private const val EXTRA_SERVER = "server"
        private const val EXTRA_FROM = "from"
        private const val ACTION_RESHOW = "com.titanvps.app.RESHOW"
        private const val ACTION_PREV = "com.titanvps.app.PREV"
        private const val ACTION_NEXT = "com.titanvps.app.NEXT"
        /** ACTION_START from keep-alive (boot, app update): a failing core is retried. */
        private const val EXTRA_REVIVE = "revive"
        private const val ALERT_CHANNEL_ID = "whitelist"
        private const val UPDATE_CHANNEL_ID = "updates"
        private const val UPDATE_ID = 3
        private const val ALERT_ID = 2
        /** «Ограничения сняты» and the offers to go back from a bypass. */
        private const val RETURN_ID = 4
        private const val CHANNEL_ID = "vpn_status"
        private const val OLD_CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val MTU = 1500
        /** A new network settles (DHCP, DNS) before the core restarts on it. */
        private const val NETWORK_SETTLE_MS = 1_500L
        /** Quick ‹ › taps step on; the VPN reconnects once they pause. */
        private const val STEP_SETTLE_MS = 400L
        private const val PLUGIN_CHECK_MS = 5_000L

        /** Kill switch: false closes a stopped core's fd copy instead of parking it ([releaseCoreFd]). */
        private const val FD_SWAP = true

        /** Never written, both ends kept for the life of the process: never readable. */
        private val parking: Array<ParcelFileDescriptor> by lazy { ParcelFileDescriptor.createPipe() }

        /** Connects, or switches to the selected server if already on. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TitanVpnService::class.java).setAction(ACTION_START)
            )
        }

        /** Keep-alive brings the VPN back (boot, app update): like [start], with retries. */
        internal fun revive(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, TitanVpnService::class.java).setAction(ACTION_START).putExtra(EXTRA_REVIVE, true)
            )
        }

        /** Off by hand: stays off (keep-alive lets it be). */
        fun stop(context: Context) {
            context.startService(Intent(context, TitanVpnService::class.java).setAction(ACTION_STOP))
        }

        /** The previous / next server while on (‹ › of the widget). */
        fun step(context: Context, forward: Boolean) {
            runCatching {
                context.startService(Intent(context, TitanVpnService::class.java).setAction(if (forward) ACTION_NEXT else ACTION_PREV))
            }
        }

        /** Draws the notification again (its look changed in Настройки, notifications allowed); only while on. */
        fun reshow(context: Context) {
            val st = VpnStatus.state.value
            if (st !is VpnState.Connected && st != VpnState.Connecting) return
            runCatching { context.startService(Intent(context, TitanVpnService::class.java).setAction(ACTION_RESHOW)) }
        }
    }
}
