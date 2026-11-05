package com.titanvps.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * The protocol clients behind a warm ping core ([ClientStarts]): a Naive, Mieru or sing-box
 * client whose start failed or that exited since was reused by every Пинг in the next minute,
 * and its server read «таймаут» although it works. It now starts again when a probe needs it,
 * once, however many probes or requests ask at once.
 */
class ClientStartsTest {

    /** A fake client: [up] while running; each start counted and finished by the test. */
    private class Client(val name: String) {
        @Volatile var up = false
        val starts = mutableListOf<CompletableFuture<Unit>>()
        override fun toString() = name
    }

    private fun startsOf(): ClientStarts<Client> = ClientStarts({ it.up }, { c ->
        synchronized(c) { CompletableFuture<Unit>().also { c.starts += it } }
    })

    private fun Client.started() { up = true; synchronized(this) { starts.last() }.complete(Unit) }
    private fun Client.failed() { up = false; synchronized(this) { starts.last() }.completeExceptionally(IllegalStateException("не отвечает")) }

    @Test
    fun aClientStillStartingIsWaitedFor() {
        val starts = startsOf()
        val c = Client("naive")
        starts.begin(c)
        val first = starts.ready(c)
        // A second probe through it, or a row's ping meanwhile: the same start.
        assertSame(first, starts.ready(c))
        assertEquals(1, c.starts.size)
    }

    @Test
    fun aRunningClientIsntStartedAgain() {
        val starts = startsOf()
        val c = Client("mieru")
        starts.begin(c)
        c.started()
        val f = starts.ready(c)
        assertTrue(f.isDone)
        assertSame(f, starts.ready(c))
        assertEquals(1, c.starts.size)
    }

    @Test
    fun aFailedStartIsTriedAgainOnTheNextPing() {
        val starts = startsOf()
        val c = Client("sing-box")
        starts.begin(c)
        c.failed()
        // It used to give «клиент протокола не запустился» at once on every warm Пинг.
        val again = starts.ready(c)
        assertEquals(2, c.starts.size)
        assertTrue(!again.isDone)
        c.started()
        assertSame(again, starts.ready(c))
        assertEquals(2, c.starts.size)
    }

    @Test
    fun aClientThatExitedSinceIsStartedAgain() {
        val starts = startsOf()
        val c = Client("naive")
        starts.begin(c)
        c.started()
        val first = starts.ready(c)
        c.up = false // exited (a crash, Task Manager): its port refuses every probe
        val again = starts.ready(c)
        assertNotSame(first, again)
        assertEquals(2, c.starts.size)
    }

    @Test
    fun eachClientIsItsOwn() {
        val starts = startsOf()
        val a = Client("naive")
        val b = Client("mieru")
        starts.begin(a)
        starts.begin(b)
        a.failed()
        b.started()
        starts.ready(a)
        starts.ready(b)
        assertEquals(2, a.starts.size)
        assertEquals(1, b.starts.size)
    }

    @Test
    fun manyProbesAtOnceStartADeadClientOnce() {
        // A list's probes through a balancer's members on one client and a row's ping, together.
        val starts = startsOf()
        val c = Client("naive")
        starts.begin(c)
        c.failed()
        val pool = Executors.newFixedThreadPool(16)
        try {
            val go = CountDownLatch(1)
            val asked = (1..64).map { pool.submit<Future<*>> { go.await(); starts.ready(c) } }
            go.countDown()
            val got = asked.map { it.get(5, TimeUnit.SECONDS) }.toSet()
            assertEquals(1, got.size)
            assertEquals(2, c.starts.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
