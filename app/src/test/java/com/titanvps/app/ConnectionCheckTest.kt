package com.titanvps.app

import com.titanvps.app.core.ConnectionCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionCheckTest {

    @Test
    fun traceWithIpAndCountry() {
        val body = "fl=29f12\nh=1.1.1.1\nip=203.0.113.7\nts=1700000000.1\nvisit_scheme=https\nloc=fi\ntls=TLSv1.3\n"
        assertEquals("203.0.113.7" to "FI", ConnectionCheck.parseTrace(body))
    }

    @Test
    fun traceWithoutThem() {
        assertEquals(null to null, ConnectionCheck.parseTrace("fl=29f12\nh=1.1.1.1\n"))
        assertEquals(null to null, ConnectionCheck.parseTrace(""))
        // Empty values count as missing.
        val (ip, loc) = ConnectionCheck.parseTrace("ip=\nloc=\n")
        assertNull(ip)
        assertNull(loc)
    }

    @Test
    fun traceWithCrLf() {
        val body = "ip=2001:db8::1\r\nloc=DE\r\nwarp=off\r\n"
        assertEquals("2001:db8::1" to "DE", ConnectionCheck.parseTrace(body))
    }

    @Test
    fun valueKeepsItsEqualsSigns() {
        assertEquals("a=b" to "NL", ConnectionCheck.parseTrace("ip=a=b\nloc=nl"))
    }

    @Test
    fun medianOfTheSamples() {
        // The last one without an answer (1.5 s) doesn't move it.
        assertEquals(64L, ConnectionCheck.median(listOf(64, 61, 1500)))
        // Of two, the upper one (as iOS): a slow answer isn't hidden by a quick one.
        assertEquals(80L, ConnectionCheck.median(listOf(80, 60)))
        assertEquals(42L, ConnectionCheck.median(listOf(42)))
        assertNull(ConnectionCheck.median(emptyList()))
    }

    /** [ConnectionCheck.warmLatency] asking [answers] in turn; [asked]: how many it took. */
    private class Script(vararg answers: ConnectionCheck.Answer) {
        private val left = ArrayDeque(answers.toList())
        var asked = 0

        fun latency(): Long? = ConnectionCheck.warmLatency {
            asked++
            left.removeFirst()
        }
    }

    private fun ms(ms: Long) = ConnectionCheck.Answer.Ms(ms)

    @Test
    fun threeWarmAnswers() {
        val script = Script(ms(60), ms(80), ms(70), ms(10))
        assertEquals(70L, script.latency())
        assertEquals(3, script.asked)
    }

    @Test
    fun aTimeoutIsSlowAndEndsIt() {
        // [80, no answer within 1.5 s, 85]: not a green 82, and 85 is never asked for.
        val script = Script(ms(80), ConnectionCheck.Answer.TimedOut, ms(85))
        val latency = script.latency()!!
        assertEquals(ConnectionCheck.SAMPLE_TIMEOUT_MS, latency)
        assertEquals(2, script.asked)
        assertEquals(ConnectionCheck.Latency.SLOW, ConnectionCheck.latencyOf(latency))
        assertEquals(ConnectionCheck.SAMPLE_TIMEOUT_MS, Script(ConnectionCheck.Answer.TimedOut).latency())
    }

    @Test
    fun aLostConnectionEndsItWithWhatCame() {
        val script = Script(ms(80), ConnectionCheck.Answer.Lost, ms(85))
        assertEquals(80L, script.latency())
        assertEquals(2, script.asked)
        assertNull(Script(ConnectionCheck.Answer.Lost).latency())
    }

    @Test
    fun aNewConnectionIsNeverWarm() {
        // Answered or timed out: needing a new connection loses the warm one, it isn't slow.
        assertEquals(ConnectionCheck.Answer.Lost, ConnectionCheck.answered(ms(40), newConnection = true))
        assertEquals(ConnectionCheck.Answer.Lost, ConnectionCheck.answered(ConnectionCheck.Answer.TimedOut, newConnection = true))
        assertEquals(ms(40), ConnectionCheck.answered(ms(40), newConnection = false))
        assertEquals(ConnectionCheck.Answer.TimedOut, ConnectionCheck.answered(ConnectionCheck.Answer.TimedOut, newConnection = false))
    }

    @Test
    fun latencyShownCalmly() {
        assertEquals(ConnectionCheck.Latency.FAST, ConnectionCheck.latencyOf(1))
        assertEquals(ConnectionCheck.Latency.FAST, ConnectionCheck.latencyOf(119))
        assertEquals(ConnectionCheck.Latency.NORMAL, ConnectionCheck.latencyOf(120))
        assertEquals(ConnectionCheck.Latency.NORMAL, ConnectionCheck.latencyOf(299))
        // From 300 ms a word instead of the number.
        assertEquals(ConnectionCheck.Latency.SLOW, ConnectionCheck.latencyOf(300))
        assertEquals(ConnectionCheck.Latency.SLOW, ConnectionCheck.latencyOf(1575))
    }
}
