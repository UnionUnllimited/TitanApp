package com.titanvps.app.vpn

/**
 * Decisions around «Обходы» on mobile data, without Android (times in ms are passed in):
 * when silent servers mean whitelist mode, when the restrictions count as lifted, and how
 * often we may switch to a bypass on our own, so we never flap between the two. [switches]:
 * our own switches to a bypass, [returnedAt]: our last own way back (0: none); both saved
 * by [BypassReturn].
 */
class BypassPolicy(switches: List<Long> = emptyList(), returnedAt: Long = 0L) {

    /** What to do once whitelist mode is confirmed. */
    sealed interface Decision {
        /** Move to the fastest bypass. */
        data object Switch : Decision
        /** Only offer it: the notification with «Переключить на обход». */
        data object Offer : Decision
        /** Just back from a bypass: look again in [ms]. */
        data class Wait(val ms: Long) : Decision
        /**
         * [MAX_SWITCHES] of our own this hour: only the offer for now, and look again in
         * [retryInMs], once the oldest of them is an hour old.
         */
        data class Capped(val retryInMs: Long) : Decision
    }

    /** What to do on a bypass once the restrictions look lifted. */
    enum class Back { RETURN, OFFER, NONE }

    private val switches = switches.toMutableList()

    var returnedAt: Long = returnedAt
        private set

    /** Our own switches within the last hour (what is kept). */
    @Synchronized
    fun switches(now: Long): List<Long> {
        prune(now)
        return switches.toList()
    }

    /**
     * Whitelist mode confirmed: a switch with «Автопереход на обходы» on, unless we came
     * back less than [COOLDOWN_MS] ago (then later) or already switched [MAX_SWITCHES]
     * times this hour (then the offer, and a switch again once the hour allows it).
     */
    @Synchronized
    fun onWhitelist(now: Long, autoBypass: Boolean): Decision {
        if (!autoBypass) return Decision.Offer
        prune(now)
        // Kept switches are less than an hour old: always a wait > 0.
        if (switches.size >= MAX_SWITCHES) return Decision.Capped(switches.min() + HOUR_MS - now)
        val since = now - returnedAt
        // A clock set back counts as long ago.
        if (returnedAt > 0 && since in 0L until COOLDOWN_MS) return Decision.Wait(COOLDOWN_MS - since)
        return Decision.Switch
    }

    @Synchronized
    fun recordSwitch(now: Long) {
        prune(now)
        switches += now
    }

    @Synchronized
    fun recordReturn(now: Long) {
        returnedAt = now
    }

    private fun prune(now: Long) {
        switches.removeAll { (now - it) !in 0L until HOUR_MS }
    }

    /** Checks in a row: true once [needed] of them said yes; a no starts over. */
    class Streak(private val needed: Int) {
        private var count = 0

        fun add(yes: Boolean): Boolean {
            count = if (yes) count + 1 else 0
            return count >= needed
        }

        fun reset() {
            count = 0
        }
    }

    companion object {
        /** Misses of the current server in a row before the network is looked at. */
        const val MISSES = 2
        /** Checks in a row that must find the restrictions lifted. */
        const val LIFTED_CHECKS = 2
        /** No switch of our own this soon after our own way back. */
        const val COOLDOWN_MS = 3 * 60_000L
        /** Our own switches per hour; beyond that only the offer. */
        const val MAX_SWITCHES = 3
        const val HOUR_MS = 60 * 60_000L

        /**
         * The current server is silent: whitelist mode only when no other regular server
         * answers either, a whitelisted Russian site opens directly and no foreign one does
         * (if one does, the server is the problem, not the network).
         */
        fun whitelisted(othersAnswered: Boolean, russianOpens: Boolean, foreignOpens: Boolean): Boolean =
            !othersAnswered && russianOpens && !foreignOpens

        /** On a bypass: a foreign site opens directly and the regular server answers. */
        fun lifted(foreignOpens: Boolean, serverAnswered: Boolean): Boolean = foreignOpens && serverAnswered

        /**
         * [movedByUs]: the app moved the user to this bypass (there is a way back saved);
         * [offered]: the way back was offered on this connection already.
         */
        fun onLifted(movedByUs: Boolean, autoBypass: Boolean, offered: Boolean): Back = when {
            movedByUs && autoBypass -> Back.RETURN
            offered -> Back.NONE
            else -> Back.OFFER
        }
    }
}
