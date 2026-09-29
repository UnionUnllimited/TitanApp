package com.titanvps.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.titanvps.app.core.PingClient
import com.titanvps.app.data.Server
import com.titanvps.app.data.ServerGroups
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Detects the Russian mobile "whitelist" mode while connected to a regular server:
 * the server stops answering, yet Russian sites are reachable directly. Then the
 * caller switches (or offers to switch) to the fastest bypass server.
 */
class WhitelistWatcher(private val context: Context) {

    private val direct = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    /** Suspends until whitelist mode is detected for [current]; returns false if it's not a regular server. */
    suspend fun awaitWhitelist(current: Server, all: List<Server>): Boolean {
        if (ServerGroups.groupOf(current, all) != ServerGroups.Group.SERVERS) return false
        var failures = 0
        while (true) {
            delay(CHECK_INTERVAL_MS)
            if (!onMobileData()) { failures = 0; continue }
            val delayMs = ping(listOf(current))[current.id] ?: -1L
            failures = if (delayMs < 0) failures + 1 else 0
            // Two misses in a row, while a whitelisted Russian site still opens.
            if (failures >= 2 && russianSiteReachable()) return true
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

    private suspend fun ping(servers: List<Server>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        withTimeoutOrNull(60_000) {
            PingClient.ping(context, servers).collect { event ->
                if (event is PingClient.Event.Partial) result += event.delays
            }
        }
        return result
    }

    /** Our app is excluded from the VPN, so this goes straight to the mobile network. */
    private fun russianSiteReachable(): Boolean = RU_PROBES.any { url ->
        runCatching {
            direct.newCall(Request.Builder().url(url).head().build()).execute().use { it.code in 200..499 }
        }.getOrDefault(false)
    }

    private fun onMobileData(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        // Our process isn't routed through the VPN: activeNetwork is the underlying one.
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    private companion object {
        const val CHECK_INTERVAL_MS = 30_000L
        const val MAX_CANDIDATES = 8
        val RU_PROBES = listOf("https://ya.ru", "https://vk.com", "https://www.gosuslugi.ru")
    }
}
