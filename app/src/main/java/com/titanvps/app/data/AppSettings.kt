package com.titanvps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-level settings (not part of the subscription). */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _excludedApps = MutableStateFlow(prefs.getStringSet(KEY_EXCLUDED, emptySet())!!.toSet())
    /** Packages that bypass the VPN. */
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    private val _autoBypass = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BYPASS, true))
    /** Switch to a bypass server automatically when mobile internet goes whitelist-only. */
    val autoBypass: StateFlow<Boolean> = _autoBypass.asStateFlow()

    fun setAutoBypass(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BYPASS, enabled).apply()
        _autoBypass.value = enabled
    }

    fun setExcluded(packageName: String, excluded: Boolean) {
        val next = if (excluded) _excludedApps.value + packageName else _excludedApps.value - packageName
        prefs.edit().putStringSet(KEY_EXCLUDED, next).apply()
        _excludedApps.value = next
    }

    fun reset() {
        prefs.edit().clear().apply()
        _excludedApps.value = emptySet()
        _autoBypass.value = true
    }

    private companion object {
        const val KEY_EXCLUDED = "excluded_apps"
        const val KEY_AUTO_BYPASS = "auto_bypass"
    }
}
