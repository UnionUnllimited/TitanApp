package com.titanvps.app

import android.app.Application
import android.content.Context
import com.titanvps.app.data.AppSettings
import com.titanvps.app.data.NetworkMonitor
import com.titanvps.app.data.SubscriptionRepository
import com.titanvps.app.vpn.VpnStatus
import com.titanvps.app.widget.TitanWidget
import kotlinx.coroutines.launch
import java.io.File

class TitanApp : Application() {

    lateinit var repository: SubscriptionRepository
        private set

    lateinit var settings: AppSettings
        private set

    lateinit var network: NetworkMonitor
        private set

    /** Directory with geoip.dat / geosite.dat for Xray routing rules. */
    val assetDir: String get() = File(filesDir, "xray").absolutePath

    override fun onCreate() {
        super.onCreate()
        repository = SubscriptionRepository(this)
        settings = AppSettings(this)
        network = NetworkMonitor(this)
        cleanOldGeoCopies()
        // Home-screen widget follows the VPN state and the selected server. Main process
        // only: the ":ping" process has its own (always "off") VpnStatus.
        if (isMainProcess()) kotlinx.coroutines.MainScope().launch {
            kotlinx.coroutines.flow.combine(VpnStatus.state, repository.selectedId) { state, _ -> state }
                .collect { runCatching { TitanWidget.update(this@TitanApp, it) } }
        }
    }

    private fun isMainProcess(): Boolean = runCatching {
        File("/proc/self/cmdline").readText().trim('\u0000').trim() == packageName
    }.getOrDefault(true)

    /** Removes full-size copies left by older versions (now trimmed in GeoFiles). */
    private fun cleanOldGeoCopies() {
        File(assetDir, ".version").takeIf { it.exists() }?.let { marker ->
            marker.delete()
            File(assetDir).listFiles()?.filter { it.name.endsWith(".dat") }?.forEach { it.delete() }
        }
    }

    companion object {
        fun get(context: Context): TitanApp = context.applicationContext as TitanApp
    }
}
