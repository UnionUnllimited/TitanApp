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

    /** A fake ping core: each key answers from its script, one value per attempt (-1 = no answer). */
    private class Fake(script: Map<String, List<Long>>) {
        private val left = script.mapValues { ArrayDeque(it.value) }
        /** (key, seconds given) per attempt, in order. */
        val calls: MutableList<Pair<String, Int>> = Collections.synchronizedList(mutableListOf())
        /** Attempts and results in order: "measure k" / "result id". */
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val results = ConcurrentHashMap<String, Pair<Long, String?>>()

        fun measure(key: String, timeoutSec: Int, onError: (String) -> Unit): Long {
            calls += key to timeoutSec
            log += "measure $key"
            val r = synchronized(left) { left.getValue(key).removeFirstOrNull() } ?: error("$key: no attempt left")
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
        // Dead member first: it waits for the end of the list, then needs no recheck.
        val fake = Fake(mapOf("d" to listOf(-1L), "g" to listOf(200L, 190L), "t" to listOf(300L, 300L)))
        run(fake, mapOf("BAL" to listOf("d", "g"), "T" to listOf("t")))
        assertEquals(190L to null, fake.results["BAL"])
        assertEquals(listOf(PingRun.TIMEOUT_SEC), fake.callsOf("d"))
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
            "ping: 3 servers, 3 probes, 2 missed or slow on the first pass (1 over UDP), 2 measured again, 1 of them good, 12.3 s",
            summary.line(12_345),
        )
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
    }
}
