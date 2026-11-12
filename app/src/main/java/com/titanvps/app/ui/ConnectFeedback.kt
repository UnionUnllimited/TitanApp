package com.titanvps.app.ui

import com.titanvps.app.vpn.VpnState

/**
 * What the user's own Вкл / Выкл on Главная came to, to be felt once: connected, off, or failed
 * (TitanScreen plays it). Only a connect or an off asked for in the app counts, and only while
 * the app stays on screen ([forget] when it goes). What the VPN does by itself — a reconnect,
 * «Не отключать VPN», another network, the switch from a bypass on Wi-Fi, a new key connecting —
 * and the tile, the widget, the notification or a shortcut are never felt: nothing asked here.
 * Times are elapsedRealtime, passed in.
 */
internal class ConnectFeedback {
    enum class Asked { CONNECT, DISCONNECT }

    enum class Outcome { CONNECTED, DISCONNECTED, FAILED }

    private var asked: Asked? = null
    private var askedAt = 0L

    fun expect(what: Asked, now: Long) {
        asked = what
        askedAt = now
    }

    /** Not to be felt any more: the app left the screen, or the connect didn't start. */
    fun forget() {
        asked = null
    }

    /** The VPN's state as it changes: the outcome to feel now, or null. */
    fun onState(state: VpnState, now: Long): Outcome? {
        val what = asked ?: return null
        // Too late to be this one's outcome.
        if (now - askedAt > if (what == Asked.CONNECT) CONNECT_WITHIN_MS else DISCONNECT_WITHIN_MS) {
            asked = null
            return null
        }
        val outcome = when (what) {
            Asked.CONNECT -> when (state) {
                is VpnState.Connected -> Outcome.CONNECTED
                is VpnState.Error -> Outcome.FAILED
                // Off on the way: a failed connect goes through it to its error.
                else -> return null
            }
            Asked.DISCONNECT -> when (state) {
                VpnState.Disconnected -> Outcome.DISCONNECTED
                // Off anyway: nothing more to feel.
                is VpnState.Error -> null
                else -> return null
            }
        }
        asked = null
        return outcome
    }

    private companion object {
        /** A connect can wait for the VPN permission and a slow network. */
        const val CONNECT_WITHIN_MS = 60_000L
        const val DISCONNECT_WITHIN_MS = 15_000L
    }
}
