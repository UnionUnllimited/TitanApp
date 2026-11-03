package com.titanvps.app.core

import android.os.SystemClock

/**
 * The latency the VPN notification shows next to «Подключено» (iOS: the Live Activity's): the
 * newest warm one of Проверить соединение ([ConnectionCheck.once]) and the notification's own
 * probe ([ConnectionCheck.probe]), on the server it was measured on. Not a server's Пинг (a
 * fresh connection's time, the proxy's handshake included). A failed check, or one that
 * measured and got no number, leaves none; a check that didn't measure leaves it as it is.
 * Shown only on the same server, at most [MAX_AGE_MS] old and under [ConnectionCheck.SLOW_MS].
 * Each core start and the VPN going off forget it ([clear]): another network, or a new
 * connection, isn't what it measured. Times are elapsedRealtime.
 */
object LiveLatency {

    private val book = Book()

    /** A measurement begins: when, later than any before it. */
    fun begin(): Long = book.begin(SystemClock.elapsedRealtime())

    /**
     * The outcome of the measurement begun at [startedAt]: [ms] on [server], or none (null). One
     * begun before the newest recorded is dropped; a [probe] also when anything began after it.
     */
    fun record(startedAt: Long, server: String, ms: Long?, probe: Boolean = false) =
        book.record(startedAt, server, ms, SystemClock.elapsedRealtime(), probe)

    /** None any more; measurements begun before this are dropped too. */
    fun clear() = book.clear(SystemClock.elapsedRealtime())

    /** The number to show on [server] at [now]; null: none. */
    fun shown(server: String, now: Long): Long? = book.shown(server, now)

    /** No latency of [server] under [PROBE_AFTER_MS] old at [now]: the probe may measure one. */
    fun due(server: String, now: Long): Boolean = book.due(server, now)

    /** [ms] on [server] (its name, with the flag) measured at [measuredAt]; begun at [startedAt]. */
    data class Value(val server: String, val ms: Long, val measuredAt: Long, val startedAt: Long)

    /** The rules, apart from the clock (tests). */
    class Book {
        private var value: Value? = null
        /** The start of the newest measurement recorded, a number or none. */
        private var newest = 0L
        /** The start of the last measurement begun. */
        private var lastStart = 0L

        @Synchronized
        fun begin(now: Long): Long {
            lastStart = maxOf(now, lastStart + 1)
            return lastStart
        }

        @Synchronized
        fun record(startedAt: Long, server: String, ms: Long?, now: Long, probe: Boolean = false) {
            if (startedAt < newest || (probe && startedAt != lastStart)) return
            newest = startedAt
            value = ms?.let { Value(server, it, now, startedAt) }
        }

        @Synchronized
        fun clear(now: Long) {
            value = null
            newest = begin(now)
        }

        @Synchronized
        fun shown(server: String, now: Long): Long? {
            val v = value ?: return null
            if (v.server != server || now - v.measuredAt > MAX_AGE_MS) return null
            return v.ms.takeIf { it in 0 until ConnectionCheck.SLOW_MS }
        }

        @Synchronized
        fun due(server: String, now: Long): Boolean {
            val v = value ?: return true
            return v.server != server || now - v.measuredAt >= PROBE_AFTER_MS
        }
    }

    /** Older than this, none is shown. */
    const val MAX_AGE_MS = 120_000L
    /** A latency this old is measured again (at most once a minute in all, TitanVpnService). */
    const val PROBE_AFTER_MS = 60_000L
}
