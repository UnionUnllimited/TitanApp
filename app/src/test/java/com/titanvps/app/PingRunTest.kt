package com.titanvps.app

import com.titanvps.app.core.PingRun
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PingRunTest {

    private val pools = mutableListOf<ExecutorService>()

    @After
    fun shutdown() = pools.forEach { it.shutdownNow() }

    /** Single-threaded by default: the keys are measured in order, so each case is the same every time. */
    private fun pool(threads: Int = 1) = Executors.newFixedThreadPool(threads).also { pools += it }

    /**
     * A fake ping core: each key answers from its script, one value per attempt (-1 = no answer).
     * An attempt that [takes] time is counted in flight meanwhile, under its host ([hosts], else
     * the key itself), in the first pass or in the recheck (its 6 s attempts).
     */
    private class Fake(
        script: Map<String, List<Long>>,
        private val hosts: Map<String, String> = emptyMap(),
        private val takes: (String) -> Long = { 0L },
    ) {
        private val left = script.mapValues { ArrayDeque(it.value) }
        private val inFlight = HashMap<String, Int>()
        /** Most attempts in flight at once: "first <host>" / "recheck <host>", "first *" / "recheck *" for all. */
        val peak = ConcurrentHashMap<String, Int>()
        /** "start k" / "end k" of each attempt that takes time, in order. */
        val spans: MutableList<String> = Collections.synchronizedList(mutableListOf())
        /** (key, seconds given) per attempt, in order. */
        val calls: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())
        /** Attempts and results in order: "measure k" / "result id". */
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val results = ConcurrentHashMap<String, Pair<Long, String?>>()

        fun measure(key: String, timeoutSec: Int, onError: (String) -> Unit): Long {
            calls += key to timeoutSec
            log += "measure $key"
            val r = synchronized(left) { left.getValue(key).removeFirstOrNull() } ?: error("$key: no attempt left")
            val ms = takes(key)
            if (ms > 0) {
                val phase = if (timeoutSec == PingRun.RECHECK_TIMEOUT_SEC) "recheck" else "first"
                val names = listOf("$phase ${hosts[key] ?: key}", "$phase *")
                synchronized(inFlight) {
                    spans += "start $key"
                    names.forEach { n -> inFlight[n] = (inFlight[n] ?: 0) + 1; peak.merge(n, inFlight.getValue(n), ::maxOf) }
                }
                Thread.sleep(ms)
                synchronized(inFlight) {
                    spans += "end $key"
                    names.forEach { n -> inFlight[n] = inFlight.getValue(n) - 1 }
                }
            }
            if (r < 0) onError("таймаут")
            return r
        }

        fun result(id: String, ms: Long, error: String?) {
            log += "result $id"
            results[id] = ms to error
        }

        fun callsOf(key: String) = calls.filter { it.first == key }.map { it.second }
    }

    private fun run(
        fake: Fake,
        servers: Map<String, List<String>>,
        single: Boolean = false,
        udp: Set<String> = emptySet(),
        cancelled: () -> Boolean = { false },
        threads: Int = 1,
    ): PingRun.Summary = PingRun.run(
        targets = servers.keys.toList(),
        keysOf = { servers.getValue(it) },
        single = single,
        pool = pool(threads),
        recheckPool = pool(),
        measure = fake::measure,
        result = fake::result,
        udp = udp,
        cancelled = cancelled,
    )

    @Test
    fun aListMeasuresMissesAndSlowAnswersAgainOverTcpToo() {
        // A list: a miss or an answer over 1.5 s is measured again, whatever the transport.
        assertTrue(PingRun.needsRecheck(single = false, ms = -1))
        assertTrue(PingRun.needsRecheck(single = false, ms = 3200))
        assertTrue(PingRun.needsRecheck(single = false, ms = PingRun.RECHECK_ABOVE_MS + 1))
        assertFalse(PingRun.needsRecheck(single = false, ms = PingRun.RECHECK_ABOVE_MS))
        assertFalse(PingRun.needsRecheck(single = false, ms = 300))
        // One server asked for (a row tap, the watcher's tick) runs alone already.
        assertFalse(PingRun.needsRecheck(single = true, ms = -1))
        assertFalse(PingRun.needsRecheck(single = true, ms = 3200))
    }

    @Test
    fun bestOfKeepsTheAnswer() {
        assertEquals(150L, PingRun.bestOf(3200, 150))
        assertEquals(180L, PingRun.bestOf(-1, 180))
        assertEquals(3200L, PingRun.bestOf(3200, -1))
        assertEquals(-1L, PingRun.bestOf(-1, -1))
    }

    @Test
    fun aCrowdedTcpMissIsMeasuredAgainAlone() {
        // The cold first attempt got nothing in 4 s; alone at the end it answers.
        val fake = Fake(mapOf("a" to listOf(-1L, 180L), "b" to listOf(300L, 280L)))
        val summary = run(fake, mapOf("A" to listOf("a"), "B" to listOf("b")))
        assertEquals(180L to null, fake.results["A"])
        assertEquals(280L to null, fake.results["B"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("a"))
        // A good answer: the usual two attempts, no recheck.
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC), fake.callsOf("b"))
        assertTrue(summary.anyOk)
        assertEquals(1, summary.missed)
        assertEquals(1, summary.rechecked)
        assertEquals(1, summary.turnedGood)
    }

    @Test
    fun aSlowAnswerShowsTheBetterRun() {
        // 3.2 s, then nothing within the 3 s second attempt: 3200 stood before; the recheck gives 150.
        val fake = Fake(mapOf("a" to listOf(3200L, -1L, 150L)))
        run(fake, mapOf("A" to listOf("a")))
        assertEquals(150L to null, fake.results["A"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("a"))
    }

    @Test
    fun aSlowRecheckKeepsTheFirstRun() {
        val fake = Fake(mapOf("a" to listOf(2000L, 2500L, 2600L)))
        val summary = run(fake, mapOf("A" to listOf("a")))
        assertEquals(2000L to null, fake.results["A"])
        assertEquals(1, summary.rechecked)
        assertEquals(0, summary.turnedGood)
    }

    @Test
    fun aDeadServerCostsTwoAttemptsAndSaysWhy() {
        val fake = Fake(mapOf("a" to listOf(-1L, -1L)))
        val summary = run(fake, mapOf("A" to listOf("a")))
        assertEquals(-1L to "таймаут", fake.results["A"])
        // 4 s in the list, 6 s alone at the end: nothing more.
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("a"))
        assertFalse(summary.anyOk)
    }

    @Test
    fun oneServerAskedForIsNotMeasuredAgain() {
        val fake = Fake(mapOf("a" to listOf(-1L), "b" to listOf(3200L, 3100L)))
        run(fake, mapOf("A" to listOf("a")), single = true)
        assertEquals(-1L to "таймаут", fake.results["A"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("a"))
        run(fake, mapOf("B" to listOf("b")), single = true)
        assertEquals(3100L to null, fake.results["B"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC), fake.callsOf("b"))
    }

    @Test
    fun aBalancerWithAGoodMemberDoesNotWaitForItsDeadOnes() {
        // Dead member first: held, but the row goes out with its good member, and no recheck.
        val fake = Fake(mapOf("d" to listOf(-1L), "g" to listOf(200L, 190L), "t" to listOf(300L, 300L)))
        run(fake, mapOf("BAL" to listOf("d", "g"), "T" to listOf("t")))
        assertEquals(190L to null, fake.results["BAL"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("d"))
        assertTrue(fake.log.indexOf("result BAL") < fake.log.indexOf("measure t"))
        // Good member first: the dead one is settled at once, the row doesn't wait for the rest.
        val fake2 = Fake(mapOf("g" to listOf(200L, 190L), "d" to listOf(-1L), "t" to listOf(300L, 300L)))
        run(fake2, mapOf("BAL" to listOf("g", "d"), "T" to listOf("t")))
        assertEquals(190L to null, fake2.results["BAL"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake2.callsOf("d"))
        assertTrue(fake2.log.indexOf("result BAL") < fake2.log.indexOf("measure t"))
    }

    @Test
    fun aBalancerWithNoGoodMemberIsMeasuredAgain() {
        val fake = Fake(mapOf("m1" to listOf(-1L, 250L), "m2" to listOf(3500L, -1L, -1L)))
        val summary = run(fake, mapOf("BAL" to listOf("m1", "m2")))
        assertEquals(250L to null, fake.results["BAL"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("m1"))
        assertEquals(2, summary.rechecked)
        assertEquals(1, summary.turnedGood)
    }

    @Test
    fun aSharedKeyIsMeasuredAgainForTheServerThatNeedsIt() {
        // The same node in a balancer (which has another good member) and as its own row.
        val fake = Fake(mapOf("k1" to listOf(200L, 210L), "k2" to listOf(-1L, 400L)))
        run(fake, mapOf("BAL" to listOf("k1", "k2"), "ROW" to listOf("k2")))
        assertEquals(200L to null, fake.results["BAL"])
        assertEquals(400L to null, fake.results["ROW"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("k2"))
    }

    @Test
    fun manyAtOnceGetEveryResult() {
        val script = (0 until 40).associate { i -> "k$i" to if (i % 3 == 0) listOf(-1L, 100L + i) else listOf(500L + i, 400L + i) }
        val fake = Fake(script)
        val summary = run(fake, script.keys.associate { "S-$it" to listOf(it) }, threads = 6)
        assertEquals(40, fake.results.size)
        script.keys.forEachIndexed { i, k -> assertEquals(if (i % 3 == 0) 100L + i else 400L + i, fake.results.getValue("S-$k").first) }
        assertEquals(14, summary.missed)
        assertEquals(14, summary.rechecked)
        assertEquals(14, summary.turnedGood)
    }

    @Test
    fun stoppedAfterTheFirstPassMeasuresNothingMore() {
        val stop = AtomicBoolean(false)
        val fake = Fake(mapOf("a" to listOf(-1L), "b" to listOf(300L, 290L)))
        val summary = PingRun.run(
            targets = listOf("A", "B"), keysOf = { mapOf("A" to listOf("a"), "B" to listOf("b")).getValue(it) }, single = false,
            pool = pool(), recheckPool = pool(),
            measure = { key, sec, onError -> fake.measure(key, sec, onError).also { if (key == "b") stop.set(true) } },
            result = fake::result, cancelled = { stop.get() },
        )
        // A stopped row keeps its old value: no result, no recheck.
        assertEquals(null, fake.results["A"])
        assertEquals(290L to null, fake.results["B"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("a"))
        assertTrue(summary.stopped)
        assertTrue(summary.anyOk)
    }

    @Test
    fun noAddressAndAClientThatDidNotStart() {
        val fake = Fake(mapOf("p" to emptyList()))
        PingRun.run(
            targets = listOf("EMPTY", "PLUGIN"), keysOf = { if (it == "PLUGIN") listOf("p") else emptyList() }, single = false,
            pool = pool(), recheckPool = pool(), measure = fake::measure, result = fake::result,
            ready = { CompletableFuture.failedFuture<Unit>(IllegalStateException("exited")) },
        )
        assertEquals(-1L to "нет адреса сервера", fake.results["EMPTY"])
        assertEquals(-1L to "клиент протокола не запустился", fake.results["PLUGIN"])
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun theListFillsTopDown() {
        val started = Collections.synchronizedList(mutableListOf<String>())
        val fake = Fake(mapOf("a" to listOf(100L, 100L), "b" to listOf(100L, 100L), "c" to listOf(100L, 100L)))
        PingRun.run(
            targets = listOf("C", "A", "B"), keysOf = { listOf(it.lowercase()) }, single = false,
            pool = pool(), recheckPool = pool(), measure = fake::measure, result = fake::result,
            started = { started += it },
        )
        assertEquals(listOf("C", "A", "B"), started)
        assertEquals(listOf("measure c", "measure c", "result C", "measure a"), fake.log.take(4))
    }

    @Test
    fun theRunsLineHasCountsOnly() {
        val fake = Fake(mapOf("u" to listOf(-1L, 120L), "t" to listOf(3300L, 3100L, -1L), "g" to listOf(90L, 80L)))
        val summary = run(fake, mapOf("U" to listOf("u"), "T" to listOf("t"), "G" to listOf("g")), udp = setOf("u"))
        assertEquals(PingRun.Summary(anyOk = true, servers = 3, probes = 3, missed = 2, missedUdp = 1, rechecked = 2, turnedGood = 1), summary)
        assertEquals(
            "ping: 3 servers, 3 probes, 2 missed or slow on the first pass (1 over UDP), 2 measured again, 1 of them good, " +
                "one at a time on 0 hosts, 0 given up, 12.3 s",
            summary.line(12_345),
        )
        assertTrue(summary.copy(gatedHosts = 2, gaveUp = 1).line(1_000).contains(", one at a time on 2 hosts, 1 given up, 1.0 s"))
        assertTrue(summary.copy(stopped = true).line(1_000).endsWith("1.0 s, stopped"))
    }

    @Test
    fun aRequestAsksForItsMembers() {
        // Главная asks for nothing: every member up to the service's cap.
        assertEquals(30, PingRun.maxMembers(JSONObject(), 30))
        // The watcher: a few.
        assertEquals(3, PingRun.maxMembers(JSONObject().put("maxMembers", 3), 30))
        assertEquals(1, PingRun.maxMembers(JSONObject().put("maxMembers", 0), 30))
        assertEquals(30, PingRun.maxMembers(JSONObject().put("maxMembers", 99), 30))
        // Главная's lists are measured again; the watcher asks for no recheck.
        assertTrue(PingRun.recheck(JSONObject()))
        assertFalse(PingRun.recheck(JSONObject().put("recheck", false)))
    }

    @Test
    fun aBalancerPastItsFirstMembersAnswersOnlyWithAllOfThem() {
        // АВТО whose first three members are down and the fourth works: the balancer works.
        val servers = mapOf("AUTO" to listOf("d1", "d2", "d3", "g"))
        val fake = Fake(mapOf("d1" to listOf(-1L, -1L), "d2" to listOf(-1L, -1L), "d3" to listOf(-1L, -1L), "g" to listOf(200L, 190L)))
        fun once(maxMembers: Int) = PingRun.run(
            targets = listOf("AUTO"), keysOf = { servers.getValue(it) }, single = true,
            pool = pool(), recheckPool = pool(), measure = fake::measure, result = fake::result,
            maxMembers = maxMembers,
        )
        // The watcher's tick (3 members): a miss, so it has to ask again with every member.
        once(PingRun.maxMembers(JSONObject().put("maxMembers", 3), 30))
        assertEquals(-1L, fake.results.getValue("AUTO").first)
        assertTrue(fake.callsOf("g").isEmpty())
        // Every member (what the way back and the tick's second look ask for): it answers.
        once(PingRun.maxMembers(JSONObject(), 30))
        assertEquals(190L to null, fake.results["AUTO"])
    }

    @Test
    fun aListWithoutRecheckReportsItsMissesFromTheFirstPass() {
        // The watcher's lists: a dead key after its one 4 s attempt, a slow answer as it came,
        // in the list's order; nothing goes to the recheck pool (shut: a submit there would throw).
        val fake = Fake(mapOf("a" to listOf(-1L), "b" to listOf(3200L, 3100L), "c" to listOf(100L, 90L)))
        val shut = pool().also { it.shutdown() }
        val summary = PingRun.run(
            targets = listOf("A", "B", "C"), keysOf = { listOf(it.lowercase()) }, single = false,
            pool = pool(), recheckPool = shut, measure = fake::measure, result = fake::result,
            recheck = false,
        )
        assertEquals(-1L to "таймаут", fake.results["A"])
        assertEquals(3100L to null, fake.results["B"])
        assertEquals(90L to null, fake.results["C"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("a"))
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC), fake.callsOf("b"))
        assertEquals(listOf("measure a", "result A", "measure b", "measure b", "result B"), fake.log.take(5))
        assertEquals(0, summary.rechecked)
    }

    /** A run with no recheck at the end (the watcher's): a submit to the recheck pool would throw. */
    private fun runWithoutRecheck(fake: Fake, servers: Map<String, List<String>>, udp: Set<String>, single: Boolean = false) =
        PingRun.run(
            targets = servers.keys.toList(), keysOf = { servers.getValue(it) }, single = single,
            pool = pool(), recheckPool = pool().also { it.shutdown() }, measure = fake::measure, result = fake::result,
            udp = udp, recheck = false,
        )

    @Test
    fun withoutRecheckAUdpMissGetsItsSecondChanceAtOnce() {
        // UDP: a miss, then 300 alone; a slow answer, then 400; a good one as it came. TCP: one 4 s attempt.
        val fake = Fake(mapOf(
            "u" to listOf(-1L, 300L), "s" to listOf(3200L, 3100L, 400L), "g" to listOf(200L, 190L), "t" to listOf(-1L),
        ))
        val summary = runWithoutRecheck(
            fake, mapOf("U" to listOf("u"), "S" to listOf("s"), "G" to listOf("g"), "T" to listOf("t")), udp = setOf("u", "s", "g"),
        )
        assertEquals(300L to null, fake.results["U"])
        assertEquals(400L to null, fake.results["S"])
        assertEquals(190L to null, fake.results["G"])
        assertEquals(-1L to "таймаут", fake.results["T"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("u"))
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("s"))
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC), fake.callsOf("g"))
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("t"))
        // On the same thread, before the next key: nothing waits for the end of the list.
        assertEquals(listOf("measure u", "measure u", "result U", "measure s"), fake.log.take(4))
        assertEquals(0, summary.rechecked)
        // A dead one: 4 s and 6 s, and why.
        val dead = Fake(mapOf("d" to listOf(-1L, -1L)))
        runWithoutRecheck(dead, mapOf("D" to listOf("d")), udp = setOf("d"))
        assertEquals(-1L to "таймаут", dead.results["D"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), dead.callsOf("d"))
    }

    @Test
    fun withoutRecheckOneServerAndBalancerMembersGetNoSecondChance() {
        // One server asked for (the tick): its one attempt, UDP or not.
        val fake = Fake(mapOf("u" to listOf(-1L)))
        runWithoutRecheck(fake, mapOf("U" to listOf("u")), udp = setOf("u"), single = true)
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("u"))
        // A balancer over UDP whose members are all down (АВТО in whitelist mode): one attempt each,
        // or the watcher's look would run past its time limit.
        val bal = Fake(mapOf("m1" to listOf(-1L), "m2" to listOf(-1L), "m3" to listOf(-1L)))
        runWithoutRecheck(bal, mapOf("BAL" to listOf("m1", "m2", "m3")), udp = setOf("m1", "m2", "m3"))
        assertEquals(-1L to "таймаут", bal.results["BAL"])
        assertEquals(listOf("m1", "m2", "m3").map { it to PingRun.TIMEOUT_SEC }, bal.calls)
        // A member that is also its own row: measured again, for the row.
        val shared = Fake(mapOf("m1" to listOf(200L, 190L), "m2" to listOf(-1L, 350L)))
        runWithoutRecheck(shared, mapOf("BAL" to listOf("m1", "m2"), "ROW" to listOf("m2")), udp = setOf("m1", "m2"))
        assertEquals(190L to null, shared.results["BAL"])
        assertEquals(350L to null, shared.results["ROW"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), shared.callsOf("m2"))
    }

    /** АВТО over a, b, c, and each node also its own row; a misses on the first pass. */
    private val autoAndRows = mapOf("AUTO" to listOf("a", "b", "c"), "A" to listOf("a"), "B" to listOf("b"), "C" to listOf("c"))

    @Test
    fun aBalancerDoesNotWaitForItsMembersRowToBeMeasuredAgain() {
        val fake = Fake(mapOf("a" to listOf(-1L, 150L), "b" to listOf(280L, 300L), "c" to listOf(400L, 410L)))
        run(fake, autoAndRows)
        // АВТО as soon as only a's recheck (for row A) is left, not after it.
        assertTrue(fake.log.indexOf("result AUTO") < fake.log.lastIndexOf("measure a"))
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf("a"))
        assertEquals(280L to null, fake.results["AUTO"])
        // Row A gets the recheck; АВТО's result goes out once.
        assertEquals(150L to null, fake.results["A"])
        assertEquals(1, fake.log.count { it == "result AUTO" })
        // Dead member last: АВТО right after it, before the recheck too.
        val fake2 = Fake(mapOf("b" to listOf(280L, 300L), "a" to listOf(-1L, 150L)))
        run(fake2, mapOf("AUTO" to listOf("b", "a"), "A" to listOf("a")))
        assertEquals(listOf("measure b", "measure b", "measure a", "result AUTO", "measure a", "result A"), fake2.log)
    }

    @Test
    fun aStoppedRunKeepsTheBalancerItMeasured() {
        // Stopped before the recheck: row A keeps its old value, АВТО shows its good member.
        val stop = AtomicBoolean(false)
        val fake = Fake(mapOf("a" to listOf(-1L), "b" to listOf(280L, 300L), "c" to listOf(400L, 410L)))
        val summary = PingRun.run(
            targets = autoAndRows.keys.toList(), keysOf = { autoAndRows.getValue(it) }, single = false,
            pool = pool(), recheckPool = pool(),
            measure = { key, sec, onError -> fake.measure(key, sec, onError).also { if (key == "c") stop.set(true) } },
            result = fake::result, cancelled = { stop.get() },
        )
        assertEquals(280L to null, fake.results["AUTO"])
        assertEquals(null, fake.results["A"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("a"))
        assertTrue(summary.stopped)
    }

    /**
     * A run whose attempts take time on [threads] threads, [parallel] keys at once in the first
     * pass (the list pool's size), and more recheck threads than [PingRun.RECHECK_PARALLEL]: the
     * limits under test are PingRun's own.
     */
    private fun runTimed(
        fake: Fake,
        servers: Map<String, List<String>>,
        hosts: Map<String, String>,
        single: Boolean = false,
        parallel: Int = 6,
        ready: (String) -> java.util.concurrent.Future<*>? = { null },
        started: (List<String>) -> Unit = {},
    ): PingRun.Summary = PingRun.run(
        targets = servers.keys.toList(), keysOf = { servers.getValue(it) }, single = single,
        pool = pool(if (single) 16 else parallel), recheckPool = pool(6), measure = fake::measure, result = fake::result,
        started = started, ready = ready, hosts = hosts, parallel = if (single) Int.MAX_VALUE else parallel,
    )

    /** One row per key, in that order. */
    private fun rows(vararg keys: String) = keys.associate { it.uppercase() to listOf(it) }

    @Test
    fun aHostGetsOneProbeAtATimeAndOtherHostsGoOnBeside() {
        // h1..h4 behind one entry; o1..o4 a host each; u1, u2 with no host known (no rule for them).
        val hosts = mapOf("h1" to "a.example", "h2" to "a.example", "h3" to "a.example", "h4" to "a.example") +
            (1..4).associate { "o$it" to "192.0.2.$it" }
        val keys = listOf("u1", "u2", "h1", "o1", "h2", "o2", "h3", "o3", "h4", "o4")
        val fake = Fake(keys.associateWith { listOf(100L, 90L) }, hosts + mapOf("u1" to "unknown", "u2" to "unknown")) { 40L }
        val summary = runTimed(fake, rows(*keys.toTypedArray()), hosts)
        keys.forEach { assertEquals(90L to null, fake.results[it.uppercase()]) }
        assertEquals(1, fake.peak["first a.example"])
        // The rest beside it, within the list's six at once; the two with no host side by side.
        assertTrue(fake.peak.getValue("first *") in 4..6)
        assertEquals(2, fake.peak["first unknown"])
        assertEquals(1, summary.gatedHosts)
        assertEquals(0, summary.gaveUp)
    }

    @Test
    fun aHostThatFailedTwiceWithoutAnAnswerIsGivenUp() {
        // b: down, every key. a: answers first, then its dead ones. c: one miss, then an answer.
        val hosts = (1..5).associate { "d$it" to "b.example" } + (1..4).associate { "a$it" to "a.example" } +
            (1..4).associate { "c$it" to "192.0.2.3" }
        val fake = Fake(
            (1..5).associate { "d$it" to listOf(-1L, -1L) } +
                mapOf("a1" to listOf(100L, 90L), "a2" to listOf(-1L, 120L), "a3" to listOf(-1L, -1L), "a4" to listOf(-1L, -1L)) +
                mapOf("c1" to listOf(-1L, -1L), "c2" to listOf(100L, 90L), "c3" to listOf(-1L, -1L), "c4" to listOf(-1L, -1L)),
            hosts,
        ) { 40L }
        val keys = (1..5).map { "d$it" } + (1..4).map { "a$it" } + (1..4).map { "c$it" }
        val summary = runTimed(fake, rows(*keys.toTypedArray()), hosts, parallel = 13)
        // b: two misses one after the other, then the other three at once, still measured (not failed).
        val first = fake.spans.filter { it.endsWith("d1") || it.endsWith("d2") || it.endsWith("d3") }.take(5)
        assertEquals(listOf("start d1", "end d1", "start d2", "end d2", "start d3"), first)
        assertEquals(3, fake.peak["first b.example"])
        (1..5).forEach { assertEquals(PingRun.TIMEOUT_SEC, fake.callsOf("d$it").first()) }
        // An entry that answered keeps one at a time, its dead servers too; one miss isn't enough.
        assertEquals(1, fake.peak["first a.example"])
        assertEquals(1, fake.peak["first 192.0.2.3"])
        assertEquals(-1L to "таймаут", fake.results["D3"])
        assertEquals(120L to null, fake.results["A2"])
        assertEquals(90L to null, fake.results["C2"])
        // The recheck counts misses afresh: b given up again, two at a time. a and c answered
        // in the first pass: one at a time still, c's dead ones too.
        assertEquals(1, fake.peak["recheck a.example"])
        assertEquals(1, fake.peak["recheck 192.0.2.3"])
        assertEquals(2, fake.peak["recheck b.example"])
        assertEquals(2, fake.peak["recheck *"])
        assertEquals(3, summary.gatedHosts)
        assertEquals(1, summary.gaveUp)
    }

    @Test
    fun aGivenUpHostThatAnswersIsHeldAgain() {
        // e1, e2 dead first: the entry is given up, e3 and e4 start together. e3 answers, so the
        // entry is up after all: e5 waits for e4 again, as before it was given up.
        val hosts = (1..5).associate { "e$it" to "a.example" }
        val fake = Fake(
            mapOf("e1" to listOf(-1L, -1L), "e2" to listOf(-1L, -1L)) +
                (3..5).associate { "e$it" to listOf(100L, 90L) },
            hosts,
        ) { if (it == "e4") 200L else 20L }
        runTimed(fake, rows("e1", "e2", "e3", "e4", "e5"), hosts, parallel = 2)
        val spans = fake.spans.toList()
        assertTrue(spans.toString(), spans.lastIndexOf("end e4") < spans.indexOf("start e5"))
    }

    @Test
    fun anEntryThatAnsweredIsNotGivenUpInTheRecheck() {
        // h1 answers; h2, h3 dead; h4, h5 slow, then good. The recheck starts with h2 and h3:
        // two misses, yet the entry answered before, so h4 and h5 still go one at a time.
        val hosts = (1..5).associate { "h$it" to "a.example" }
        val fake = Fake(
            mapOf("h1" to listOf(100L, 90L), "h2" to listOf(-1L, -1L), "h3" to listOf(-1L, -1L)) +
                mapOf("h4" to listOf(2000L, 1900L, 100L), "h5" to listOf(2000L, 1900L, 100L)),
            hosts,
        ) { 30L }
        val summary = runTimed(fake, rows("h1", "h2", "h3", "h4", "h5"), hosts)
        assertEquals(listOf("h2", "h3", "h4", "h5"), fake.calls.filter { it.second == PingRun.RECHECK_TIMEOUT_SEC }.map { it.first })
        assertEquals(1, fake.peak["first a.example"])
        assertEquals(1, fake.peak["recheck a.example"])
        assertEquals(100L to null, fake.results["H5"])
        assertEquals(-1L to "таймаут", fake.results["H3"])
        assertEquals(0, summary.gaveUp)
    }

    @Test
    fun theRecheckGoesTwoAtATimeOnePerHost() {
        // Slow on the first pass (2 s), good in the recheck: x1..x3 behind one entry, y1, v1 a
        // host each, u1 none; z1 good at once. x2 and x3 come last in the recheck, one after the other.
        val hosts = mapOf("x1" to "a.example", "x2" to "a.example", "x3" to "a.example", "y1" to "b.example", "v1" to "192.0.2.9")
        val slow = listOf("x1", "x2", "x3", "y1", "v1", "u1")
        val fake = Fake(slow.associateWith { listOf(2000L, 1900L, 100L) } + mapOf("z1" to listOf(100L, 90L)), hosts) { 30L }
        val summary = runTimed(fake, rows(*(slow + "z1").toTypedArray()), hosts)
        slow.forEach { assertEquals(100L to null, fake.results[it.uppercase()]) }
        slow.forEach { assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC, PingRun.RECHECK_TIMEOUT_SEC), fake.callsOf(it)) }
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC), fake.callsOf("z1"))
        // Two at a time (as iOS), whatever the pool; one per host.
        assertEquals(2, fake.peak["recheck *"])
        assertEquals(1, fake.peak["recheck a.example"])
        assertEquals(1, fake.peak["first a.example"])
        assertEquals(6, summary.rechecked)
        assertEquals(6, summary.turnedGood)
    }

    @Test
    fun aKeyWaitingForItsHostGoesBeforeTheRestOfTheList() {
        // Two at a time; A and B behind one entry. B waits for A, then starts before D.
        val hosts = mapOf("a" to "a.example", "b" to "a.example", "c" to "192.0.2.3", "d" to "192.0.2.4")
        val fake = Fake(listOf("a", "b", "c", "d").associateWith { listOf(100L, 90L) }, hosts) { if (it == "c") 150L else 20L }
        val started = Collections.synchronizedList(mutableListOf<String>())
        runTimed(fake, rows("a", "b", "c", "d"), hosts, parallel = 2, started = { started += it })
        // A and C side by side (either may tell first), then B, then D.
        assertEquals(setOf("A", "C"), started.take(2).toSet())
        assertEquals(listOf("B", "D"), started.drop(2))
        assertEquals(1, fake.peak["first a.example"])
    }

    @Test
    fun aSingleRequestHoldsItsMembersToOnePerHostToo() {
        // АВТО tapped: four members behind one entry, one elsewhere beside them (better, but slow
        // to come); no recheck. m1 dead, m2 answers: АВТО is shown with m2 at once (as iOS), m3
        // and m4 aren't started, and it doesn't wait for m5.
        val hosts = (1..4).associate { "m$it" to "a.example" } + mapOf("m5" to "192.0.2.5")
        val fake = Fake(
            mapOf("m1" to listOf(-1L), "m2" to listOf(300L, 280L), "m3" to listOf(200L, 190L), "m4" to listOf(250L, 240L), "m5" to listOf(100L, 90L)),
            hosts,
        ) { if (it == "m5") 300L else 30L }
        runTimed(fake, mapOf("AUTO" to listOf("m1", "m2", "m3", "m4", "m5")), hosts, single = true)
        assertEquals(280L to null, fake.results["AUTO"])
        assertTrue(fake.log.toString(), fake.log.indexOf("result AUTO") < fake.log.lastIndexOf("measure m5"))
        assertEquals(1, fake.log.count { it == "result AUTO" })
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("m1"))
        assertTrue(fake.callsOf("m3").isEmpty())
        assertTrue(fake.callsOf("m4").isEmpty())
        assertEquals(1, fake.peak["first a.example"])
        assertEquals(2, fake.peak["first *"])
        // One server of one node: as before, two attempts.
        val one = Fake(mapOf("k" to listOf(300L, 280L)), mapOf("k" to "a.example")) { 10L }
        runTimed(one, mapOf("K" to listOf("k")), mapOf("k" to "a.example"), single = true)
        assertEquals(280L to null, one.results["K"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC, PingRun.SECOND_TIMEOUT_SEC), one.callsOf("k"))
    }

    @Test
    fun aProtocolClientThatDidNotStartSaysNothingAboutItsHost() {
        // p1, p2: their client didn't start; p3, p4 still one at a time behind their server.
        val hosts = (1..4).associate { "p$it" to "a.example" }
        val fake = Fake(mapOf("p3" to listOf(100L, 90L), "p4" to listOf(100L, 90L)), hosts) { 30L }
        runTimed(
            fake, rows("p1", "p2", "p3", "p4"), hosts,
            ready = { if (it == "p1" || it == "p2") CompletableFuture.failedFuture<Unit>(IllegalStateException("exited")) else null },
        )
        assertEquals(-1L to "клиент протокола не запустился", fake.results["P1"])
        assertEquals(90L to null, fake.results["P4"])
        assertEquals(1, fake.peak["first a.example"])
    }
}
