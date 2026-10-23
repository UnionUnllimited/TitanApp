package com.titanvps.app.vpn

import android.annotation.SuppressLint
import android.content.Context

/**
 * The way back from a bypass the app itself moved the user to: the regular server he was
 * on and the bypass we switched to. Kept with [KeepAlive]'s flags, so a revive or a killed
 * process keeps it; [BypassPolicy]'s history of our own switches lives there too.
 */
object BypassReturn {

    data class Saved(val returnTo: String, val bypassId: String)

    private const val KEY_RETURN_TO = "bypass_return_to"
    private const val KEY_BYPASS_ID = "bypass_id"
    private const val KEY_SWITCHES = "bypass_switches"
    private const val KEY_RETURNED_AT = "bypass_returned_at"

    fun load(context: Context): Saved? {
        val prefs = KeepAlive.prefs(context)
        val returnTo = prefs.getString(KEY_RETURN_TO, null) ?: return null
        val bypassId = prefs.getString(KEY_BYPASS_ID, null) ?: return null
        return Saved(returnTo, bypassId)
    }

    /** Written at once: a process killed right after the switch still knows the way back. */
    @SuppressLint("ApplySharedPref")
    fun save(context: Context, returnTo: String, bypassId: String) {
        KeepAlive.prefs(context).edit().putString(KEY_RETURN_TO, returnTo).putString(KEY_BYPASS_ID, bypassId).commit()
    }

    @SuppressLint("ApplySharedPref")
    fun clear(context: Context) {
        val prefs = KeepAlive.prefs(context)
        if (!prefs.contains(KEY_RETURN_TO) && !prefs.contains(KEY_BYPASS_ID)) return
        prefs.edit().remove(KEY_RETURN_TO).remove(KEY_BYPASS_ID).commit()
    }

    fun policy(context: Context): BypassPolicy {
        val prefs = KeepAlive.prefs(context)
        val switches = prefs.getString(KEY_SWITCHES, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        return BypassPolicy(switches, prefs.getLong(KEY_RETURNED_AT, 0L))
    }

    fun savePolicy(context: Context, policy: BypassPolicy, now: Long) {
        KeepAlive.prefs(context).edit()
            .putString(KEY_SWITCHES, policy.switches(now).joinToString(","))
            .putLong(KEY_RETURNED_AT, policy.returnedAt)
            .apply()
    }
}
