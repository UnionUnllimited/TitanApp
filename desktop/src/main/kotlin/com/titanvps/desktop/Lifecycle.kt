package com.titanvps.desktop

import java.util.concurrent.atomic.AtomicInteger

/**
 * Who owns the connection's processes (cores, tunnel, system proxy). Each connect, off and
 * quit takes the next number; work begun under an older one gives up at its next step, and
 * whoever took over stops or replaces what it had started. Starting and stopping run one at a
 * time, so an off never lands in the middle of a connect's step.
 */
class ConnectionGen {
    private val number = AtomicInteger()
    private val lock = Any()

    /** A new owner: everything under an older number gives up. */
    fun next(): Int = number.incrementAndGet()

    fun isCurrent(gen: Int): Boolean = gen == number.get()

    /** [block] under the lock while [gen] is still the owner (also after waiting for the lock); else null. */
    fun <T> ifCurrent(gen: Int, block: () -> T): T? = synchronized(lock) { if (isCurrent(gen)) block() else null }

    /** [block] under the lock whoever owns it (the final shutdown). */
    fun <T> locked(block: () -> T): T = synchronized(lock) { block() }
}

/**
 * A core, protocol client or sing-box that exits after connecting is started again: at once,
 * then after a growing pause (Android's back-off); after the last try the VPN goes off with an
 * error. A connection up for a minute again counts its tries from the start.
 */
object Revive {
    val WAIT_SEC = listOf(0, 1, 2, 4, 8, 15)
    const val STABLE_MS = 60_000L

    /** The pause before try [n] (from 0); null: no more tries. */
    fun waitSec(n: Int): Int? = WAIT_SEC.getOrNull(n)

    /** Tries used so far, as they count for an exit after [upMs] up since the last (re)start. */
    fun triesAfterExit(tries: Int, upMs: Long): Int = if (upMs >= STABLE_MS) 0 else tries
}
