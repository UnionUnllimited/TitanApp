package com.titanvps.app.data

import android.app.DownloadManager
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
import org.json.JSONObject
import java.io.File

/**
 * Updates from GitHub Releases, downloaded only while our VPN is on: everything goes
 * through Android's DownloadManager, which runs under the system downloads UID and so
 * uses the tunnel (our own app is excluded from it).
 */
class Updater(private val context: Context) {

    data class Release(val versionCode: Int, val versionName: String, val apkUrl: String, val sizeBytes: Long)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val progress: Float) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val dm = context.getSystemService(DownloadManager::class.java)
    private val dir = File(context.getExternalFilesDir(null), "update").apply { mkdirs() }

    fun reset() { _state.value = State.Idle }

    suspend fun check() {
        if (!vpnOn()) { _state.value = State.Error(NEED_VPN); return }
        _state.value = State.Checking
        _state.value = runCatching {
            val file = download(LATEST_URL, "latest.json", visible = false, json = true) { }
            parse(file.readText())
        }.fold(
            onSuccess = { r -> if (r != null && r.versionCode > BuildConfig.VERSION_CODE) State.Available(r) else State.UpToDate },
            onFailure = { State.Error("Не удалось проверить обновление. Попробуйте позже") },
        )
    }

    suspend fun downloadAndInstall(release: Release) {
        if (!vpnOn()) { _state.value = State.Error(NEED_VPN); return }
        _state.value = State.Downloading(0f)
        runCatching {
            val apk = download(release.apkUrl, "titan-vps-${release.versionCode}.apk", visible = true, json = false) {
                _state.value = State.Downloading(it)
            }
            install(apk)
            _state.value = State.Idle
        }.onFailure { e ->
            _state.value = State.Error(
                if (e.message == "install permission") "Разрешите установку из Titan VPS в открывшихся настройках и нажмите «Обновить» ещё раз"
                else "Не удалось скачать обновление. Проверьте подключение и попробуйте ещё раз"
            )
        }
    }

    /** Opens the system installer, asking for the "install unknown apps" permission first if needed. */
    fun install(apk: File) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            throw IllegalStateException("install permission")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun vpnOn() = VpnStatus.state.value is VpnState.Connected

    private suspend fun download(url: String, name: String, visible: Boolean, json: Boolean, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val target = File(dir, name).apply { delete() }
            val request = DownloadManager.Request(Uri.parse(url))
                .setDestinationUri(Uri.fromFile(target))
                .addRequestHeader("User-Agent", "Titan")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .setTitle("Titan VPS")
                .setNotificationVisibility(
                    if (visible) DownloadManager.Request.VISIBILITY_VISIBLE else DownloadManager.Request.VISIBILITY_HIDDEN
                )
                .apply { if (json) addRequestHeader("Accept", "application/vnd.github+json") }
            val id = dm.enqueue(request)
            val deadline = System.currentTimeMillis() + if (json) 30_000 else 15 * 60_000
            try {
                while (true) {
                    dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
                        if (!c.moveToFirst()) error("download gone")
                        when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                            DownloadManager.STATUS_SUCCESSFUL -> return@withContext target
                            DownloadManager.STATUS_FAILED -> error("download failed")
                            else -> {
                                val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                                if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                    if (System.currentTimeMillis() > deadline) error("timeout")
                    // VPN turned off mid-way: stop instead of downloading past the tunnel.
                    if (!vpnOn()) error("vpn off")
                    delay(400)
                }
                @Suppress("UNREACHABLE_CODE") target
            } catch (e: Exception) {
                dm.remove(id)
                throw e
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
        const val NEED_VPN = "Включите VPN — обновление скачивается только через него"
    }
}
