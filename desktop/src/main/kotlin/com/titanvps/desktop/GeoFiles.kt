package com.titanvps.desktop

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Trimmed copies of geoip.dat / geosite.dat with only the codes the subscription uses
 * (the full geosite is ~70 MB). Same as the Android app.
 */
object GeoFiles {
    private val TAG = Regex("""\b(geosite|geoip):!?([A-Za-z0-9_.\-]+)""")
    private val dir = File(AppPaths.dataDir, "geo")

    @Synchronized
    fun prepare(configs: List<String>): File {
        val codes = mutableMapOf("geosite" to sortedSetOf<String>(), "geoip" to sortedSetOf())
        configs.forEach { cfg -> TAG.findAll(cfg).forEach { m -> codes.getValue(m.groupValues[1]) += m.groupValues[2].substringBefore('@').uppercase() } }
        val sources = listOf("geosite", "geoip").associateWith { File(AppPaths.coreDir, "$it.dat") }
        val key = sources.values.joinToString { "${it.length()}" } + "|" + codes.toString()
        dir.mkdirs()
        val marker = File(dir, ".codes")
        if (marker.exists() && marker.readText() == key && sources.keys.all { File(dir, "$it.dat").exists() }) return dir
        for ((kind, src) in sources) {
            val out = File(dir, "$kind.dat")
            if (!src.exists()) { out.writeBytes(ByteArray(0)); continue }
            val tmp = File(dir, "$kind.dat.tmp")
            src.inputStream().use { input -> BufferedOutputStream(tmp.outputStream()).use { trim(input, it, codes.getValue(kind)) } }
            out.delete()
            tmp.renameTo(out)
        }
        marker.writeText(key)
        return dir
    }

    private fun trim(input: InputStream, output: OutputStream, keep: Set<String>) {
        val src = BufferedInputStream(input, 1 shl 16)
        while (true) {
            val tag = readVarint(src) ?: break
            require((tag and 7).toInt() == 2) { "bad geo file" }
            val len = readVarint(src)!!.toInt()
            val entry = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = src.read(entry, read, len - read)
                if (n < 0) throw EOFException()
                read += n
            }
            if (code(entry)?.uppercase() in keep) {
                writeVarint(output, tag); writeVarint(output, len.toLong()); output.write(entry)
            }
        }
    }

    private fun code(entry: ByteArray): String? {
        if (entry.isEmpty() || entry[0].toInt() != 0x0A) return null
        var pos = 1; var len = 0L; var shift = 0
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
        var result = 0L; var shift = 0
        while (true) {
            val b = src.read()
            if (b < 0) { if (shift == 0) return null; throw EOFException() }
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) return result
            shift += 7
        }
    }

    private fun writeVarint(out: OutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }
}
