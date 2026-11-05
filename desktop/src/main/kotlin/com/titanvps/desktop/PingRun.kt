package com.titanvps.desktop

import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * How [PingEngine] measures one request, apart from the core and the UI: it is handed one
 * attempt through an endpoint key ([run]'s measure), so the order, the attempts and the
 * recheck can be tested with a fake one. The same rules as Android's PingRun (plain JVM:
 * no Compose, no xray.exe).
 */
internal object PingRun {
    /** The first attempt; a dead server costs this much in the first pass. */
    const val TIMEOUT_SEC = 4
    /** The second attempt, only after an answer (the first handshake is often the slow part). */
    const val SECOND_TIMEOUT_SEC = 3
    /** A list's miss or slow answer, measured again once the list is done. */
    const val RECHECK_TIMEOUT_SEC = 6
    const val RECHECK_ABOVE_MS = 1500L
    /** The recheck: two at a time, as a ping by hand (alone, a server that is up answers). */
    const val RECHECK_PARALLEL = 2
    /**
     * A host that failed this often without ever answering is likely down: its other probes
     * stop waiting for one another ([run]).
     */
    const val HOST_GIVE_UP = 2

    /**
     * In a list run (not [single]) a miss or an answer slower than [RECHECK_ABOVE_MS] is
     * measured again once the list is done ([RECHECK_PARALLEL] at a time), over TCP as over
     * UDP: the first attempt is cold and crowded (6 at once, often on a core just started),
     * and as the last word it showed 3000+ ms or «таймаут» for servers that answered normally
     * on the next Пинг. One server asked for runs alone.
     * A request can ask for no recheck ([recheck]); then only UDP gets a second chance
     * ([run]).
     */
    fun needsRecheck(single: Boolean, ms: Long): Boolean = !single && (ms < 0 || ms > RECHECK_ABOVE_MS)

    /**
     * Balancer members a request measures per server: what it asks for ([asked], Android's
     * watcher asks for a few) within 1..[cap]; [cap] when it asks for nothing (Главная).
     */
    fun maxMembers(asked: Int?, cap: Int): Int = (asked ?: cap).coerceIn(1, cap)

    fun bestOf(a: Long, b: Long): Long = when {
        a < 0 -> b
        b < 0 -> a
        else -> minOf(a, b)
    }

    /** What a run did, for its line in Технический режим (ping.log): counts only, nothing about the servers. */
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
        /** Hosts whose probes waited for one another (one at a time per host), in either pass. */
        val gatedHosts: Int = 0,
        /** Hosts given up as down ([HOST_GIVE_UP] misses, no answer) in either pass. */
        val gaveUp: Int = 0,
    ) {
        fun line(elapsedMs: Long): String =
            "ping: $servers servers, $probes probes, $missed missed or slow on the first pass ($missedUdp over UDP), " +
                "$rechecked measured again, $turnedGood of them good, " +
                "one at a time on $gatedHosts hosts, $gaveUp given up, " +
                String.format(Locale.US, "%.1f s", elapsedMs / 1000.0) + if (stopped) ", stopped" else ""
    }

    /** What the one-probe-per-host rule did in a pass: hosts held to it, hosts given up as down. */
    private class Gate(val waited: Set<String>, val down: Set<String>)

    /**
     * Every server is measured on its keys (a balancer: its members, the first [maxMembers]);
     * in a list a server's ping is its best member, what the balancer itself would pick; in a
     * [single] request its first member that answers (the rest aren't started). [keysOf]: a
     * server's endpoint keys; [measure]: one attempt through a key within the seconds given,
     * ms or -1 (why: its onError); [result]: a server's ping, once, when all its keys are done
     * or it has a good one and only keys held for the recheck are left (a single request: or
     * it has a good one); [started]: servers being measured now; [ready]: a key's protocol
     * client still starting; [udp]: keys over UDP; [recheck]: false, no recheck at the end of
     * a list either, only a bad result over UDP of a server measured on that key alone is
     * measured once more right away.
     *
     * [hosts]: the host a key dials first (none: no rule for it). A host gets one probe at a
     * time, in both passes and in a single request too, until it has failed [HOST_GIVE_UP]
     * times without ever answering: servers behind one entry that doesn't take several new
     * connections at once all timed out pinged together, yet answered one by one (as on iOS).
     * Unlike iOS, the recheck remembers the hosts that answered in the first pass: an entry
     * whose first servers measured again are dead is still up, and given up it took the rest
     * of its servers all at once again.
     * [parallel]: keys measured at once in the first pass (the list pool's size; the recheck:
     * [RECHECK_PARALLEL]), so a key whose host was busy starts as soon as it's free, ahead of
     * those below it.
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
        hosts: Map<String, String> = emptyMap(),
        parallel: Int = Int.MAX_VALUE,
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
         * doesn't spin until that row's recheck at the very end. A single request sends it at its
         * first good one (as iOS): its members behind one entry go one at a time, and waiting for
         * all of them would add up every member's time (call under [lock]).
         */
        fun finish(id: String) {
            val ms = best[id] ?: -1L
            val left = remaining.getValue(id)
            if ((left == 0 || ms >= 0 && (single || left == held[id].orEmpty().size)) && sent.add(id)) {
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
        /** Hosts that answered in this run: never given up. */
        val answered = HashSet<String>()
        // Keys start in the order of the servers asked for, so the list fills top-down.
        val firstPass = gated(serversByKey.keys.toList(), hosts, parallel, pool, cancelled, answered) { key ->
            val ids = serversByKey.getValue(key)
            if (cancelled()) return@gated null
            // A single request's server already shown: the members still waiting for their host
            // aren't started (nothing about the host either).
            if (single && synchronized(lock) { ids.all { it in sent } }) return@gated null
            synchronized(lock) {
                val fresh = ids.filter { startedIds.add(it) }
                if (fresh.isNotEmpty()) started(fresh)
            }
            // Through a protocol client: once it is up (it didn't start: no answer, and nothing
            // about its host).
            ready(key)?.let { start ->
                if (runCatching { start.get(30, TimeUnit.SECONDS) }.isFailure) {
                    report(key, -1L, "клиент протокола не запустился")
                    return@gated null
                }
            }
            var error: String? = null
            var r = measureKey(key) { error = it }
            // No recheck at the end (a request that asks for none): a miss or slow answer over
            // UDP (Hysteria, TUIC: often just the crowd) is measured once more alone at once, on
            // this thread. Only for a server of its own: a balancer has its other members, and
            // each dead one measured again would add up.
            if (!recheck && !single && key in udp && (r < 0 || r > RECHECK_ABOVE_MS) &&
                serversByKey.getValue(key).any { keys.getValue(it).size == 1 }
            ) {
                r = bestOf(r, measure(key, RECHECK_TIMEOUT_SEC) { error = it })
            }
            val again = needsRecheck(single || !recheck, r)
            if (again) {
                missed.incrementAndGet()
                if (key in udp) missedUdp.incrementAndGet()
            }
            // A bad result waits for the recheck at the end, unless its servers are done anyway.
            if (again && !settled(key)) hold(key, r, error) else report(key, r, error)
            r >= 0
        }
        val summary = Summary(
            false, targets.size, serversByKey.size, missed.get(), missedUdp.get(),
            gatedHosts = firstPass.waited.size, gaveUp = firstPass.down.size,
        )
        if (cancelled()) return summary.copy(anyOk = best.isNotEmpty(), stopped = true)
        // Two at a time, one attempt each; keep the better of the two runs. Not needed when
        // every server using it already has a good result (e.g. another balancer member).
        val (needed, done) = firstRun.toList().partition { (key, _) -> !settled(key) }
        done.forEach { (key, first) -> report(key, first, recheckError[key]) }
        val firstOf = needed.toMap()
        val rechecked = AtomicInteger()
        val turnedGood = AtomicInteger()
        // One per host here too, as by hand; the first pass's misses don't count against a host.
        val recheckPass = gated(needed.map { it.first }, hosts, RECHECK_PARALLEL, recheckPool, cancelled, answered) { key ->
            if (cancelled()) return@gated null
            var error: String? = null
            val again = measure(key, RECHECK_TIMEOUT_SEC) { error = it }
            rechecked.incrementAndGet()
            if (again in 0..RECHECK_ABOVE_MS) turnedGood.incrementAndGet()
            report(key, bestOf(firstOf.getValue(key), again), error ?: recheckError[key])
            again >= 0
        }
        return summary.copy(
            anyOk = best.isNotEmpty(), rechecked = rechecked.get(), turnedGood = turnedGood.get(), stopped = cancelled(),
            gatedHosts = (firstPass.waited + recheckPass.waited).size, gaveUp = (firstPass.down + recheckPass.down).size,
        )
    }

    /**
     * Runs [work] on [keys] in [pool], in that order, at most [parallel] at once and one at a
     * time per host ([hosts]; a key without one isn't held): a key whose host is busy waits,
     * the keys below it go ahead. Once a host has failed [HOST_GIVE_UP] times here and never
     * answered ([answered]: hosts that did, in this run; updated), its keys start without
     * waiting for one another (as iOS: they're measured, not failed; one dead server behind
     * a live entry isn't enough). [work]: whether the key answered, null when it tells nothing
     * about the host. Stopped: nothing new starts; returns once what started is done.
     */
    private fun gated(
        keys: List<String>,
        hosts: Map<String, String>,
        parallel: Int,
        pool: ExecutorService,
        cancelled: () -> Boolean,
        answered: MutableSet<String>,
        work: (String) -> Boolean?,
    ): Gate {
        val gate = ReentrantLock()
        val finished = gate.newCondition()
        val pending = keys.toMutableList()
        val busy = HashMap<String, Int>()
        val fails = HashMap<String, Int>()
        val down = HashSet<String>()
        val waited = HashSet<String>()
        var running = 0
        gate.withLock {
            while (true) {
                val next = pending.iterator()
                while (running < parallel && !cancelled() && next.hasNext()) {
                    val key = next.next()
                    val host = hosts[key]
                    if (host != null && host !in down && (busy[host] ?: 0) > 0) {
                        waited += host
                        continue
                    }
                    next.remove()
                    if (host != null) busy[host] = (busy[host] ?: 0) + 1
                    running++
                    pool.submit {
                        var ok: Boolean? = null
                        try {
                            ok = work(key)
                        } finally {
                            gate.withLock {
                                running--
                                if (host != null) {
                                    busy[host] = busy.getValue(host) - 1
                                    if (ok == true) {
                                        answered += host
                                        down -= host
                                    } else if (ok == false && host !in answered) {
                                        fails[host] = (fails[host] ?: 0) + 1
                                        if (fails.getValue(host) >= HOST_GIVE_UP) down += host
                                    }
                                }
                                finished.signal()
                            }
                        }
                    }
                }
                if (running == 0 && (pending.isEmpty() || cancelled())) break
                finished.await()
            }
        }
        return Gate(waited, down)
    }
}

/**
 * Which measurement may still change the servers on screen (as iPhone's ping-all hold). Пинг
 * starts a list run and overtakes the one before; Стоп and Выйти stop whatever is measuring
 * then (the engine skips its queued probes). Its probes already in flight still end, up to
 * ~7 s later (longer behind a protocol client), by then often under a newer Пинг: a list run
 * stopped or overtaken writes nothing more, no «таймаут» for what it didn't reach and clears
 * no spinners. One server's ping keeps a result that comes after Стоп (it was measured), but
 * gives no «таймаут» for what Стоп cut short. On the UI thread.
 */
internal class PingRuns {
    /** One measurement: its list run (null: one server) and how many stops came before it. */
    class Ticket internal constructor(val run: Int?, internal val stops: Int)

    private var latestRun = 0
    private var stops = 0

    /** Пинг: a new list run; any earlier one is overtaken. */
    fun startList(): Ticket = Ticket(++latestRun, stops)

    /** One server's ping (also while a list run goes on). */
    fun startOne(): Ticket = Ticket(null, stops)

    /** Стоп, Выйти: everything measuring now is stopped. */
    fun stop() { stops++ }

    /** Stopped since [t] began: what it didn't reach keeps its old value (or none), no spinner comes back. */
    fun stopped(t: Ticket): Boolean = stops != t.stops

    /** [t]'s results may still land: a list run while it is the latest and not stopped; one server's always. */
    fun owns(t: Ticket): Boolean = t.run == null || (t.run == latestRun && !stopped(t))

    /**
     * [t] may still set and clear spinners and give «таймаут» to what it didn't reach: not once
     * stopped (Стоп cleared every spinner; those on the rows since are a newer run's).
     */
    fun spins(t: Ticket): Boolean = owns(t) && !stopped(t)

    /** No newer Пинг came after [t]'s: its end ends «Стоп» on the button. */
    fun isLatest(t: Ticket): Boolean = t.run == latestRun
}
