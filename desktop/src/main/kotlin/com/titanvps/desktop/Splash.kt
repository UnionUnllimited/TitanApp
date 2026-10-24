package com.titanvps.desktop

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.SplashScreen

/**
 * The start-up window (resources/splash.png, shown by the Java launcher itself before the
 * app loads): the current step and a progress bar under the logo, so a slow start doesn't
 * look like nothing happens. It closes by itself when the main window opens.
 */
object Splash {
    private val TEXT = Color(140, 150, 168)
    private val TRACK = Color(31, 38, 51)
    private val BRAND = Color(26, 102, 255)

    /** Closes it now (when the app starts hidden in the tray, no window ever closes it). */
    fun close() {
        runCatching { SplashScreen.getSplashScreen()?.close() }
    }

    fun step(text: String, progress: Float) {
        runCatching {
            val splash = SplashScreen.getSplashScreen() ?: return
            val size = splash.size
            val g = splash.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            // The previous step off: the overlay is cleared there, so the picture (its light
            // isn't flat) shows through again.
            g.composite = AlphaComposite.Clear
            g.fillRect(1, 222, size.width - 2, 56)
            g.composite = AlphaComposite.SrcOver
            g.font = Font("Segoe UI", Font.PLAIN, 13)
            g.color = TEXT
            val w = g.fontMetrics.stringWidth(text)
            g.drawString(text, (size.width - w) / 2, 245)
            val left = (size.width - 200) / 2
            g.color = TRACK
            g.fillRoundRect(left, 262, 200, 4, 4, 4)
            g.color = BRAND
            g.fillRoundRect(left, 262, (200 * progress.coerceIn(0f, 1f)).toInt().coerceAtLeast(4), 4, 4, 4)
            g.dispose()
            splash.update()
        }
    }
}
