package com.titanvps.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.withContext
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
    /**
     * Apps Android left out of [tun] (installed when it was built); leaving out other apps,
     * also one installed since, needs a new tun.
     */
    private var tunApps: Set<String> = emptySet()
    /** The copy of [tun] the running core reads: a raw fd, owned by nobody on our side; -1 when none. */
    private var coreFd = -1
    /** Go got [coreFd] (XrayCore.start was called with it), so it may still hold the number. */
    private var coreFdGiven = false
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

    /** Naive / Mieru client of the current server, if it is one. */
    private var plugin: PluginProcess? = null

    private var wifiJob: Job? = null
    private var updateJob: Job? = null
    private var watchdogJob: Job? = null
    @Volatile private var restartJob: Job? = null
    @Volatile private var stepJob: Job? = null
    /** The server ‹ › stepped to and not connected yet: quick taps step on from it. */
    @Volatile private var stepTarget: String? = null
    /** The last off by hand (its stop under [lock]); later commands end the service after it. */
    @Volatile private var stopJob: Job? = null
    /** Plugin clients that exited soon after their start, in a row (see [watchPlugin]). */
    @Volatile private var pluginDeaths = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_SWITCH_BYPASS -> {
                if (tun == null) showStatus(Status.CONNECTING, null) else showStatus(status, statusServer, statusSince)
                val gen = generation.get()
                val repo = TitanApp.get(this).repository
                // The pick when asked: one made while measuring (by hand, ‹ ›) wins.
                val picked = repo.selectedServer()?.id
                scope.launch {
                    // Measured outside the lock: an off by hand never waits for the pings.
                    val best = repo.subscription.value?.servers?.let { watcher.bestBypass(it) }
                    val skipped = lock.withLock {
                        // Turned off while measuring: stays off.
                        if (gen != generation.get()) return@withLock true
                        // Another server picked while measuring: it stays.
                        if (repo.selectedServer()?.id != picked) return@withLock true
                        when {
                            best != null -> {
                                // Wanted only now: a stale «Переключить на обход» with nothing to
                                // switch to leaves an off by hand off, also for keep-alive.
                                KeepAlive.setWanted(this@TitanVpnService, true)
                                switchToBypass(best, auto = false)
                            }
                            // Nothing to switch to and nothing on: no "Подключение…" left behind.
                            tun == null -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                        }
                        false
                    }
                    // Not switched: the service ends after this command unless the VPN is on (or on its way).
                    if (skipped) finishIfOff(startId)
                }
                return START_STICKY
            }
            // The status notification was swiped away (Android 14+ lets users): back while on.
            ACTION_RESHOW -> {
                // Off by hand (perhaps still on its way): not shown again, the service ends after it.
                if (!KeepAlive.wanted(this)) { finishIfOff(startId); return START_NOT_STICKY }
                val st = VpnStatus.state.value
                if (st !is VpnState.Connected && st != VpnState.Connecting) { stopSelf(startId); return START_NOT_STICKY }
                showStatus(status, statusServer, statusSince)
                return START_STICKY
            }
            ACTION_STOP -> {
                // Off by hand: nothing queued may turn the VPN back on, and keep-alive lets it be.
                KeepAlive.setWanted(this, false)
                cancelQueued()
                warnAlwaysOn()
                stopJob = scope.launch { lock.withLock { stopVpn() }; stopSelf(startId) }
                return START_NOT_STICKY
            }
            ACTION_PREV, ACTION_NEXT -> {
                onStep(forward = action == ACTION_NEXT)
                // Off by hand (perhaps still on its way): only the pick moved, the service ends after it.
                if (!KeepAlive.wanted(this)) { finishIfOff(startId); return START_NOT_STICKY }
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
        // A connect or switch asked for: a plugin client's quick exits count from zero again.
        pluginDeaths = 0
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
            // Android leaves out only apps that are installed when the tun is built, and never
            // looks again: the installed ones are compared, so a new one gets a new tun.
            val present = installed(apps)
            val current = tun
            if (current == null) {
                VpnStatus.set(VpnState.Connecting)
                showStatus(Status.CONNECTING, target.name)
                val (fresh, applied) = establish(apps)
                tun = fresh
                tunApps = applied intersect present
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
                if (target == running && present == tunApps && VpnStatus.state.value is VpnState.Connected) {
                    showStatus(Status.CONNECTED, target.name, since)
                    return
                }
                VpnStatus.set(VpnState.Connecting)
                showStatus(Status.SWITCHING, target.name)
                if (present == tunApps) {
                    stopCore()
                } else {
                    // Other apps left out: the new tun first (Android moves the routes to it).
                    val (fresh, applied) = establish(apps)
                    stopCore()
                    tun = fresh
                    tunApps = applied intersect present
                    runCatching { current.close() }
                }
                clearLogs()
            }
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

    /** A new tun leaving out [apps], and those of them Android took (not one that isn't installed). */
    private fun establish(apps: Set<String>): Pair<ParcelFileDescriptor, Set<String>> {
        val applied = HashSet<String>()
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
                    apps.forEach { pkg -> if (runCatching { addDisallowedApplication(pkg) }.isSuccess) applied += pkg }
                }
                .setBlocking(false)
                .apply { if (Build.VERSION.SDK_INT >= 29) setMetered(false) }
                .establish()
        } catch (e: Exception) {
            Log.w("Titan", "establish failed", e)
            null
        }
        return (pfd ?: throw StartRefused("Нет разрешения на VPN")) to applied
    }

    /** Those of [packages] that are installed (and visible to us). */
    private fun installed(packages: Set<String>): Set<String> = packages.filterTo(HashSet()) { pkg ->
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(pkg, 0)
            }
        }.isSuccess
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
        try {
            // Naive / Mieru: their own client as a local SOCKS, Xray's proxy outbound points
            // at it; MASQUE: Xray's own client.
            val (serverJson, p) = Plugins.prepare(this, server.name, server.xrayJson, server.proxyTag)
            plugin = p
            // Go gets its own fd number, only once the client is up (a failed start closes
            // it again); detached at once, so Android's fd owner checks never see Go close
            // a descriptor a ParcelFileDescriptor still owns.
            coreFd = pfd.dup().detachFd()
            val config = XrayConfigs.buildRunConfig(
                serverJson, coreFd, TitanApp.get(this).assetDir, MTU, cacheDir.absolutePath, server.proxyTag, localProxy,
            )
            coreFdGiven = true
            XrayCore.start(config, dns) { fd -> protect(fd) }
        } catch (e: Exception) {
            stopCore()
            throw e
        }
        VpnStatus.localProxy = localProxy
        running = server
        startWatcher(server)
        watchWifi(server, all)
        watchPlugin()
    }

    /** Stops the core and its plugin; the tun stays. Under [lock] (or in onDestroy). */
    private fun stopCore() {
        wifiJob?.cancel()
        wifiJob = null
        watcherJob?.cancel()
        watcherJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        running = null
        XrayCore.stop()
        plugin?.close()
        plugin = null
        releaseCoreFd()
    }

    /**
     * The stopped core's copy of the tun. FD_SWAP: its number is not handed back to the
     * system at once, so Go closing it later (iOS: when the stopped instance is
     * garbage-collected) can't hit a reused number such as the next tun; it now points at a
     * pipe nobody writes to, which never reads as ready, so an old reader can't spin or take
     * our packets. Libxray v26.9.30 doesn't close it at all, so [park] closes the oldest
     * parked numbers itself. A number Go never got (the start failed before) is simply closed.
     */
    private fun releaseCoreFd() {
        val fd = coreFd
        if (fd < 0) return
        coreFd = -1
        val given = coreFdGiven
        coreFdGiven = false
        if (FD_SWAP && given && park(fd)) return
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
        // The network the core starts on (launchCore reads the same one). None (on at boot or
        // in the car before Wi-Fi / LTE is up): the first network to come is a change too.
        val start = cm.activeNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = start
            private var key: String? = null

            override fun onAvailable(network: Network) {
                if (network == current) return
                current = network
                key = null
                restartCore(network, NETWORK_SETTLE_MS)
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

    /**
     * A Naive / Mieru / sing-box client that exits takes the server with it: restart both.
     * After a minute up, at once; one that keeps exiting soon after its start waits like a
     * failed restart ([KeepAlive.BACKOFF_SEC], then every 30 s while keep-alive is on; without
     * it the VPN goes off after the list).
     */
    private fun watchPlugin() {
        watchdogJob?.cancel()
        val p = plugin ?: return
        val gen = generation.get()
        val started = launchedAt
        watchdogJob = scope.launch {
            while (true) {
                delay(PLUGIN_CHECK_MS)
                val lived = SystemClock.elapsedRealtime() - started
                if (p.isAlive) {
                    if (lived >= PLUGIN_STABLE_MS) pluginDeaths = 0
                    continue
                }
                Log.w("Titan", "plugin client exited")
                lock.withLock {
                    // Stopped, turned off or restarted since.
                    if (!stillOn(gen) || plugin !== p) return@launch
                    pluginDeaths = if (lived < PLUGIN_STABLE_MS) pluginDeaths + 1 else 0
                    val deaths = pluginDeaths
                    val waitSec = when {
                        deaths == 0 -> 0
                        deaths <= KeepAlive.BACKOFF_SEC.size -> KeepAlive.BACKOFF_SEC[deaths - 1]
                        TitanApp.get(this@TitanVpnService).settings.keepAlive.value -> 30
                        else -> {
                            giveUp("Не удалось подключиться. Попробуйте другой сервер")
                            return@launch
                        }
                    }
                    // The server is down while it waits: the app and the notification say so.
                    VpnStatus.set(VpnState.Connecting)
                    showStatus(Status.RECONNECTING, running?.name)
                    restartCore(null, waitSec * 1000L, firstBackoff = deaths)
                }
                return@launch
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
        // Off, or an off by hand on its way: only the pick moves.
        if (tun == null || !KeepAlive.wanted(this)) return
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

    /**
     * Ends the service for command [startId] once the off by hand on its way (if any) is done:
     * that off's own stopSelf has an older id by then and would leave the service, and its
     * notification, behind. A START since keeps it; checked on the main thread, where
     * onStartCommand sets wanted.
     */
    private fun finishIfOff(startId: Int) {
        val off = stopJob
        scope.launch {
            off?.join()
            withContext(Dispatchers.Main) {
                if (tun != null || KeepAlive.wanted(this@TitanVpnService)) return@withContext
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
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

    /** Watches for mobile whitelist mode while on a regular server. */
    private fun startWatcher(server: Server) {
        watcherJob?.cancel()
        val all = TitanApp.get(this).repository.subscription.value?.servers ?: return
        watcherJob = scope.launch {
            if (!watcher.awaitWhitelist(server, all)) return@launch
            if (TitanApp.get(this@TitanVpnService).settings.autoBypass.value) {
                val gen = generation.get()
                val askedAt = SystemClock.elapsedRealtime()
                // Separate job: restarting the VPN cancels this watcher. The pings run before the lock.
                scope.launch {
                    val best = watcher.bestBypass(all) ?: return@launch
                    lock.withLock {
                        if (!stillOn(gen)) return@withLock
                        // Another server picked while measuring (by hand, ‹ ›): it stays.
                        if (TitanApp.get(this@TitanVpnService).repository.selectedServer()?.id != server.id) return@withLock
                        // A connect, switch or restart since: that core's own watcher looks again.
                        if (running != null && launchedAt > askedAt) return@withLock
                        switchToBypass(best, auto = true)
                    }
                }
            } else {
                notifyWhitelist(null)
            }
        }
    }

    /** Moves to [best], the fastest bypass server (measured before taking [lock]). Under [lock]. */
    private fun switchToBypass(best: Server, auto: Boolean) {
        TitanApp.get(this).repository.select(best.id)
        startVpn(revive = false)
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
        pluginDeaths = 0
        unwatchNetwork()
        // «Переключить на обход» belongs to the session that ended.
        runCatching { getSystemService(NotificationManager::class.java).cancel(ALERT_ID) }
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
     * default importance (the main section, not "Silent") without sound, colorized in our
     * blue unless it is a Live Update, connection time on a timer, and shown again if swiped
     * away. «Уведомление с сервером» on: the server's flag and name as the title, the status
     * below and ‹ Отключить › (also on the lock screen; on Android 16 asked to be promoted to
     * a Live Update). [since]: connected at.
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
        val live = name != null && TitanApp.get(this).settings.liveNotification.value
        // Android 16 is asked to promote it to a Live Update, which a colorized one can't be.
        val promoted = live && Build.VERSION.SDK_INT >= 36
        // Elsewhere our blue: Android ranks a colorized foreground service first (Android 12+:
        // in the top section, with media and calls), not under every newer notification.
        if (!promoted) builder.setColorized(true).setColor(BRAND_COLOR)
        if (name != null && live) {
            builder.setContentTitle(Flags.nameWithoutFlag(name))
                .setContentText(statusText(live = true, name))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            flagIcon(name, onBrand = !promoted)?.let { builder.setLargeIcon(it) }
            val steps = canStep()
            if (steps) builder.addAction(0, getString(R.string.action_prev), serviceIntent(6, ACTION_PREV))
            builder.addAction(0, getString(R.string.action_disconnect), stop)
            if (steps) builder.addAction(0, getString(R.string.action_next), serviceIntent(7, ACTION_NEXT))
            // Android 16 Live Update (the Dynamic Island of Android): string keys, so any
            // compile SDK builds it; older versions ignore them.
            if (promoted) {
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

    /**
     * The round flag. On our blue ([onBrand]): a white disc, so the shield of a server without
     * a flag still shows; otherwise the shade follows the system theme, so do the disc and the ring.
     */
    private fun flagIcon(name: String, onBrand: Boolean): Bitmap? {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val key = "$name|$night|$onBrand"
        if (key == iconKey) return icon
        val px = (64 * resources.displayMetrics.density).roundToInt()
        icon = runCatching {
            when {
                onBrand -> Flags.circleBitmap(this, name, px, 0xFFFFFFFF.toInt(), 0x40FFFFFF)
                night -> Flags.circleBitmap(this, name, px, 0x1AFFFFFF, 0x26FFFFFF)
                else -> Flags.circleBitmap(this, name, px, 0x14000000, 0x1F000000)
            }
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
        private const val ACTION_RESHOW = "com.titanvps.app.RESHOW"
        private const val ACTION_PREV = "com.titanvps.app.PREV"
        private const val ACTION_NEXT = "com.titanvps.app.NEXT"
        /** ACTION_START from keep-alive (boot, app update): a failing core is retried. */
        private const val EXTRA_REVIVE = "revive"
        private const val ALERT_CHANNEL_ID = "whitelist"
        private const val UPDATE_CHANNEL_ID = "updates"
        private const val UPDATE_ID = 3
        private const val ALERT_ID = 2
        private const val CHANNEL_ID = "vpn_status"
        private const val OLD_CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        /** The colour of the VPN notification (the app's blue). */
        private val BRAND_COLOR = 0xFF1A66FF.toInt()
        private const val MTU = 1500
        /** A new network settles (DHCP, DNS) before the core restarts on it. */
        private const val NETWORK_SETTLE_MS = 1_500L
        /** Quick ‹ › taps step on; the VPN reconnects once they pause. */
        private const val STEP_SETTLE_MS = 400L
        private const val PLUGIN_CHECK_MS = 5_000L
        /** A plugin client up this long has started fine: its next exit restarts it at once. */
        private const val PLUGIN_STABLE_MS = 60_000L

        /** Kill switch: false closes a stopped core's fd copy instead of parking it ([releaseCoreFd]). */
        private const val FD_SWAP = true

        /** Never written, both ends kept for the life of the process: never readable. */
        private val parking: Array<ParcelFileDescriptor> by lazy { ParcelFileDescriptor.createPipe() }

        /** Parked numbers ([releaseCoreFd]) and when they were parked, oldest first; for the life of the process. */
        private val parked = LinkedHashMap<Int, Long>()
        /** Parked numbers kept in any case: a few switches' worth. */
        private const val PARKED_MAX = 32
        /**
         * A parked number this old is closed once there are more than [PARKED_MAX]: Go collects
         * garbage at least every 2 minutes, so a stopped instance that ever closes its copy has
         * done so by then.
         */
        private const val PARKED_CLOSE_AFTER_MS = 5 * 60_000L

        /**
         * Points [fd] at the parking pipe. Beyond [PARKED_MAX], the oldest numbers parked long
         * enough ago are closed, so keep-alive retrying every 30 s for days, or a car unit that
         * never restarts the app, doesn't run out of fds. False: [fd] couldn't be parked.
         */
        private fun park(fd: Int): Boolean {
            if (runCatching { Os.dup2(parking[0].fileDescriptor, fd) }.isFailure) return false
            val now = SystemClock.elapsedRealtime()
            synchronized(parked) {
                // The same number again: Go closed the old copy and the number came back as a new one.
                parked.remove(fd)
                parked[fd] = now
                val pipe = fdTarget(parking[0].fd) ?: return true
                val oldest = parked.entries.iterator()
                while (parked.size > PARKED_MAX && oldest.hasNext()) {
                    val (n, at) = oldest.next()
                    if (now - at < PARKED_CLOSE_AFTER_MS) break
                    oldest.remove()
                    // Only while it still is our pipe: a number Go closed may belong to anything now.
                    if (fdTarget(n) == pipe) runCatching { ParcelFileDescriptor.adoptFd(n).close() }
                }
            }
            return true
        }

        /** What [fd] is open on ("pipe:[…]", a path…); null when it isn't open. */
        private fun fdTarget(fd: Int): String? = runCatching { Os.readlink("/proc/self/fd/$fd") }.getOrNull()

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
