package com.titanvps.desktop

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Downloads the subscription (api1 ↔ api2 fallback) and keeps app state on disk. */
object Repository {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Never through our own system proxy.
        .proxy(java.net.Proxy.NO_PROXY)
        .build()

    private val stateFile = File(AppPaths.dataDir, "state.json")

    fun fetch(url: String): Subscription {
        if (!Links.isAllowed(url)) throw SubscriptionException("Недопустимый адрес подписки")
        var firstError: Exception? = null
        for (candidate in listOf(url) + Links.alternates(url)) {
            try {
                val (info, body) = download(candidate)
                val servers = XrayConfigs.serversFromXrayJson(body).filterNot(ServerGroups::isHidden)
                if (servers.isEmpty()) throw SubscriptionException("В подписке нет серверов")
                return Subscription(url, info, servers, System.currentTimeMillis())
            } catch (e: Exception) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: SubscriptionException("Сервер подписки недоступен")
    }

    private fun download(url: String): Pair<SubscriptionInfo, String> {
        val request = Request.Builder().url(url)
            .header("User-Agent", Config.USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("x-hwid", Hwid.get())
            .header("x-device-os", "Windows")
            .header("x-ver-os", System.getProperty("os.version") ?: "")
            .header("x-device-model", Hwid.model())
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                if (!Links.isAllowed(resp.request.url.toString())) throw SubscriptionException("Недопустимый адрес подписки")
                when {
                    resp.code == 404 || resp.code == 403 ->
                        throw SubscriptionException("Подписка не найдена или отключена (${resp.code}, ${resp.request.url.host})")
                    !resp.isSuccessful -> throw SubscriptionException("Сервер подписки недоступен (${resp.code}, ${resp.request.url.host})")
                }
                SubscriptionHeaders.parse { resp.header(it) } to resp.body.string()
            }
        } catch (e: IOException) {
            throw SubscriptionException("Нет соединения с сервером подписки")
        }
    }

    // ------------------------------------------------------------------ state

    data class State(val subscription: Subscription?, val selectedId: String?, val theme: String)

    fun load(): State {
        val o = runCatching { JSONObject(stateFile.readText()) }.getOrNull() ?: return State(null, null, "system")
        val sub = o.optJSONObject("subscription")?.let { s ->
            val i = s.getJSONObject("info")
            val arr = s.getJSONArray("servers")
            Subscription(
                url = s.getString("url"),
                info = SubscriptionInfo(
                    i.optLong("upload"), i.optLong("download"), i.optLong("total"), i.optLong("expire"),
                    i.optString("supportUrl").ifEmpty { null }, i.optString("webPageUrl").ifEmpty { null },
                    i.optString("announce").ifEmpty { null },
                ),
                servers = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .map { Server(it.getString("id"), it.getString("name"), it.getString("json"), it.getString("tag")) },
                fetchedAt = s.optLong("fetchedAt"),
            )
        }
        return State(sub, o.optString("selected").ifEmpty { null }, o.optString("theme", "system"))
    }

    fun save(state: State) {
        val o = JSONObject().put("theme", state.theme).put("selected", state.selectedId ?: "")
        state.subscription?.let { s ->
            o.put("subscription", JSONObject()
                .put("url", s.url).put("fetchedAt", s.fetchedAt)
                .put("info", JSONObject()
                    .put("upload", s.info.uploadBytes).put("download", s.info.downloadBytes)
                    .put("total", s.info.totalBytes).put("expire", s.info.expireAt)
                    .put("supportUrl", s.info.supportUrl ?: "").put("webPageUrl", s.info.webPageUrl ?: "")
                    .put("announce", s.info.announce ?: ""))
                .put("servers", JSONArray().apply {
                    s.servers.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("json", it.xrayJson).put("tag", it.proxyTag)) }
                }))
        }
        val tmp = File(stateFile.parentFile, "state.json.tmp")
        tmp.writeText(o.toString())
        stateFile.delete()
        tmp.renameTo(stateFile)
    }
}

/** Real ping through Xray: one temporary core, a SOCKS port per node, warm + timed request. */
object Pinger {
    private const val URL = "https://www.gstatic.com/generate_204"
    private val core = XrayProcess("ping")

    /** Calls [onResult] per server id (ms, or -1 for timeout) as soon as it is known. */
    fun pingAll(servers: List<Server>, onResult: (String, Long) -> Unit) {
        data class Probe(val json: String, val tag: String)
        val probes = LinkedHashMap<String, Probe>()
        val serverKeys = servers.map { s ->
            XrayConfigs.pingTags(s.xrayJson, s.proxyTag).mapNotNull { tag ->
                val key = XrayConfigs.endpointKey(s.xrayJson, tag) ?: return@mapNotNull null
                if (key !in probes && probes.size < 200) probes[key] = Probe(s.xrayJson, tag)
                key.takeIf { it in probes }
            }.distinct()
        }
        val keys = probes.keys.toList()
        if (keys.isEmpty()) { servers.forEach { onResult(it.id, -1) }; return }
        val ports = freePorts(keys.size)
        core.start(XrayConfigs.buildPingConfig(keys.map { probes.getValue(it).let { p -> p.json to p.tag } }, ports))
        val best = LongArray(servers.size) { -1 }
        val remaining = IntArray(servers.size) { serverKeys[it].size }
        val byKey = HashMap<String, MutableList<Int>>().also { m ->
            serverKeys.forEachIndexed { i, ks -> ks.forEach { m.getOrPut(it) { mutableListOf() }.add(i) } }
        }
        servers.indices.filter { remaining[it] == 0 }.forEach { onResult(servers[it].id, -1) }
        val lock = Any()
        try {
            val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(keys.size, 16))
            keys.mapIndexed { p, key ->
                pool.submit {
                    var ms = measure(ports[p], 5)
                    if (ms < 0) ms = measure(ports[p], 10)
                    synchronized(lock) {
                        for (i in byKey[key].orEmpty()) {
                            if (ms >= 0 && (best[i] < 0 || ms < best[i])) best[i] = ms
                            if (--remaining[i] == 0) onResult(servers[i].id, best[i])
                        }
                    }
                }
            }.forEach { runCatching { it.get() } }
            pool.shutdown()
        } finally {
            core.stop()
        }
    }

    private fun measure(port: Int, timeoutSec: Long): Long {
        val client = OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", port)))
            .connectTimeout(timeoutSec, TimeUnit.SECONDS)
            .readTimeout(timeoutSec, TimeUnit.SECONDS)
            .callTimeout(timeoutSec + 2, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val request = Request.Builder().url(URL).build()
        return try {
            val warmStart = System.nanoTime()
            client.newCall(request).execute().use { it.body.bytes() }
            val warm = (System.nanoTime() - warmStart) / 1_000_000
            runCatching {
                val start = System.nanoTime()
                client.newCall(request).execute().use { it.body.bytes() }
                (System.nanoTime() - start) / 1_000_000
            }.getOrDefault(warm).coerceAtLeast(1)
        } catch (e: Exception) {
            -1
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }
}
