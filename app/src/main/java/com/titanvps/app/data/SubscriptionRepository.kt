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
    val deepLinks = DeepLinks(DeepLinks.parseHosts(BuildConfig.SUB_HOSTS))

    private val _subscription = MutableStateFlow(store.load())
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

    /** null selection = automatic (lowest ping). */
    fun selectedServer(): Server? {
        val sub = _subscription.value ?: return null
        return sub.servers.firstOrNull { it.id == _selectedId.value }
    }

    fun isStale(): Boolean {
        val sub = _subscription.value ?: return false
        val age = System.currentTimeMillis() - sub.fetchedAt
        return age > TimeUnit.HOURS.toMillis(sub.info.updateIntervalHours.toLong())
    }

    /** Activates the app with a link from our bot / site. */
    suspend fun activate(link: String): Subscription {
        val url = deepLinks.extractSubscriptionUrl(link)
            ?: throw SubscriptionException("Ссылка не относится к TitanVPS")
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

        val request = Request.Builder()
            .url(url)
            // Configure a Remnawave response rule for this User-Agent → Xray JSON.
            .header("User-Agent", "TitanVPS/${BuildConfig.VERSION_NAME} (Android)")
            .header("Accept", "application/json, text/plain, */*")
            // Remnawave HWID device limit headers.
            .header("x-hwid", hwid())
            .header("x-device-os", "Android")
            .header("x-ver-os", Build.VERSION.RELEASE ?: "")
            .header("x-device-model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .build()

        val (info, body) = try {
            http.newCall(request).execute().use { resp ->
                if (!deepLinks.isAllowed(resp.request.url.toString())) {
                    throw SubscriptionException("Недопустимый адрес подписки")
                }
                when {
                    resp.code == 404 || resp.code == 403 ->
                        throw SubscriptionException("Подписка не найдена или отключена")
                    !resp.isSuccessful ->
                        throw SubscriptionException("Сервер подписки недоступен (${resp.code})")
                }
                SubscriptionHeaders.parse { resp.header(it) } to resp.body.string()
            }
        } catch (e: IOException) {
            throw SubscriptionException("Нет соединения с сервером подписки")
        }

        val servers = if (XrayConfigs.isXrayJson(body)) {
            XrayConfigs.serversFromXrayJson(body)
        } else {
            XrayConfigs.serversFromOutbounds(XrayCore.convertShareLinks(body))
        }
        if (servers.isEmpty()) throw SubscriptionException("В подписке нет серверов")

        val sub = Subscription(url, info, servers, System.currentTimeMillis())
        store.save(sub)
        _subscription.value = sub
        if (servers.none { it.id == _selectedId.value }) select(null)
        sub
    }

    @SuppressLint("HardwareIds")
    private fun hwid(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
}
