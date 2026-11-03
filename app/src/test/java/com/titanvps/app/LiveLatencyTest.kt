package com.titanvps.app

import com.titanvps.app.core.LiveLatency
import com.titanvps.app.core.TrafficMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VPN notification's numbers: its latency (LiveLatency) and its traffic (TrafficMeter.Session). */
class LiveLatencyTest {

    private val fi = "🇫🇮 Финляндия"
    private val de = "🇩🇪 Германия"

    @Test
    fun theNewestByStartStays() {
        val book = LiveLatency.Book()
        val older = book.begin(1_000)
        val newer = book.begin(2_000)
        book.record(newer, fi, 50, now = 2_500)
        // Began first, ended last: dropped.
        book.record(older, fi, 80, now = 3_000)
        assertEquals(50L, book.shown(fi, 3_000))
    }

    @Test
    fun startsAreApart() {
        val book = LiveLatency.Book()
        val a = book.begin(1_000)
        val b = book.begin(1_000)
        assertTrue(b > a)
    }

    @Test
    fun aFailureLeavesNone() {
        val book = LiveLatency.Book()
        book.record(book.begin(1_000), fi, 45, now = 1_500)
        book.record(book.begin(2_000), fi, null, now = 2_500)
        assertNull(book.shown(fi, 2_500))
    }

    @Test
    fun aProbeGivesWayToACheckBegunMeanwhile() {
        val book = LiveLatency.Book()
        book.record(book.begin(500), fi, 45, now = 600)
        val probe = book.begin(1_000)
        val check = book.begin(1_500)
        // The check still runs: the probe's number (or none) doesn't go in.
        book.record(probe, fi, null, now = 2_000, probe = true)
        assertEquals(45L, book.shown(fi, 2_000))
        book.record(check, fi, 60, now = 3_000)
        assertEquals(60L, book.shown(fi, 3_000))
    }

    @Test
    fun aProbeAloneGoesIn() {
        val book = LiveLatency.Book()
        val probe = book.begin(1_000)
        book.record(probe, fi, 45, now = 2_000, probe = true)
        assertEquals(45L, book.shown(fi, 2_000))
    }

    @Test
    fun shownOnTheSameServerFreshAndCalm() {
        val book = LiveLatency.Book()
        book.record(book.begin(1_000), fi, 299, now = 1_000)
        assertEquals(299L, book.shown(fi, 1_000))
        assertNull(book.shown(de, 1_000))
        assertEquals(299L, book.shown(fi, 1_000 + LiveLatency.MAX_AGE_MS))
        assertNull(book.shown(fi, 1_001 + LiveLatency.MAX_AGE_MS))
        // From 300 ms none (Главная says «медленно»).
        book.record(book.begin(2_000), fi, 300, now = 2_000)
        assertNull(book.shown(fi, 2_000))
    }

    @Test
    fun probedWhenNoneFreshOnTheServer() {
        val book = LiveLatency.Book()
        assertTrue(book.due(fi, 0))
        book.record(book.begin(1_000), fi, 45, now = 1_000)
        assertFalse(book.due(fi, 1_000 + LiveLatency.PROBE_AFTER_MS - 1))
        assertTrue(book.due(fi, 1_000 + LiveLatency.PROBE_AFTER_MS))
        assertTrue(book.due(de, 1_000))
    }

    @Test
    fun sessionTrafficAndSpeed() {
        val session = TrafficMeter.Session(TrafficMeter.Totals(rx = 1_000, tx = 500, atMillis = 0))
        assertNull(session.speed(0))
        // A moment after: nothing read yet.
        session.look(TrafficMeter.Totals(rx = 5_000, tx = 900, atMillis = 300))
        assertEquals(0L, session.used)
        session.look(TrafficMeter.Totals(rx = 1_000 + 2_097_152, tx = 500 + 2_048, atMillis = 2_000))
        assertEquals(2_097_152L + 2_048L, session.used)
        val speed = session.speed(2_000)!!
        assertEquals(1_048_576.0, speed.down, 0.001)
        assertEquals(1_024.0, speed.up, 0.001)
        // Read 5 s ago or more: not the speed now.
        assertNull(session.speed(7_000))
        // After a gap (the screen off) the traffic goes on, the speed waits for the next look.
        session.look(TrafficMeter.Totals(rx = 1_000 + 3_145_728, tx = 500 + 2_048, atMillis = 60_000))
        assertEquals(3_145_728L + 2_048L, session.used)
        assertNull(session.speed(60_000))
        // Counters that went back don't take the traffic down.
        session.look(TrafficMeter.Totals(rx = 10, tx = 10, atMillis = 62_000))
        assertEquals(3_145_728L + 2_048L, session.used)
    }
}
