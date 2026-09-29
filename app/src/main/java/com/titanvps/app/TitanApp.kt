package com.titanvps.app

import android.app.Application
import android.content.Context
import com.titanvps.app.data.AppSettings
import com.titanvps.app.data.SubscriptionRepository
import java.io.File

class TitanApp : Application() {

    lateinit var repository: SubscriptionRepository
        private set

    lateinit var settings: AppSettings
        private set

    /** Directory with geoip.dat / geosite.dat for Xray routing rules. */
    val assetDir: String get() = File(filesDir, "xray").absolutePath

    override fun onCreate() {
        super.onCreate()
        repository = SubscriptionRepository(this)
        settings = AppSettings(this)
        copyGeoAssets()
    }

    /** Copies bundled *.dat files once per app version. */
    private fun copyGeoAssets() {
        val dir = File(assetDir).apply { mkdirs() }
        val marker = File(dir, ".version")
        if (marker.exists() && marker.readText() == BuildConfig.VERSION_CODE.toString()) return
        assets.list("")?.filter { it.endsWith(".dat") }?.forEach { name ->
            assets.open(name).use { input -> File(dir, name).outputStream().use { input.copyTo(it) } }
        }
        marker.writeText(BuildConfig.VERSION_CODE.toString())
    }

    companion object {
        fun get(context: Context): TitanApp = context.applicationContext as TitanApp
    }
}
