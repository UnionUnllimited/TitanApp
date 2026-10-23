package com.titanvps.app.ui.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

/**
 * Taps the phone answers with: a switch moved, a choice made, something done or refused
 * (iOS Haptics). Newer feedback kinds only where the phone knows them; the system's
 * "Вибрация при касании" setting still applies.
 */
class Haptics internal constructor(private val view: View) {
    fun tap() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    fun medium() = perform(HapticFeedbackConstants.VIRTUAL_KEY)

    fun soft() = perform(HapticFeedbackConstants.CLOCK_TICK)

    fun selection() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK,
    )

    fun success() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun warning() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    fun error() = perform(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS,
    )

    /** A drag went far enough to act on release (a swipe action, the slider). */
    fun arm() = perform(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        else HapticFeedbackConstants.VIRTUAL_KEY,
    )

    fun toggle(on: Boolean) = perform(
        when {
            Build.VERSION.SDK_INT < 34 -> HapticFeedbackConstants.CONTEXT_CLICK
            on -> HapticFeedbackConstants.TOGGLE_ON
            else -> HapticFeedbackConstants.TOGGLE_OFF
        },
    )

    private fun perform(feedback: Int) {
        runCatching { view.performHapticFeedback(feedback) }
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
