package com.titanvps.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.titanvps.app.BuildConfig
import com.titanvps.app.core.XrayConfigs
import com.titanvps.app.core.XrayCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class SubscriptionRepository(private val context: Context) {

    class SubscriptionException(message: String) : Exception(message)

    private val store = SubscriptionStore(context)
    private val hosts = DeepLinks.parseHosts(BuildConfig.SUB_HOSTS)
    val deepLinks = DeepLinks(hosts)
    val linkFinder = SubscriptionLinkFinder(deepLinks, hosts)

    private val _subscription = MutableStateFlow(store.load()?.let(::withoutHidden))
    val subscription: StateFlow<Subscription?> = _subscription.asStateFlow()

    private val _selectedId = MutableStateFlow(store.selectedServerId)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun select(serverId: String?) {
        store.selectedServerId = serverId
        _selectedId.value = serverId
    }

    /** Selected server, or the first one if nothing (valid) is selected. */
    fun selectedServer(): Server? {
        val sub = _subscription.value ?: return null
        return sub.servers.firstOrNull { it.id == _selectedId.value } ?: sub.servers.firstOrNull()
    }

    fun isStale(): Boolean {
        val sub = _subscription.value ?: return false
        val age = System.currentTimeMillis() - sub.fetchedAt
        return age > TimeUnit.HOURS.toMillis(sub.info.updateIntervalHours.toLong())
    }

    /** Activates the app with a pasted key or a titanvps:// / App Link. */
    suspend fun activate(link: String): Subscription {
        val url = deepLinks.extractSubscriptionUrl(link)
            ?: linkFinder.find(link)
            ?: throw SubscriptionException("Это не ключ TitanVPS")
        return fetch(url)
    }

    suspend fun refresh(): Subscription? {
        val url = _subscription.value?.url ?: return null
        return fetch(url)
    }

    fun logout() {
        store.clear()
        _subscription.value = null
        _selectedId.value = null
    }

    private suspend fun fetch(url: String): Subscription = withContext(Dispatchers.IO) {
        if (!deepLinks.isAllowed(url)) throw SubscriptionException("Недопустимый адрес подписки")

        val (info, body) = downloadWithFallback(url)

        // Full Xray JSON (keeps server-side routing); anything else, or JSON we
        // can't use, goes through libXray's parser (links, base64, Xray JSON nodes).
        val servers = runCatching {
            if (XrayConfigs.isXrayJson(body)) XrayConfigs.serversFromXrayJson(body) else emptyList()
        }.getOrDefault(emptyList()).ifEmpty {
            runCatching { XrayConfigs.serversFromOutbounds(XrayCore.convertShareLinks(XrayConfigs.clean(body))) }
                .getOrElse { throw SubscriptionException("Не удалось разобрать подписку: ${it.message}") }
        }
        if (servers.isEmpty()) throw SubscriptionException("В подписке нет серверов")

        val sub = withoutHidden(Subscription(url, info, servers, System.currentTimeMillis()))
        if (sub.servers.isEmpty()) throw SubscriptionException("В подписке нет серверов")
        store.save(sub)
        _subscription.value = sub
        // Trim geo files now so the first connect doesn't wait for it.
        runCatching {
            com.titanvps.app.core.GeoFiles.prepare(
                context, java.io.File(com.titanvps.app.TitanApp.get(context).assetDir), sub.servers.map { it.xrayJson },
            )
        }
        if (sub.servers.none { it.id == _selectedId.value }) select(sub.servers.first().id)
        sub
    }

    private fun withoutHidden(sub: Subscription) = sub.copy(servers = sub.servers.filterNot(ServerGroups::isHidden))

    /**
     * Tries the key's own host first, then the same path on our other subscription
     * hosts (api1 ↔ api2), so a blocked or down domain doesn't break updates.
     */
    private fun downloadWithFallback(url: String): Pair<SubscriptionInfo, String> {
        var firstError: Exception? = null
        for (candidate in listOf(url) + alternates(url)) {
            try {
                return download(candidate)
            } catch (e: SubscriptionException) {
                if (firstError == null) firstError = e
            }
        }
        throw firstError ?: SubscriptionException("Сервер подписки недоступен")
    }

    /** Same URL on the other allowed hosts. */
    private fun alternates(url: String): List<String> {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return emptyList()
        val host = uri.host?.lowercase() ?: return emptyList()
        return hosts.filter { it != host }.map { url.replaceFirst(uri.rawAuthority, it) }
    }

    private fun download(url: String): Pair<SubscriptionInfo, String> {
        val request = Request.Builder()
            .url(url)
            // TODO: temporary. Switch to our own UA (e.g. "TitanVPS/<version>") once a
            //  Remnawave response rule for it is set up.
            .header("User-Agent", "Xray")
            .header("Accept", "application/json, text/plain, */*")
            // Remnawave HWID device limit headers.
            .header("x-hwid", hwid())
            .header("x-device-os", "Android")
            .header("x-ver-os", Build.VERSION.RELEASE ?: "")
            .header("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .build()

        return try {
            http.newCall(request).execute().use { resp ->
                if (!deepLinks.isAllowed(resp.request.url.toString())) {
                    throw SubscriptionException("Недопустимый адрес подписки")
                }
                when {
                    resp.code == 404 || resp.code == 403 ->
                        throw SubscriptionException("Подписка не найдена или отключена (${resp.code}, ${resp.request.url.host})")
                    !resp.isSuccessful ->
                        throw SubscriptionException("Сервер подписки недоступен (${resp.code}, ${resp.request.url.host})")
                }
                SubscriptionHeaders.parse { resp.header(it) } to resp.body.string()
            }
        } catch (e: IOException) {
            throw SubscriptionException("Нет соединения с сервером подписки")
        }
    }

    @SuppressLint("HardwareIds")
    private fun hwid(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
