package com.titanvps.app

import com.titanvps.app.core.PingReasons
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PingReasonsTest {

    @Test
    fun timeouts() {
        assertEquals("таймаут", PingReasons.short(SocketTimeoutException("Read timed out")))
        // OkHttp's call timeout.
        assertEquals("таймаут", PingReasons.short(InterruptedIOException("timeout")))
    }

    @Test
    fun refusedAndUnknownHost() {
        assertEquals("соединение отклонено", PingReasons.short(ConnectException("Connection refused")))
        assertEquals("адрес сервера не найден", PingReasons.short(UnknownHostException("www.gstatic.com")))
    }

    @Test
    fun tls() {
        assertEquals("ошибка TLS: Handshake failed", PingReasons.short(SSLHandshakeException("Handshake failed")))
        assertEquals("ошибка TLS", PingReasons.short(SSLHandshakeException(null)))
    }

    @Test
    fun socksAndGeneric() {
        assertEquals("прокси не ответил", PingReasons.short(SocketException("Malformed reply from SOCKS server")))
        assertEquals("SocketException: Connection reset", PingReasons.short(SocketException("Connection reset")))
        assertEquals("IOException", PingReasons.short(IOException()))
    }

    @Test
    fun shortIsCapped() {
        val reason = PingReasons.short(IOException("x".repeat(500)))
        assertEquals(200, reason.length)
        assertTrue(reason.startsWith("IOException: x"))
    }

    @Test
    fun cleanDropsTheRequest() {
        assertEquals(
            "context deadline exceeded (Client.Timeout exceeded while awaiting headers)",
            PingReasons.clean("Head \"https://www.gstatic.com/generate_204\": context deadline exceeded (Client.Timeout exceeded while awaiting headers)"),
        )
        assertEquals("EOF", PingReasons.clean("Get \"https://www.gstatic.com/generate_204\": EOF"))
    }

    @Test
    fun cleanCollapsesSpaceAndCaps() {
        assertEquals("dial tcp: i/o timeout", PingReasons.clean("  dial tcp:\n   i/o   timeout "))
        assertEquals(200, PingReasons.clean("y".repeat(300)).length)
    }
}
