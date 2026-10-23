package com.titanvps.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.titanvps.app.TitanApp
import com.titanvps.app.core.PingClient
import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.data.NetworkMonitor
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Detects the Russian mobile "whitelist" mode while connected to a regular server: the
 * server and the other regular ones stop answering, Russian sites still open directly and
 * foreign ones don't. Then the caller switches (or offers to switch) to the fastest bypass
 * server. On a bypass it tells when the restrictions are over. Checks run only on mobile
 * data; [BypassPolicy] holds the rules.
 */
class WhitelistWatcher(private val context: Context) {

    /**
     * Direct requests: our app is left out of its own VPN (addDisallowedApplication in
     * TitanVpnService.establish), so they go straight to the physical network.
     */
    private val direct = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Servers that answered our pings (id → elapsed realtime): the first others to try. */
    private val answered = ConcurrentHashMap<String, Long>()

    /**
     * Suspends until whitelist mode is detected for [current]; returns false if it's not a
     * regular server. Every [CHECK_INTERVAL_MS] the server is pinged; after
     * [BypassPolicy.MISSES] misses in a row the network is looked at, and once more
     * [CONFIRM_AGAIN_MS] later before it counts.
     */
    suspend fun awaitWhitelist(current: Server, all: List<Server>): Boolean {
        if (ServerGroups.groupOf(current, all) != ServerGroups.Group.SERVERS) return false
        val misses = BypassPolicy.Streak(BypassPolicy.MISSES)
        while (true) {
            delay(CHECK_INTERVAL_MS)
            if (!onMobileData()) { misses.reset(); awaitMobile(); continue }
            if (!misses.add(failed(ping(listOf(current)), current))) continue
            if (whitelisted(current, all, pingCurrent = false)) {
                delay(CONFIRM_AGAIN_MS)
                if (onMobileData() && whitelisted(current, all, pingCurrent = true)) return true
            }
            // The server is the problem (or no network at all): two new misses before the next look.
            misses.reset()
        }
    }

    /**
     * On a bypass: returns once the restrictions look lifted, that is on mobile data a
     * foreign site opens directly and [target] (a regular server) answers,
     * [BypassPolicy.LIFTED_CHECKS] checks in a row [RETURN_INTERVAL_MS] apart.
     */
    suspend fun awaitLifted(target: Server) {
        val streak = BypassPolicy.Streak(BypassPolicy.LIFTED_CHECKS)
        while (true) {
            delay(RETURN_INTERVAL_MS)
            if (!onMobileData()) { streak.reset(); awaitMobile(); continue }
            val foreign = foreignSiteReachable()
            // Still whitelisted: the foreign site already failed, no ping.
            val ok = foreign && (ping(listOf(target))[target.id] ?: -1L) >= 0
            if (streak.add(BypassPolicy.lifted(foreign, ok))) return
        }
    }

    /** Fastest answering bypass server (prefers real "обход" entries over info rows). */
    suspend fun bestBypass(all: List<Server>): Server? {
        val bypass = ServerGroups.split(all).toMap()[ServerGroups.Group.BYPASS].orEmpty()
        val candidates = bypass.filter { "обход" in it.name.lowercase() }.ifEmpty { bypass }.take(MAX_CANDIDATES)
        if (candidates.isEmpty()) return null
        val pings = ping(candidates)
        return candidates.filter { (pings[it.id] ?: -1) >= 0 }.minByOrNull { pings.getValue(it.id) }
            ?: candidates.first()
    }

    /**
     * The current server is silent: the network looks whitelisted. Cheapest first: a foreign
     * site that opens means the server is the problem; no Russian site means no network at
     * all; then up to [MAX_OTHERS] other regular servers (with [current] itself again when
     * [pingCurrent]) must all miss.
     */
    private suspend fun whitelisted(current: Server, all: List<Server>, pingCurrent: Boolean): Boolean {
        val foreign = foreignSiteReachable()
        if (foreign) return false
        val russian = russianSiteReachable()
        if (!russian) return false
        val others = others(current, all)
        val probe = if (pingCurrent) listOf(current) + others else others
        // No other node to ask (one server, or a balancer that covers them all): the sites decide.
        val pings = if (probe.isEmpty()) emptyMap<String, Long>() else ping(probe)
        val othersAnswered = probe.any { !failed(pings, it) }
        return BypassPolicy.whitelisted(othersAnswered, russian, foreign)
    }

    /** Only a miss the ping service reported counts: a cancelled or lost measurement doesn't. */
    private fun failed(pings: Map<String, Long>, server: Server): Boolean = pings[server.id]?.let { it < 0 } == true

    /**
     * Up to [MAX_OTHERS] regular servers of one node each, none of [current]'s nodes and no
     * node twice: those that answered our pings lately first, then АВТО's members, then in the
     * subscription's order.
     */
    private fun others(current: Server, all: List<Server>): List<Server> {
        val regular = ServerGroups.split(all).toMap()[ServerGroups.Group.SERVERS].orEmpty()
        val autoNodes = regular.filter { "авто" in it.name.lowercase() }.flatMap(::nodes).toSet()
        val taken = nodes(current).toMutableSet()
        return regular.filter { it.id != current.id }
            .mapNotNull { s -> nodes(s).singleOrNull()?.let { s to it } }
            .sortedWith(
                compareByDescending<Pair<Server, String>> { answered[it.first.id] ?: 0L }
                    .thenByDescending { it.second in autoNodes }
            )
            .filter { (_, node) -> taken.add(node) }
            .take(MAX_OTHERS)
            .map { it.first }
    }

    /** The server's nodes as the ping service tells them apart (a balancer: each member). */
    private fun nodes(server: Server): List<String> =
        XrayConfigs.pingTags(server.xrayJson, server.proxyTag)
            .mapNotNull { XrayConfigs.endpointKey(server.xrayJson, it) }
            .distinct()

    private suspend fun ping(servers: List<Server>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        withTimeoutOrNull(60_000) {
            PingClient.ping(context, servers, servers.map { it.id }).collect { event ->
                if (event is PingClient.Event.Partial) result += event.delays
            }
        }
        val now = SystemClock.elapsedRealtime()
        result.forEach { (id, ms) -> if (ms >= 0) answered[id] = now }
        return result
    }

    private fun russianSiteReachable(): Boolean = RU_PROBES.any(::opens)

    /** All at once: while whitelisted each of them just times out. */
    private suspend fun foreignSiteReachable(): Boolean = coroutineScope {
        FOREIGN_PROBES.map { url -> async(Dispatchers.IO) { opens(url) } }.awaitAll().any { it }
    }

    private fun opens(url: String): Boolean = runCatching {
        direct.newCall(Request.Builder().url(url).head().build()).execute().use { it.code in 200..499 }
    }.getOrDefault(false)

    private fun onMobileData(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        // Our process isn't routed through the VPN: activeNetwork is the underlying one.
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return NetworkMonitor.isMobileLike(caps)
    }

    /** Nothing is checked on Wi-Fi: wait for mobile data. */
    private suspend fun awaitMobile() {
        TitanApp.get(context).network.onMobile.first { it }
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 30_000L
        const val CONFIRM_AGAIN_MS = 20_000L
        const val RETURN_INTERVAL_MS = 2 * 60_000L
        const val MAX_CANDIDATES = 8
        const val MAX_OTHERS = 4
        val RU_PROBES = listOf("https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru")
        val FOREIGN_PROBES = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
            "https://captive.apple.com/hotspot-detect.html",
        )
    }
}
