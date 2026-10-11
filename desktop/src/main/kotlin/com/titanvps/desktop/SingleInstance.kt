package com.titanvps.desktop

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * One copy of the app at a time: the first one listens on a local port; a second start
 * (e.g. the desktop icon while the app sits in the tray) asks it to show its window and quits.
 */
class SingleInstance private constructor(private val server: ServerSocket) {
    @Volatile private var onShow: () -> Unit = {}

    init {
        thread(isDaemon = true, name = "single-instance") {
            while (!server.isClosed) {
                runCatching { server.accept().use { onShow() } }
            }
        }
    }

    fun onShow(action: () -> Unit) { onShow = action }

    companion object {
        private const val PORT = 47613

        /**
         * This instance, or null if another one runs (it has been asked to show itself).
         * [waitMs]: the previous copy is quitting (restart as administrator), wait for it.
         */
        fun claim(waitMs: Long = 0): SingleInstance? {
            val loopback = InetAddress.getLoopbackAddress()
            val until = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < until) {
                runCatching { return SingleInstance(ServerSocket(PORT, 4, loopback)) }
                Thread.sleep(200)
            }
            return runCatching { SingleInstance(ServerSocket(PORT, 4, loopback)) }.getOrElse {
                // Taken: most likely our own app. If nobody answers, run anyway.
                val other = runCatching { Socket(loopback, PORT).use { } }.isSuccess
                if (other) null else SingleInstance(ServerSocket(0, 4, loopback))
            }
        }
    }
}
