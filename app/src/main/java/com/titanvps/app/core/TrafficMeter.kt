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

    /** TrafficStats.UNSUPPORTED. */
    private const val UNSUPPORTED = -1L
}
