package com.titanvps.app.core

import org.json.JSONObject
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * How [PingService] measures one request, apart from Android and the core: it is handed one
 * attempt through an endpoint key ([run]'s measure), so the order, the attempts and the
 * recheck can be tested with a fake one.
 */
internal object PingRun {
    /** The first attempt; a dead server costs this much in the first pass. */
    const val TIMEOUT_SEC = 4
    /** The second attempt, only after an answer (the first handshake is often the slow part). */
    const val SECOND_TIMEOUT_SEC = 3
    /** A list's miss or slow answer, measured again alone once the list is done. */
    const val RECHECK_TIMEOUT_SEC = 6
    const val RECHECK_ABOVE_MS = 1500L

    /**
     * In a list run (not [single]) a miss or an answer slower than [RECHECK_ABOVE_MS] is
     * measured again alone once the list is done, over TCP as over UDP: the first attempt is
     * cold and crowded (6 at once, often on a core just started, beside the phone's own new
     * connections while the VPN is on), and as the last word it showed 3000+ ms or «таймаут»
     * for servers that answered normally on the next Пинг. One server asked for runs alone.
     * A request can ask for no recheck ([recheck]).
     */
    fun needsRecheck(single: Boolean, ms: Long): Boolean = !single && (ms < 0 || ms > RECHECK_ABOVE_MS)

    /**
     * Balancer members a request measures per server: what it asks for ("maxMembers", the
     * watcher's 30 s tick a few) within 1..[cap]; [cap] when it asks for nothing (Главная).
     */
    fun maxMembers(request: JSONObject, cap: Int): Int = request.optInt("maxMembers", cap).coerceIn(1, cap)

    /**
     * Whether a list request has its misses and slow answers measured again ([needsRecheck]):
     * Главная's yes; the watcher's no ("recheck": false), it only needs to know who answered,
     * and held behind Главная's rechecks its results came after its own time limit.
     */
    fun recheck(request: JSONObject): Boolean = request.optBoolean("recheck", true)

    fun bestOf(a: Long, b: Long): Long = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** What a run did, for its line in Диагностика: counts only, nothing about the servers. */
    data class Summary(
        /** Anything answered. */
        val anyOk: Boolean,
        val servers: Int,
        val probes: Int,
        /** Keys whose first pass missed or was slow ([needsRecheck]). */
        val missed: Int = 0,
        /** Of [missed], over UDP. */
        val missedUdp: Int = 0,
        /** Of [missed], measured again (the rest: their servers had a good member already). */
        val rechecked: Int = 0,
        /** Of [rechecked], answered within [RECHECK_ABOVE_MS] the second time. */
        val turnedGood: Int = 0,
        /** Stopped by hand before the end. */
        val stopped: Boolean = false,
    ) {
        fun line(elapsedMs: Long): String =
            "ping: $servers servers, $probes probes, $missed missed or slow on the first pass ($missedUdp over UDP), " +
                "$rechecked measured again, $turnedGood of them good, " +
                String.format(Locale.US, "%.1f s", elapsedMs / 1000.0) + if (stopped) ", stopped" else ""
    }

    /**
     * Every server is measured on its keys (a balancer: its members, the first [maxMembers]);
     * a server's ping is its best member, what the balancer itself would pick. [keysOf]: a
     * server's endpoint keys; [measure]: one attempt through a key within the seconds given,
     * ms or -1 (why: its onError); [result]: a server's ping, once, when all its keys are done
     * or it has a good one and only keys held for the recheck are left; [started]: servers
     * being measured now; [ready]: a key's protocol client still starting; [udp]: keys over
     * UDP (only counted apart in the [Summary]); [recheck]: false, no recheck in a list either
     * (the request's "recheck").
     */
    fun run(
        targets: List<String>,
        keysOf: (String) -> List<String>,
        single: Boolean,
        pool: ExecutorService,
        recheckPool: ExecutorService,
        measure: (key: String, timeoutSec: Int, onError: (String) -> Unit) -> Long,
        result: (id: String, ms: Long, error: String?) -> Unit,
        started: (List<String>) -> Unit = {},
        ready: (key: String) -> Future<*>? = { null },
        udp: Set<String> = emptySet(),
        cancelled: () -> Boolean = { false },
        maxMembers: Int = Int.MAX_VALUE,
        recheck: Boolean = true,
    ): Summary {
        val keys = targets.associateWith { keysOf(it).take(maxMembers) }
        val best = HashMap<String, Long>()
        val remaining = HashMap<String, Int>()
        val startedIds = HashSet<String>()
        val serversByKey = LinkedHashMap<String, MutableList<String>>()
        targets.forEach { id ->
            remaining[id] = keys.getValue(id).size
            keys.getValue(id).forEach { serversByKey.getOrPut(it) { mutableListOf() }.add(id) }
        }
        targets.filter { remaining[it] == 0 }.forEach { result(it, -1L, "нет адреса сервера") }
        val lock = Any()
        /** Why a member last failed: a server that got no answer at all says why. */
        val lastError = HashMap<String, String>()
        /** Servers whose result went out: one each, a later recheck of a shared key only counts down. */
        val sent = HashSet<String>()
        /** A server's keys held for the recheck at the end. */
        val held = HashMap<String, MutableSet<String>>()
        // Keys held for the recheck in the order of the servers asked for: their first-pass result and why.
        val firstRun = Collections.synchronizedMap(LinkedHashMap<String, Long>())
        val recheckError = Collections.synchronizedMap(HashMap<String, String>())
        /**
         * Sends [id]'s result once every key is in, or once it has a good one and only held keys
         * are left: a balancer (АВТО, Безлимитные) whose dead member is also its own country row
         * doesn't spin until that row's recheck at the very end (call under [lock]).
         */
        fun finish(id: String) {
            val ms = best[id] ?: -1L
            val left = remaining.getValue(id)
            if ((left == 0 || ms >= 0 && left == held[id].orEmpty().size) && sent.add(id)) {
                result(id, ms, if (ms < 0) lastError[id] else null)
            }
        }
        fun report(key: String, r: Long, error: String? = null) = synchronized(lock) {
            for (id in serversByKey.getValue(key)) {
                if (r >= 0 && (best[id] ?: -1L).let { it < 0 || r < it }) best[id] = r
                if (r < 0 && error != null) lastError[id] = error
                remaining[id] = remaining.getValue(id) - 1
                held[id]?.remove(key)
                finish(id)
            }
        }
        fun hold(key: String, r: Long, error: String?) = synchronized(lock) {
            error?.let { recheckError[key] = it }
            firstRun[key] = r
            for (id in serversByKey.getValue(key)) {
                held.getOrPut(id) { HashSet() } += key
                finish(id)
            }
        }
        /** Every server using [key] already has a good result (e.g. another balancer member). */
        fun settled(key: String) = synchronized(lock) { serversByKey.getValue(key).all { (best[it] ?: -1L) >= 0 } }

        /** One attempt; if it answered, a second one and the better of the two. */
        fun measureKey(key: String, onError: (String) -> Unit): Long {
            val first = measure(key, TIMEOUT_SEC, onError)
            return if (first < 0) first else bestOf(first, measure(key, SECOND_TIMEOUT_SEC, onError))
        }

        val missed = AtomicInteger()
        val missedUdp = AtomicInteger()
        // Keys go into the pool in the order of the servers asked for, so the list fills top-down.
        serversByKey.map { (key, ids) ->
            pool.submit {
                if (cancelled()) return@submit
                synchronized(lock) {
                    val fresh = ids.filter { startedIds.add(it) }
                    if (fresh.isNotEmpty()) started(fresh)
                }
                // Through a protocol client: once it is up (it didn't start: no answer).
                ready(key)?.let { start ->
                    if (runCatching { start.get(30, TimeUnit.SECONDS) }.isFailure) {
                        report(key, -1L, "клиент протокола не запустился")
                        return@submit
                    }
                }
                var error: String? = null
                val r = measureKey(key) { error = it }
                val again = needsRecheck(single || !recheck, r)
                if (again) {
                    missed.incrementAndGet()
                    if (key in udp) missedUdp.incrementAndGet()
                }
                // A bad result waits for the recheck at the end, unless its servers are done anyway.
                if (again && !settled(key)) hold(key, r, error) else report(key, r, error)
            }
        }.forEach { runCatching { it.get() } }
        val summary = Summary(false, targets.size, serversByKey.size, missed.get(), missedUdp.get())
        if (cancelled()) return summary.copy(anyOk = best.isNotEmpty(), stopped = true)
        // A few at a time, one attempt each; keep the better of the two runs. Not needed when
        // every server using it already has a good result (e.g. another balancer member).
        val (needed, done) = firstRun.toList().partition { (key, _) -> !settled(key) }
        done.forEach { (key, first) -> report(key, first, recheckError[key]) }
        val rechecked = AtomicInteger()
        val turnedGood = AtomicInteger()
        needed.map { (key, first) ->
            recheckPool.submit {
                if (cancelled()) return@submit
                var error: String? = null
                val again = measure(key, RECHECK_TIMEOUT_SEC) { error = it }
                rechecked.incrementAndGet()
                if (again in 0..RECHECK_ABOVE_MS) turnedGood.incrementAndGet()
                report(key, bestOf(first, again), error ?: recheckError[key])
            }
        }.forEach { runCatching { it.get() } }
        return summary.copy(
            anyOk = best.isNotEmpty(), rechecked = rechecked.get(), turnedGood = turnedGood.get(), stopped = cancelled(),
        )
    }
}
