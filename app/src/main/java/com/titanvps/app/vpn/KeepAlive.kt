package com.titanvps.app.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

/**
 * «Не отключать VPN»: whether the user wants the VPN on. Set by every start, cleared by an
 * off by hand (and by a failed first connect), so Android's sticky restart after a kill, a
 * reboot or an app update brings the VPN back only when it was on.
 */
object KeepAlive {
    /** Waits before each new try of a failed restart; then every 30 s while keep-alive is on. */
    val BACKOFF_SEC = listOf(1, 2, 4, 8, 15, 30)

    /** Runtime flags, not settings: [com.titanvps.app.data.AppSettings.reset] keeps them. */
    const val PREFS = "vpn_runtime"
    private const val KEY_WANTED = "wanted"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun wanted(context: Context): Boolean = prefs(context).getBoolean(KEY_WANTED, false)

    /** Written at once: a process killed right after an off by hand must not come back on. */
    @SuppressLint("ApplySharedPref")
    fun setWanted(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_WANTED, on).commit()
    }

    fun shouldRevive(keepAlive: Boolean, wanted: Boolean): Boolean = keepAlive && wanted
}
