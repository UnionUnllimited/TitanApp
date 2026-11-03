package com.titanvps.app.core

import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.math.max

/**
 * Speed of the VPN, from the app's own byte counters: the core's sockets carry all of the
 * tunnel's traffic (encrypted, outside the tunnel), plus the app's few own requests and the
 * ping process. Close enough for "↓ 2.4 МБ/с"; nothing in the config changes for it.
 */
object TrafficMeter {

    /** Bytes per second down and up; [atMillis] is SystemClock.elapsedRealtime. */
    data class Sample(val atMillis: Long, val down: Double, val up: Double)

    /** One sample a second while collected; nothing on phones without per-app counters. */
    fun samples(): Flow<Sample> = flow {
        val uid = Process.myUid()
        var rx = TrafficStats.getUidRxBytes(uid)
        var tx = TrafficStats.getUidTxBytes(uid)
        if (rx == UNSUPPORTED || tx == UNSUPPORTED) return@flow
        var at = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            val nowRx = TrafficStats.getUidRxBytes(uid)
            val nowTx = TrafficStats.getUidTxBytes(uid)
            if (nowRx == UNSUPPORTED || nowTx == UNSUPPORTED) return@flow
            val now = SystemClock.elapsedRealtime()
            val seconds = max((now - at) / 1000.0, 0.2)
            emit(Sample(now, max(0L, nowRx - rx) / seconds, max(0L, nowTx - tx) / seconds))
            rx = nowRx
            tx = nowTx
            at = now
        }
    }.flowOn(Dispatchers.IO)

    /** This app's bytes received and sent so far, read at [atMillis] (elapsedRealtime). */
    data class Totals(val rx: Long, val tx: Long, val atMillis: Long)

    /** The counters now; null on phones without per-app counters. */
    fun totals(): Totals? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == UNSUPPORTED || tx == UNSUPPORTED) return null
        return Totals(rx, tx, SystemClock.elapsedRealtime())
    }

    /**
     * One connection's numbers for the VPN notification, from the counters at its connect
     * ([start]): what it has used (a core restart keeps the connection, and the count), and
     * the speed between two looks a moment apart (none across a longer gap, the screen off).
     */
    class Session(start: Totals) {
        private var last = start
        /** Down + up since the connect. */
        var used = 0L
            private set
        private var speed: Sample? = null

        fun look(now: Totals) {
            val ms = now.atMillis - last.atMillis
            // A moment after the last: its numbers still hold.
            if (ms in 0 until MIN_GAP_MS) return
            // Counters that went back (reset) count from where they are now.
            val down = max(0L, now.rx - last.rx)
            val up = max(0L, now.tx - last.tx)
            used += down + up
            speed = if (ms in MIN_GAP_MS..MAX_GAP_MS) Sample(now.atMillis, down * 1000.0 / ms, up * 1000.0 / ms) else null
            last = now
        }

        /** Bytes per second down and up, if read under [MAX_GAP_MS] before [nowMillis]. */
        fun speed(nowMillis: Long): Sample? = speed?.takeIf { nowMillis - it.atMillis < MAX_GAP_MS }
    }

    /** TrafficStats.UNSUPPORTED. */
    private const val UNSUPPORTED = -1L
    /** Two looks closer than this: the speed isn't read again. */
    private const val MIN_GAP_MS = 500L
    /** Two looks further apart: no speed (it would be an average over the gap). */
    private const val MAX_GAP_MS = 5_000L
}
