package dev.pixelforge.core.util

import java.io.File

/**
 * Safe readers for procfs / sysfs. Android's SELinux policy blocks many of these paths for
 * ordinary apps; every reader returns null instead of throwing so callers can report the
 * value as "inaccessible" rather than inventing one.
 */
object SysFs {
    fun readText(path: String, maxBytes: Int = 64 * 1024): String? = try {
        val f = File(path)
        if (!f.canRead()) null else f.inputStream().use { input ->
            val buf = ByteArray(maxBytes)
            var total = 0
            while (total < maxBytes) {
                val n = input.read(buf, total, maxBytes - total)
                if (n <= 0) break
                total += n
            }
            String(buf, 0, total)
        }
    } catch (_: Throwable) {
        null
    }

    fun readLine(path: String): String? = readText(path, 4096)?.lineSequence()?.firstOrNull()?.trim()

    fun readLong(path: String): Long? = readLine(path)?.toLongOrNull()

    /** Parses CPU list syntax such as "0-3,5,7-8". */
    fun parseCpuList(spec: String?): List<Int> {
        if (spec.isNullOrBlank()) return emptyList()
        return spec.trim().split(',').flatMap { part ->
            val range = part.trim().split('-')
            when (range.size) {
                1 -> listOfNotNull(range[0].toIntOrNull())
                2 -> {
                    val a = range[0].toIntOrNull()
                    val b = range[1].toIntOrNull()
                    if (a != null && b != null && b >= a) (a..b).toList() else emptyList()
                }
                else -> emptyList()
            }
        }
    }

    /** Reads an Android system property using the toybox getprop binary (no hidden APIs). */
    fun getprop(name: String): String? = try {
        val p = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
        p.waitFor()
        out.ifEmpty { null }
    } catch (_: Throwable) {
        null
    }
}
