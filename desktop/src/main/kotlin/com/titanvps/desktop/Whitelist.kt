package com.titanvps.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Proxy
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Is the internet in "whitelist" mode (Russian mobile networks): Russian sites open, the rest
 * of the world doesn't. Only then the bypass servers are worth their GB. Checked directly,
 * past the VPN (the app's own traffic never goes into it), on small favicons only.
 */
object Whitelist {
    private val RU = listOf("https://ya.ru/favicon.ico", "https://vk.com/favicon.ico")
    private val WORLD = listOf("https://www.google.com/favicon.ico", "https://www.cloudflare.com/favicon.ico")

    private val client = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }

    /** true: whitelist mode; false: the internet is normal; null: nothing answers (offline). */
    fun detect(): Boolean? {
        val all = (RU + WORLD).associateWith { url -> pool.submit(Callable { reachable(url) }) }
        fun ok(url: String) = runCatching { all.getValue(url).get(10, TimeUnit.SECONDS) }.getOrDefault(false)
        return when {
            WORLD.any(::ok) -> false
            RU.any(::ok) -> true
            else -> null
        }
    }

    private fun reachable(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code in 200..499 }
    }.getOrDefault(false)
}
