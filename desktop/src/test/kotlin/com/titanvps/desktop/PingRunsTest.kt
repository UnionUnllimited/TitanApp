package com.titanvps.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which measurement may still change the servers on screen ([PingRuns]): a Пинг stopped or
 * overtaken must not mark servers «таймаут» or clear a newer run's spinners when its last
 * probes end late; Выйти stops it as Стоп does.
 */
class PingRunsTest {

    @Test
    fun aRunOwnsTheRowsUntilStopped() {
        val runs = PingRuns()
        val a = runs.startList()
        assertTrue(runs.owns(a))
        assertTrue(runs.spins(a))
        assertTrue(runs.isLatest(a))
        runs.stop()
        assertFalse(runs.owns(a))
        assertFalse(runs.spins(a))
        assertTrue(runs.stopped(a))
        // Nothing newer: its end may still end «Стоп» on the button (already off).
        assertTrue(runs.isLatest(a))
    }

    @Test
    fun pingStopPingTheStoppedRunsLateEndChangesNothing() {
        val runs = PingRuns()
        val a = runs.startList()
        runs.stop()
        val b = runs.startList()
        // A's in-flight probes end now: no values, no «таймаут», no spinners cleared, «Стоп» stays.
        assertFalse(runs.owns(a))
        assertFalse(runs.spins(a))
        assertFalse(runs.isLatest(a))
        assertTrue(runs.owns(b))
        assertTrue(runs.spins(b))
        assertFalse(runs.stopped(b))
        assertTrue(runs.isLatest(b))
        // Стоп on B: B is stopped, A stays out (its earlier «таймаут» never came).
        runs.stop()
        assertFalse(runs.owns(b))
        assertFalse(runs.owns(a))
    }

    @Test
    fun aNewerRunOvertakesWithoutStop() {
        val runs = PingRuns()
        val a = runs.startList()
        val b = runs.startList()
        assertFalse(runs.owns(a))
        assertFalse(runs.isLatest(a))
        assertTrue(runs.owns(b))
    }

    @Test
    fun oneServerKeepsAResultAfterStopButNoTimeoutNorSpinner() {
        val runs = PingRuns()
        val one = runs.startOne()
        val list = runs.startList()
        assertTrue(runs.owns(one))
        assertTrue(runs.spins(one))
        runs.stop()
        // Its probe in flight was measured: the value stays; the spinners are cleared by Стоп.
        assertTrue(runs.owns(one))
        assertFalse(runs.spins(one))
        assertTrue(runs.stopped(one))
        assertFalse(runs.owns(list))
        // One server pinged after Стоп is a measurement like any other.
        val after = runs.startOne()
        assertTrue(runs.owns(after))
        assertTrue(runs.spins(after))
        assertFalse(runs.stopped(after))
    }
}
