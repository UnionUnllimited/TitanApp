package com.titanvps.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.titanvps.app.BuildConfig
import com.titanvps.app.vpn.VpnState
import com.titanvps.app.vpn.VpnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Updates from GitHub Releases, downloaded only while our VPN is on and only through
 * it (via the core's local proxy, see [com.titanvps.app.vpn.LocalProxy]).
 */
class Updater(private val context: Context, private val requestVpn: () -> Unit) {

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val sizeBytes: Long)

    sealed interface State {
        data object Idle : State
        data object ConnectingVpn : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        /** "Install unknown apps" isn't allowed yet; asked before downloading anything. */
        data class NeedPermission(val release: Release) : State
        data class Downloading(val progress: Float) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val dir = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }

    fun reset() { _state.value = State.Idle }

    suspend fun check() {
        if (!ensureVpn()) return
        _state.value = State.Checking
        _state.value = runCatching {
            val file = download(LATEST_URL, "latest.json", json = true) { }
            parse(file.readText())
        }.fold(
            onSuccess = { r -> if (r != null && r.versionCode > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate },
            onFailure = { State.Error("Не удалось проверить обновление. Попробуйте позже") },
        )
    }

    /** Newer release than the installed one, or null. Needs the VPN; no UI state. */
    suspend fun findNewer(): Release? {
        if (!vpnOn()) return null
        val r = parse(download(LATEST_URL, "latest-bg.json", json = true) { }.readText())
        return r?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    }

    suspend fun downloadAndInstall(release: Release) {
        // Permission first, so nothing is downloaded in vain.
        if (!canInstall()) {
            openInstallPermission()
            _state.value = State.NeedPermission(release)
            return
        }
        if (!ensureVpn()) return
        _state.value = State.Downloading(0f)
        runCatching {
            val apk = download(release.apkUrl, "titan-vps-${release.versionCode}.apk", json = false) {
                _state.value = State.Downloading(it)
            }
            install(apk)
            _state.value = State.Idle
        }.onFailure {
            _state.value = State.Error("Не удалось скачать обновление. Проверьте подключение и попробуйте ещё раз")
        }
    }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26) return
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Turns the VPN on by itself if needed and waits for it; updates only go through it. */
    private suspend fun ensureVpn(): Boolean {
        if (vpnOn()) return true
        _state.value = State.ConnectingVpn
        requestVpn()
        val ok = withTimeoutOrNull(45_000) {
            while (!vpnOn()) {
                if (VpnStatus.state.value is VpnState.Error) return@withTimeoutOrNull false
                delay(300)
            }
            true
        } ?: false
        if (!ok) _state.value = State.Error(NEED_VPN)
        return ok
    }

    private fun vpnOn() = VpnStatus.state.value is VpnState.Connected && VpnStatus.localProxy != null

    /**
     * Downloads through the core's local HTTP proxy, i.e. through the server: our app is
     * excluded from its own tunnel, so a direct request would bypass the VPN.
     */
    private suspend fun download(url: String, name: String, json: Boolean, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val lp = VpnStatus.localProxy ?: error("vpn off")
            val credential = okhttp3.Credentials.basic(lp.user, lp.password)
            val client = okhttp3.OkHttpClient.Builder()
                .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", lp.port)))
                // Preemptive and on 407: Xray's HTTP inbound wants Basic auth.
                .proxyAuthenticator { _, response ->
                    response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
                .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder().url(url)
                .header("User-Agent", "Titan")
                .apply { if (json) header("Accept", "application/vnd.github+json") }
                .build()
            val target = File(dir, name).apply { delete() }
            val tmp = File(dir, "$name.part").apply { delete() }
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val total = resp.body.contentLength()
                    resp.body.byteStream().use { input ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReport = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0 && System.currentTimeMillis() - lastReport > 250) {
                                    lastReport = System.currentTimeMillis()
                                    onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                                }
                                // VPN turned off mid-way: stop instead of going around it.
                                if (!vpnOn()) error("vpn off")
                            }
                        }
                    }
                }
                if (!tmp.renameTo(target)) error("rename failed")
                target
            } catch (e: Exception) {
                android.util.Log.w("Titan", "update download failed: $url", e)
                tmp.delete()
                throw e
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }

    /** GitHub "latest release": tag android-<versionCode>, one APK per CPU type. */
    private fun parse(body: String): Release? {
        val o = JSONObject(body)
        val code = o.optString("tag_name").removePrefix("android-").toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        val byName = (0 until assets.length()).map { assets.getJSONObject(it) }.associateBy { it.optString("name") }
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val preferred = when {
            abi.startsWith("arm64") -> "titan-vps-arm64.apk"
            abi.startsWith("armeabi") -> "titan-vps-armv7.apk"
            else -> "titan-vps-universal.apk"
        }
        val asset = byName[preferred] ?: byName["titan-vps-universal.apk"] ?: return null
        return Release(code, "0.1.$code", asset.getString("browser_download_url"), asset.optLong("size"))
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/UnionUnllimited/TitanApp/releases/latest"
        const val NEED_VPN = "Не удалось включить VPN. Обновление скачивается только через VPN — подключитесь и попробуйте ещё раз"
    }
}
