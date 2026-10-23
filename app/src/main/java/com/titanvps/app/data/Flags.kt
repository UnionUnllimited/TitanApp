package com.titanvps.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import com.titanvps.app.R
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Flags of server names ("🇫🇮 Финляндия", England's 🏴 with tag letters) and their round
 * images: square PNGs of flag-icons in assets/flags (CI only: scripts/download-flags-android.sh;
 * a local build goes without and shows the emoji). The name functions are plain Kotlin (unit
 * tested); Android classes appear only inside the image functions.
 */
object Flags {

    /** The leading flag emoji and the rest of the name, trimmed; (null, name) without one. */
    fun split(name: String): Pair<String?, String> {
        val t = name.trim()
        val cps = t.codePoints().toArray()
        val length = when {
            cps.size >= 2 && cps[0] in REGIONAL && cps[1] in REGIONAL -> 2
            cps.isNotEmpty() && cps[0] == BLACK_FLAG -> {
                var i = 1
                while (i < cps.size && isTag(cps[i])) i++
                if (i > 1 && i < cps.size && cps[i] == CANCEL_TAG) i + 1 else 0
            }
            else -> 0
        }
        if (length == 0) return null to t
        val flag = String(cps, 0, length)
        return flag to t.substring(flag.length).trim()
    }

    /** "🇫🇮" → "fi"; England's 🏴 with its tag letters → "gb-eng" (flag-icons' names). */
    fun code(flagEmoji: String): String? {
        val cps = flagEmoji.trim().codePoints().toArray()
        if (cps.size == 2 && cps.all { it in REGIONAL }) {
            return String(CharArray(2) { 'a' + (cps[it] - REGIONAL.first) })
        }
        // 🏴, the subdivision in tag letters ("gbeng"), the cancel tag.
        if (cps.size > 4 && cps.first() == BLACK_FLAG && cps.last() == CANCEL_TAG) {
            val tags = cps.copyOfRange(1, cps.size - 1)
            if (tags.all(::isTag)) {
                val s = String(CharArray(tags.size) { (tags[it] - TAG_BASE).toChar() })
                return s.take(2) + "-" + s.drop(2)
            }
        }
        return null
    }

    fun codeOf(serverName: String): String? = split(serverName).first?.let(::code)

    /** The name as shown next to its FlagCircle. */
    fun nameWithoutFlag(name: String): String = split(name).second

    /** "fi" → 🇫🇮, "gb-eng" → England's flag; "" for anything else. */
    fun emojiOf(code: String): String {
        val c = code.trim().lowercase()
        if (c.length == 2 && c.all { it in 'a'..'z' }) {
            return String(IntArray(2) { REGIONAL.first + (c[it] - 'a') }, 0, 2)
        }
        val parts = c.split('-')
        if (parts.size == 2 && parts[0].length == 2 && parts[1].isNotEmpty() &&
            (parts[0] + parts[1]).all { it in 'a'..'z' || it in '0'..'9' }
        ) {
            val tags = (parts[0] + parts[1]).map { TAG_BASE + it.code }
            val cps = (listOf(BLACK_FLAG) + tags + CANCEL_TAG).toIntArray()
            return String(cps, 0, cps.size)
        }
        return ""
    }

    /** The flag's image if it was loaded before (no disk access: safe while drawing). */
    fun cached(code: String): Bitmap? = synchronized(images) { images[code] }

    /** The flag's image from assets/flags/<code>.png, loaded once; null if this build has none. */
    fun bitmap(context: Context, code: String): Bitmap? {
        cached(code)?.let { return it }
        if (code in missing || !code.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) return null
        val decoded = runCatching {
            context.assets.open("flags/$code.png").use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
        if (decoded == null) {
            missing += code
            return null
        }
        synchronized(images) { images[code] = decoded }
        return decoded
    }

    /**
     * A round flag for places outside Compose (the notification, the widget): [discColor]
     * disc, the flag PNG filled into the circle, else the emoji, else the app's shield; a
     * [strokeColor] ring round it. Colours are ARGB ints.
     */
    fun circleBitmap(
        context: Context,
        serverName: String,
        sizePx: Int,
        discColor: Int = 0x1AFFFFFF,
        strokeColor: Int = 0x26FFFFFF,
    ): Bitmap {
        val size = sizePx.coerceAtLeast(2)
        val r = size / 2f
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = discColor
        canvas.drawCircle(r, r, r, paint)

        val flag = split(serverName).first
        val image = flag?.let(::code)?.let { bitmap(context, it) }
        when {
            image != null -> fillCircle(canvas, image, size)
            flag != null -> {
                // Drawn square first, then filled into the circle with smooth edges.
                val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = size * 1.6f
                    textAlign = Paint.Align.CENTER
                }
                val metrics = text.fontMetrics
                Canvas(square).drawText(flag, r, r - (metrics.ascent + metrics.descent) / 2, text)
                fillCircle(canvas, square, size)
                square.recycle()
            }
            else -> context.getDrawable(R.drawable.ic_shield)?.mutate()?.let { shield ->
                shield.setTint(0xFF1A66FF.toInt())
                val s = size / 2
                val o = (size - s) / 2
                shield.setBounds(o, o, o + s, o + s)
                shield.draw(canvas)
            }
        }

        val ring = max(1, size / 48).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ring
        paint.color = strokeColor
        canvas.drawCircle(r, r, r - ring / 2, paint)
        return out
    }

    /** [image] scaled to fill the circle of [size] (flags are square). */
    private fun fillCircle(canvas: Canvas, image: Bitmap, size: Int) {
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = max(size.toFloat() / image.width, size.toFloat() / image.height)
        shader.setLocalMatrix(
            Matrix().apply {
                setScale(scale, scale)
                postTranslate((size - image.width * scale) / 2, (size - image.height * scale) / 2)
            },
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.shader = shader
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    }

    private fun isTag(cp: Int): Boolean = cp in TAG_DIGITS || cp in TAG_LETTERS

    private val REGIONAL = 0x1F1E6..0x1F1FF
    private const val BLACK_FLAG = 0x1F3F4
    private const val CANCEL_TAG = 0xE007F
    private const val TAG_BASE = 0xE0000
    private val TAG_DIGITS = 0xE0030..0xE0039
    private val TAG_LETTERS = 0xE0061..0xE007A

    // Plain Java collections, no Android object made here: JVM unit tests load this object.
    /** The last 64 flags drawn (access order). */
    private val images = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 64
    }
    private val missing: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
}
