package com.titanvps.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.os.PowerManager
import android.os.SystemClock
import android.system.Os
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.titanvps.app.MainActivity
import com.titanvps.app.R
import com.titanvps.app.TitanApp
import com.titanvps.app.core.ConnectionCheck
import com.titanvps.app.core.GeoFiles
import com.titanvps.app.core.LiveLatency
import com.titanvps.app.core.PingClient
import com.titanvps.app.core.PluginProcess
import com.titanvps.app.core.Plugins
import com.titanvps.app.core.TrafficMeter
import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.core.XrayCore
import com.titanvps.app.data.Flags
import com.titanvps.app.data.Fmt
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
import kotlinx.coroutines.job
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
    /** The subscription's `sniffing-enable` the running core was built with. */
    private var runningSniffing: Boolean? = null
    /** Connected at (the notification's timer); network and plugin restarts keep it. */
    private var since = 0L
    /** When the core was last started (elapsed realtime): a restart asked for before that is moot. */
    @Volatile private var launchedAt = 0L

    private var watcherJob: Job? = null
    private val watcher by lazy { WhitelistWatcher(this) }
    /**
     * On a bypass: waits for the restrictions to end ([watchReturn]). A core restart on the
     * same bypass keeps it, with its timer and checks in a row; another server or a stop ends it.
     */
    private var returnJob: Job? = null
    /** The bypass and the server list [returnJob] watches with. */
    private var returnFor: String? = null
    private var returnAll: List<Server> = emptyList()
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
    /** The last off by hand (its stop under [lock]); later commands end the service after it. */
    @Volatile private var stopJob: Job? = null
    /** Plugin clients that exited soon after their start, in a row (see [watchPlugin]). */
    @Volatile private var pluginDeaths = 0
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        // The notification's numbers are looked at only while the screen is on.
        val screen = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        runCatching { ContextCompat.registerReceiver(this, screenReceiver, screen, ContextCompat.RECEIVER_NOT_EXPORTED) }
            .onFailure { Log.w("Titan", "screen receiver", it) }
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
    }

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
                forgetReturn()
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
                if (target == running && sub.info.sniffing == runningSniffing && present == tunApps &&
                    VpnStatus.state.value is VpnState.Connected
                ) {
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
        // The way back's checks belong to one bypass: kept over its restarts only.
        if (returnFor != server.id) stopReturnWatch()
        launchedAt = SystemClock.elapsedRealtime()
        // The notification's latency was another core's: another network, server or connection.
        LiveLatency.clear()
        // Resolver for "direct" traffic: the carrier's/router's own DNS (like Happ), since
        // foreign resolvers such as 1.1.1.1 are often blocked on Russian mobile networks.
        val dns = underlyingDnsServer(network)
        // The panel's word on sniffing as of now: a new header value counts from the next core start.
        val sniffing = TitanApp.get(this).repository.subscription.value?.info?.sniffing
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
                sniffingEnable = sniffing,
            )
            coreFdGiven = true
            XrayCore.start(config, dns) { fd -> protect(fd) }
        } catch (e: Exception) {
            stopCore()
            throw e
        }
        VpnStatus.localProxy = localProxy
        running = server
        runningSniffing = sniffing
        // The way back belongs to the bypass we moved to: any other server (our way back, the
        // user's pick, Wi-Fi's regular one) drops it.
        val saved = BypassReturn.load(this)
        if (saved != null && saved.bypassId != server.id) {
            BypassReturn.clear(this)
            // «Вернёмся на обычный сервер» goes with it. Not for a regular server: Wi-Fi's
            // «Обходы недоступны на Wi-Fi» may have just been shown there.
            if (isBypass(server, all)) getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        }
        // So does the offer to go back (a restart of the same bypass keeps it).
        val offeredOn = offerFor
        if (offeredOn != null && offeredOn != server.id) cancelReturnNote()
        startWatcher(server)
        watchWifi(server, all)
        watchReturn(server, all)
        watchPlugin()
    }

    /**
     * Stops the core and its plugin; the tun stays, so do the way back's checks
     * ([watchReturn]: direct, not through the core). Under [lock] (or in onDestroy).
     */
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

    /**
     * A regular server to fall back to: its АВТО entry if there is one, else Безлимитные · все
     * серверы (its balancer picks a live server too), else the first one.
     */
    private fun regularServer(all: List<Server>): Server? {
        val regular = ServerGroups.split(all).toMap()[ServerGroups.Group.SERVERS].orEmpty()
        return regular.firstOrNull { "авто" in it.name.lowercase() }
            ?: regular.firstOrNull(ServerGroups::isUnlimited)
            ?: regular.firstOrNull()
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
                    // Off this bypass meanwhile (our way back, the user's pick): nothing to undo.
                    if (!stillOn(gen) || running?.id != server.id) return@withLock
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
                        val askedAt = SystemClock.elapsedRealtime()
                        // Separate job: restarting the VPN cancels this watcher. The pings run before the lock.
                        bypassJob = scope.launch {
                            val best = watcher.bestBypass(all) ?: return@launch
                            lock.withLock {
                                if (!stillOn(gen) || running?.id != server.id) return@withLock
                                // Another server picked while measuring (by hand, ‹ ›): it stays.
                                if (TitanApp.get(this@TitanVpnService).repository.selectedServer()?.id != server.id) return@withLock
                                // A connect, switch or restart since: that core's own watcher looks again.
                                if (launchedAt > askedAt) return@withLock
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
        // A core restart on the same bypass (a new network, a plugin exit): the checks go on
        // with their timer and checks in a row, so restarts never keep the user on paid GB.
        if (returnJob?.isActive == true && returnFor == server.id && returnAll == all) return
        stopReturnWatch()
        if (!isBypass(server, all)) return
        val saved = BypassReturn.load(this)?.takeIf { it.bypassId == server.id }
        // The user's own pick of a free bypass: it spends no GB.
        if (saved == null && !ServerGroups.isLimited(server)) return
        // Offered already and no way back of our own to follow (also after a restart): nothing to check.
        val autoNow = TitanApp.get(this).settings.autoBypass.value
        val next = BypassPolicy.onLifted(movedByUs = saved != null, autoBypass = autoNow, offered = returnOffered)
        if (next == BypassPolicy.Back.NONE) return
        val target = saved?.let { s -> all.firstOrNull { it.id == s.returnTo } } ?: regularServer(all) ?: return
        returnFor = server.id
        returnAll = all
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

    /** The way back's checks end: another server, or the VPN goes off. Under [lock] (or in onDestroy). */
    private fun stopReturnWatch() {
        returnJob?.cancel()
        returnJob = null
        returnFor = null
        returnAll = emptyList()
    }

    /** Off by hand: no way back is kept; its offer and «Вернёмся на обычный сервер» go. */
    private fun forgetReturn() {
        BypassReturn.clear(this)
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
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
        pluginDeaths = 0
        bypassJob?.cancel()
        bypassJob = null
        stopReturnWatch()
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
        postedAt = 0L
        // The notification's numbers go with it: nothing redraws it after the stop.
        stopTicking()
        LiveLatency.clear()
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
        stopTicking()
        runCatching { unregisterReceiver(screenReceiver) }
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
    /**
     * The notification's time, which ranks it among equal ones: the connect, kept while
     * connecting, switching and reconnecting, so its updates don't move it. 0 while off.
     */
    @Volatile private var postedAt = 0L
    /** startForeground went through: the notification is the service's, a look may redraw it. */
    @Volatile private var foreground = false

    private var iconKey: String? = null
    private var icon: Bitmap? = null

    /** The connection's traffic and speed; [meterSince]: its `since`, so a core restart keeps the count. */
    private var meter: TrafficMeter.Session? = null
    private var meterSince = 0L
    /** What the notification says now: a look posts it only when this changes. */
    private var shown: Content? = null

    /** The screen is on: the numbers can be seen. */
    @Volatile private var screenOn = true
    /** Looks at the numbers ([startTicking]); under the monitor, like what follows. */
    private var tickJob: Job? = null
    private var probeJob: Job? = null
    /** The last probe (elapsedRealtime); 0: none yet. */
    private var probedAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // The numbers at once, then every 2 s.
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    startTicking()
                }
                // Nobody sees them: no looks, no probe.
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stopTicking()
                }
            }
        }
    }

    /**
     * The VPN's own notification, kept at the top of the shade while on (like other VPN
     * apps): a channel of default importance (the main section, not "Silent") without sound,
     * colorized in our blue unless it is a Live Update, a time that stays put, connection time
     * on a timer, also on the lock screen, and shown again if swiped away. «Уведомление с
     * сервером» on: the server's flag and name as the title, the status below with ↓ ↑ while
     * connected (expanded: the latency and the traffic), and ‹ Отключить › (on Android 16 asked
     * to be promoted to a Live Update). [since]: connected at. The numbers between status
     * changes: [startTicking].
     */
    @Synchronized
    private fun showStatus(s: Status, server: String?, since: Long = 0L) {
        status = s
        statusServer = server
        statusSince = since
        if (since > 0) postedAt = since else if (postedAt == 0L) postedAt = System.currentTimeMillis()
        // A new connection: its traffic counts from its connect.
        if (s == Status.CONNECTED && since > 0 && since != meterSince) {
            meterSince = since
            meter = runCatching { TrafficMeter.totals() }.getOrNull()?.let { TrafficMeter.Session(it) }
        }
        // Coming on with the VPN still off (a sticky restart): the off state's notification waits.
        if (s == Status.CONNECTING) OffNotification.starting()
        ensureChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching {
            val content = content()
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(content), type)
            content
        }
            .onSuccess {
                foreground = true
                shown = it
            }
            .onFailure { Log.w("Titan", "foreground", it) }
        if (s == Status.CONNECTED) startTicking()
    }

    /**
     * What the notification says, without its intents and icons: a look redraws it only when
     * this changes. [more]: under [text] when expanded, the latency and the traffic.
     */
    private data class Content(
        val status: Status,
        val name: String?,
        val live: Boolean,
        val promoted: Boolean,
        val steps: Boolean,
        val night: Boolean,
        val text: String,
        val latency: String?,
        /** [latency] under 120 ms: green. */
        val fast: Boolean,
        val spent: String?,
        /** The Live Update's chip (only when [promoted]). */
        val chip: String?,
    ) {
        val more: String? get() = listOfNotNull(latency, spent).joinToString(" · ").ifEmpty { null }
    }

    /** Under the monitor. */
    private fun content(): Content {
        val name = statusServer
        val live = name != null && TitanApp.get(this).settings.liveNotification.value
        // Android 16 is asked to promote it to a Live Update, which a colorized one can't be;
        // only while Live Updates are allowed for the app, or it would lose the top as well.
        val promoted = live && Build.VERSION.SDK_INT >= 36 && liveUpdatesAllowed(this)
        // The theme counts only off our blue (the flag's disc, the green).
        val night = promoted &&
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        var text = statusText(live, name)
        var latency: String? = null
        var fast = false
        var spent: String? = null
        var chip = if (promoted) name?.let { Flags.codeOf(it)?.uppercase() } ?: "VPN" else null
        if (live && name != null && status == Status.CONNECTED) {
            val now = SystemClock.elapsedRealtime()
            val m = meter
            val speed = m?.speed(now)
            // As Главная: «↓ 2.4 МБ/с ↑ 140 КБ/с».
            if (speed != null) text += " · ↓ " + Fmt.speed(speed.down) + " ↑ " + Fmt.speed(speed.up)
            val ms = LiveLatency.shown(name, now)
            if (ms != null) {
                latency = getString(R.string.notification_ms, ms)
                fast = ms < ConnectionCheck.FAST_MS
            }
            // Fmt.bytes counts in МБ: «Потрачено 0 МБ» isn't said.
            if (m != null && m.used >= SPENT_MIN) spent = getString(R.string.notification_spent, Fmt.bytes(m.used))
            if (promoted) when {
                speed != null && speed.down >= CHIP_SPEED_MIN -> chip = "↓" + shortSpeed(speed.down)
                latency != null -> chip = latency
            }
        }
        val steps = live && canStep()
        return Content(status, name, live, promoted, steps, night, text, latency, fast, spent, chip)
    }

    /** The notification's intents, the same each time: a redraw asks Android for none. */
    private val reshowIntent by lazy {
        PendingIntent.getService(this, 2, Intent(this, TitanVpnService::class.java).setAction(ACTION_RESHOW), PendingIntent.FLAG_IMMUTABLE)
    }
    private val openIntent by lazy {
        PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    }
    private val stopIntent by lazy { serviceIntent(1, ACTION_STOP) }
    private val prevIntent by lazy { serviceIntent(6, ACTION_PREV) }
    private val nextIntent by lazy { serviceIntent(7, ACTION_NEXT) }

    private fun buildNotification(c: Content): Notification {
        val timer = c.status == Status.CONNECTED && statusSince > 0
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setShowWhen(timer)
            // Connected at (the timer's start; first shown while connecting), not now: a
            // redraw doesn't reorder the shade.
            .setWhen(postedAt)
            .setUsesChronometer(timer)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDeleteIntent(reshowIntent)
            .setContentIntent(openIntent)
        val name = c.name
        // Elsewhere our blue: Android ranks a colorized foreground service first (Android 12+:
        // in the top section, with media and calls), not under every newer notification.
        if (!c.promoted) builder.setColorized(true).setColor(BRAND_COLOR)
        if (name != null && c.live) {
            builder.setContentTitle(Flags.nameWithoutFlag(name))
                .setContentText(c.text)
            // Expanded (a Live Update may be): the latency and the traffic on a line of their own.
            if (c.more != null) builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded(c)))
            flagIcon(name, onBrand = !c.promoted)?.let { builder.setLargeIcon(it) }
            if (c.steps) builder.addAction(0, getString(R.string.action_prev), prevIntent)
            builder.addAction(0, getString(R.string.action_disconnect), stopIntent)
            if (c.steps) builder.addAction(0, getString(R.string.action_next), nextIntent)
            // Android 16 Live Update (the Dynamic Island of Android): string keys, so any
            // compile SDK builds it; older versions ignore them.
            if (c.promoted) {
                builder.addExtras(Bundle().apply {
                    putBoolean("android.requestPromotedOngoing", true)
                    putString("android.shortCriticalText", c.chip)
                })
            }
        } else {
            builder.setContentTitle(getString(R.string.app_name))
                .setContentText(c.text)
                .addAction(0, getString(R.string.action_disconnect), stopIntent)
        }
        return builder.build()
    }

    /**
     * The status line, then the latency and the traffic. The latency as Главная's: green under
     * 120 ms, else in the text's colour, never red or orange (from 300 ms none is shown).
     */
    private fun expanded(c: Content): CharSequence {
        val out = SpannableStringBuilder(c.text).append('\n')
        c.latency?.let { ms ->
            val start = out.length
            out.append(ms)
            // On our blue a light green that reads there; elsewhere Главная's.
            val green = when {
                !c.promoted -> GREEN_ON_BRAND
                c.night -> GREEN_NIGHT
                else -> GREEN_DAY
            }
            if (c.fast) out.setSpan(ForegroundColorSpan(green), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (c.spent != null) out.append(" · ")
        }
        c.spent?.let { out.append(it) }
        return out
    }

    private fun statusText(live: Boolean, name: String?): String = when (status) {
        Status.CONNECTING -> getString(if (live) R.string.notification_status_connecting else R.string.notification_connecting)
        Status.CONNECTED ->
            if (live || name == null) getString(R.string.notification_status_connected)
            else getString(R.string.notification_connected, name)
        Status.SWITCHING -> getString(R.string.notification_switching)
        Status.RECONNECTING -> getString(R.string.notification_reconnecting)
    }

    /**
     * While on with «Уведомление с сервером» and the screen on: a look at once, then every
     * [TICK_MS] ([look]). Never takes [lock] nor touches the tun, so a switch or an off never
     * waits for it; stopped with the VPN (stopVpn, onDestroy) and when the screen goes off.
     */
    @Synchronized
    private fun startTicking() {
        if (tickJob?.isActive == true || !ticks()) return
        tickJob = scope.launch {
            val self = coroutineContext.job
            // A failed look is logged, never thrown: this process carries the VPN.
            while (runCatching { look(self) }.onFailure { Log.w("Titan", "notification look", it) }.getOrDefault(true)) {
                delay(TICK_MS)
            }
        }
    }

    /** Worth a look: the VPN on, the screen on, the numbers shown. Under the monitor. */
    private fun ticks(): Boolean =
        tun != null && screenOn && TitanApp.get(this).settings.liveNotification.value

    @Synchronized
    private fun stopTicking() {
        tickJob?.cancel()
        tickJob = null
        probeJob?.cancel()
        probeJob = null
    }

    /**
     * One look of the ticker [self]: the numbers redrawn with notify (never startForeground),
     * only when what the notification says changed, and the probe when it is due. False:
     * nothing to look for any more.
     */
    private fun look(self: Job): Boolean {
        val totals = runCatching { TrafficMeter.totals() }.getOrNull()
        synchronized(this) {
            // Stopped or replaced meanwhile: nothing more from this one.
            if (tickJob !== self) return false
            if (!ticks()) {
                tickJob = null
                return false
            }
            if (status != Status.CONNECTED || !foreground) return true
            totals?.let { meter?.look(it) }
            val content = content()
            if (content != shown) {
                runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(content)) }
                    .onSuccess { shown = content }
                    .onFailure { Log.w("Titan", "notification", it) }
            }
            statusServer?.let { probeIfDue(it) }
            return true
        }
    }

    /**
     * The notification's own latency ([ConnectionCheck.probe]) when it has none of [name] under a
     * minute old: at most once a minute, never next to Проверить соединение or a Пинг, nor in
     * the first seconds of a core (the app's own check comes then, and traffic may not flow
     * yet). Under the monitor.
     */
    private fun probeIfDue(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (probeJob?.isActive == true || (probedAt != 0L && now - probedAt < PROBE_EVERY_MS)) return
        if (now - launchedAt < PROBE_SETTLE_MS || !LiveLatency.due(name, now)) return
        if (ConnectionCheck.checking || PingClient.busy) return
        val on = VpnStatus.state.value as? VpnState.Connected ?: return
        if (on.serverName != name) return
        probedAt = now
        probeJob = scope.launch {
            runCatching { ConnectionCheck.probe(on) }.onFailure { Log.w("Titan", "latency probe", it) }
        }
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
        icon = flagBitmap(this, name, onBrand)
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
        /** The VPN notification's channel, the off state's too ([OffNotification], ID 5). */
        internal const val CHANNEL_ID = "vpn_ongoing"
        /** Never to be used again: a channel created under a deleted id gets its old settings back. */
        private val OLD_CHANNEL_IDS = arrayOf("vpn", "vpn_status")
        private const val NOTIFICATION_ID = 1
        /** The colour of the VPN notification (the app's blue). */
        private val BRAND_COLOR = 0xFF1A66FF.toInt()
        /** A quick latency's green: a light one on our blue, else Главная's (light and dark theme). */
        private val GREEN_ON_BRAND = 0xFF86EFAC.toInt()
        private val GREEN_DAY = 0xFF12A150.toInt()
        private val GREEN_NIGHT = 0xFF22C55E.toInt()
        /** The numbers are looked at this often while the screen is on. */
        private const val TICK_MS = 2_000L
        /** The notification's own latency at most this often ([probeIfDue]). */
        private const val PROBE_EVERY_MS = 60_000L
        /** Nor sooner than this after a core start. */
        private const val PROBE_SETTLE_MS = 5_000L
        /** «Потрачено» from a megabyte. */
        private const val SPENT_MIN = 1_048_576L
        /** The Live Update's chip shows ↓ from this speed (bytes a second), else the latency. */
        private const val CHIP_SPEED_MIN = 10 * 1024.0
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
            // «Выключено» waits for it (OffNotification).
            OffNotification.starting()
            ContextCompat.startForegroundService(
                context, Intent(context, TitanVpnService::class.java).setAction(ACTION_START)
            )
        }

        /** Keep-alive brings the VPN back (boot, app update): like [start], with retries. */
        internal fun revive(context: Context) {
            OffNotification.starting()
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

        /**
         * Draws the notification again (its look changed in Настройки, notifications allowed):
         * the VPN's while on, the off state's ([OffNotification]) while off.
         */
        fun reshow(context: Context) {
            val st = VpnStatus.state.value
            if (st !is VpnState.Connected && st != VpnState.Connecting) {
                OffNotification.update(context)
                return
            }
            runCatching { context.startService(Intent(context, TitanVpnService::class.java).setAction(ACTION_RESHOW)) }
        }

        /** The channel is set up in this process: once, not on every redraw. */
        @Volatile private var channelReady = false

        /**
         * The VPN notification's channel, also the off state's: default importance (the main
         * section, not "Silent") without sound, vibration or badge. Earlier channels: "vpn" was
         * of low importance ("Silent", at the bottom), and either may have been lowered since,
         * by hand or by Android. A channel's importance can't be raised and a deleted one comes
         * back as it was, hence a new id. Not while it holds our foreground notification
         * (Android refuses), which only the new one does: tried again then.
         */
        internal fun ensureChannel(context: Context) {
            if (channelReady) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            var ready = true
            for (old in OLD_CHANNEL_IDS) runCatching { nm.deleteNotificationChannel(old) }.onFailure { ready = false }
            runCatching {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
                )
            }.onFailure { ready = false }
            channelReady = ready
        }

        /** The VPN notification's channel is turned off in Android's settings. */
        internal fun channelBlocked(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL_ID)?.importance ==
                NotificationManager.IMPORTANCE_NONE
        }.getOrDefault(false)

        /** NotificationManager.canPostPromotedNotifications (Android 16); null where there is none. */
        private val canPromote by lazy {
            runCatching { NotificationManager::class.java.getMethod("canPostPromotedNotifications") }.getOrNull()
        }

        /**
         * Android 16: Live Updates allowed for the app (the user can turn them off). Looked up by
         * name, so any compile SDK builds it; false where there is no such call.
         */
        internal fun liveUpdatesAllowed(context: Context): Boolean = runCatching {
            canPromote?.invoke(context.getSystemService(NotificationManager::class.java)) as? Boolean
        }.getOrNull() ?: false

        /**
         * The server's round flag for a notification. On our blue ([onBrand]): a white disc, so
         * the shield of a server without a flag still shows; otherwise the shade follows the
         * system theme, so do the disc and the ring.
         */
        internal fun flagBitmap(context: Context, name: String, onBrand: Boolean): Bitmap? {
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val px = (64 * context.resources.displayMetrics.density).roundToInt()
            return runCatching {
                when {
                    onBrand -> Flags.circleBitmap(context, name, px, 0xFFFFFFFF.toInt(), 0x40FFFFFF)
                    night -> Flags.circleBitmap(context, name, px, 0x1AFFFFFF, 0x26FFFFFF)
                    else -> Flags.circleBitmap(context, name, px, 0x14000000, 0x1F000000)
                }
            }.getOrNull()
        }

        /** "2.4М", "140К": the Live Update chip's ↓ (Fmt.speed's number and its unit's letter). */
        private fun shortSpeed(bytesPerSec: Double): String {
            val parts = Fmt.speed(bytesPerSec).split(' ')
            return parts.first() + parts.getOrNull(1)?.take(1).orEmpty()
        }
    }
}
