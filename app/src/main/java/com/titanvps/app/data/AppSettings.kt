package com.titanvps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** User-level settings (not part of the subscription). */
class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _excludedApps = MutableStateFlow(prefs.getStringSet(KEY_EXCLUDED, emptySet())!!.toSet())
    /** Packages that bypass the VPN. */
    val excludedApps: StateFlow<Set<String>> = _excludedApps.asStateFlow()

    private val _theme = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }.getOrDefault(ThemeMode.SYSTEM)
    )
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _theme.value = mode
    }

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(KEY_AUTO_CONNECT, false))
    /** Connect when the app is opened. */
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    fun setAutoConnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
        _autoConnect.value = enabled
    }

    private val _notifications = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))
    /** Alerts such as "mobile internet is restricted". */
    val notifications: StateFlow<Boolean> = _notifications.asStateFlow()

    fun setNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        _notifications.value = enabled
    }

    private val _favorites = MutableStateFlow(prefs.getStringSet(KEY_FAVORITES, emptySet())!!.toSet())
    /** Favorite servers by name (names are stable across subscription updates). */
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    fun toggleFavorite(name: String) {
        val next = if (name in _favorites.value) _favorites.value - name else _favorites.value + name
        prefs.edit().putStringSet(KEY_FAVORITES, next).apply()
        _favorites.value = next
    }

    private val _autoBypass = MutableStateFlow(prefs.getBoolean(KEY_AUTO_BYPASS, true))
    /** Switch to a bypass server automatically when mobile internet goes whitelist-only. */
    val autoBypass: StateFlow<Boolean> = _autoBypass.asStateFlow()

    fun setAutoBypass(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_BYPASS, enabled).apply()
        _autoBypass.value = enabled
    }

    private val _ruAppsBypass = MutableStateFlow(prefs.getBoolean(KEY_RU_APPS, true))
    /** Russian banks/marketplaces/government apps go around the VPN (see [RuApps]). */
    val ruAppsBypass: StateFlow<Boolean> = _ruAppsBypass.asStateFlow()

    fun setRuAppsBypass(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_RU_APPS, enabled).apply()
        _ruAppsBypass.value = enabled
    }

    /** Everything that must bypass the VPN right now. */
    fun bypassPackages(): Set<String> =
        _excludedApps.value + (if (_ruAppsBypass.value) RuApps.PACKAGES else emptySet())

    fun setExcluded(packageName: String, excluded: Boolean) {
        val next = if (excluded) _excludedApps.value + packageName else _excludedApps.value - packageName
        prefs.edit().putStringSet(KEY_EXCLUDED, next).apply()
        _excludedApps.value = next
    }

    fun reset() {
        prefs.edit().clear().apply()
        _excludedApps.value = emptySet()
        _autoBypass.value = true
        _ruAppsBypass.value = true
        _theme.value = ThemeMode.SYSTEM
        _autoConnect.value = false
        _notifications.value = true
        _favorites.value = emptySet()
    }

    private companion object {
        const val KEY_EXCLUDED = "excluded_apps"
        const val KEY_AUTO_BYPASS = "auto_bypass"
        const val KEY_RU_APPS = "ru_apps_bypass"
        const val KEY_THEME = "theme"
        const val KEY_AUTO_CONNECT = "auto_connect"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_FAVORITES = "favorites"
    }
}
