package com.titanvps.app.ui.kit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A tab to open from outside the screens (a notification, a reminder): set by MainActivity,
 * taken by TitanScreen.
 */
object AppNav {
    const val HOME = "home"
    const val SUBSCRIPTION = "subscription"
    const val PROFILE = "profile"

    private val pending = MutableStateFlow<String?>(null)

    val pendingTab: StateFlow<String?> = pending.asStateFlow()

    fun open(tab: String) {
        pending.value = tab
    }

    /** The screen opened it. */
    fun consume() {
        pending.value = null
    }
}
