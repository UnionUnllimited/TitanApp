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

    fun setExcluded(packageName: String, excluded: Boolean) {
        val next = if (excluded) _excludedApps.value + packageName else _excludedApps.value - packageName
        prefs.edit().putStringSet(KEY_EXCLUDED, next).apply()
        _excludedApps.value = next
    }

    fun reset() {
        prefs.edit().clear().apply()
        _excludedApps.value = emptySet()
    }

    private companion object {
        const val KEY_EXCLUDED = "excluded_apps"
    }
}
