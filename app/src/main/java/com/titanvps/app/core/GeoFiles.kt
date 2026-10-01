package com.titanvps.app.core

import android.content.Context
import com.titanvps.app.BuildConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * geoip.dat / geosite.dat for Xray. The bundled lists are huge (geosite ≈ 70 MB), but a
 * subscription only uses a handful of categories, so we write trimmed copies with just
 * the `geosite:` / `geoip:` codes the configs reference. Xray would fail on a missing
 * code with the full file as well, so trimming never changes routing.
 */
object GeoFiles {

    private val TAG = Regex("""\b(geosite|geoip):!?([A-Za-z0-9_.\-]+)""")

    /** Codes used by the configs, upper-cased as in the .dat files; `@attr` dropped. */
    fun usedCodes(configs: List<String>): Map<String, Set<String>> {
        val out = mutableMapOf("geosite" to mutableSetOf<String>(), "geoip" to mutableSetOf())
        configs.forEach { cfg ->
            TAG.findAll(cfg).forEach { m ->
                out.getValue(m.groupValues[1]) += m.groupValues[2].substringBefore('@').uppercase()
            }
        }
        return out
    }

    /** Makes sure [dir] holds .dat files with (at least) these codes. Call off the main thread. */
    @Synchronized
    fun prepare(context: Context, dir: File, configs: List<String>) {
        dir.mkdirs()
        val codes = usedCodes(configs)
        val key = BuildConfig.VERSION_CODE.toString() + "|" +
            codes.toSortedMap().entries.joinToString(";") { (k, v) -> k + "=" + v.sorted().joinToString(",") }
        val marker = File(dir, ".codes")
        if (marker.exists() && marker.readText() == key &&
            File(dir, "geosite.dat").exists() && File(dir, "geoip.dat").exists()
        ) return
        for (kind in listOf("geosite", "geoip")) {
            val tmp = File(dir, "$kind.dat.tmp")
            context.assets.open("$kind.dat").use { input ->
                BufferedOutputStream(tmp.outputStream()).use { trim(input, it, codes.getValue(kind)) }
            }
            tmp.renameTo(File(dir, "$kind.dat"))
        }
        marker.writeText(key)
    }

    /** Copies the entries (field 1 of GeoSiteList / GeoIPList) whose country_code is in [keep]. */
    fun trim(input: InputStream, output: OutputStream, keep: Set<String>) {
        val src = BufferedInputStream(input, 1 shl 16)
        while (true) {
            val tag = readVarint(src) ?: break
            val wire = (tag and 7).toInt()
            require(wire == 2) { "unexpected wire type $wire" }
            val len = readVarint(src)!!.toInt()
            val entry = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = src.read(entry, read, len - read)
                if (n < 0) throw EOFException()
                read += n
            }
            if (code(entry)?.uppercase() in keep) {
                writeVarint(output, tag)
                writeVarint(output, len.toLong())
                output.write(entry)
            }
        }
    }

    /** First field of GeoSite / GeoIP: string country_code = 1. */
    private fun code(entry: ByteArray): String? {
        if (entry.isEmpty() || entry[0].toInt() != 0x0A) return null
        var pos = 1
        var len = 0L
        var shift = 0
        while (pos < entry.size) {
            val b = entry[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        if (pos + len > entry.size) return null
        return String(entry, pos, len.toInt(), Charsets.UTF_8)
    }

    private fun readVarint(src: InputStream): Long? {
        var result = 0L
        var shift = 0
        while (true) {
            val b = src.read()
            if (b < 0) {
                if (shift == 0) return null
                throw EOFException()
            }
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    private fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while (v >= 0x80) {
            out.write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}
