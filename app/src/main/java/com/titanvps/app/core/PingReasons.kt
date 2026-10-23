package com.titanvps.app.core

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Why a ping or a connection check got no answer, in a few words (shown in Технический
 * режим and copied for the support chat). Plain Kotlin: unit tested on the JVM.
 */
object PingReasons {

    /** An exception of a request through a server (OkHttp, SOCKS, TLS) as a short reason. */
    fun short(e: Throwable): String {
        val message = e.message?.trim().orEmpty()
        val text = when {
            // SocketTimeoutException is one too; OkHttp's call timeout throws a plain one.
            e is InterruptedIOException -> "таймаут"
            e is ConnectException -> "соединение отклонено"
            e is UnknownHostException -> "адрес сервера не найден"
            e is SSLException -> if (message.isEmpty()) "ошибка TLS" else "ошибка TLS: $message"
            "SOCKS" in message -> "прокси не ответил"
            message.isEmpty() -> e.javaClass.simpleName
            else -> e.javaClass.simpleName + ": " + message
        }
        return text.trim().take(MAX_LENGTH)
    }

    /** Xray's (Go's) error without the request it was about: `Head "<url>": <reason>` → reason. */
    fun clean(goError: String): String =
        goError.trim()
            .replaceFirst(REQUEST_PREFIX, "")
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_LENGTH)

    private val REQUEST_PREFIX = Regex("""^(?:Head|Get) "[^"]*":\s*""")
    private val WHITESPACE = Regex("""\s+""")
    private const val MAX_LENGTH = 200
}
