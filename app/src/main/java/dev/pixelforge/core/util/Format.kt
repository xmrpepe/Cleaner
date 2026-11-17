package dev.pixelforge.core.util

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object Format {
    private val units = arrayOf("B", "KB", "MB", "GB", "TB")

    /** Binary-prefixed size (1 KB = 1024 B) to match how Android's Settings app reports storage. */
    fun bytes(value: Long, decimals: Int = 1): String {
        if (value < 0) return "—"
        if (value < 1024) return "$value B"
        val exp = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.lastIndex)
        val v = value / 1024.0.pow(exp)
        return String.format(Locale.US, "%.${decimals}f %s", v, units[exp])
    }

    fun percent(fraction: Double, decimals: Int = 0): String =
        String.format(Locale.US, "%.${decimals}f%%", fraction * 100)

    fun number(value: Double, decimals: Int = 1): String = String.format(Locale.US, "%.${decimals}f", value)

    fun mhz(khz: Long): String = when {
        khz <= 0 -> "—"
        khz >= 1_000_000 -> String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
        khz >= 1000 -> "${khz / 1000} MHz"
        else -> "$khz kHz"
    }

    fun celsius(c: Float?): String = if (c == null || c.isNaN()) "—" else String.format(Locale.US, "%.1f°C", c)

    fun duration(ms: Long): String {
        val s = abs(ms) / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "${d}d ${h}h ${m}m"
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${sec}s"
            else -> "${sec}s"
        }
    }

    fun dateTime(epochMs: Long): String =
        if (epochMs <= 0) "—" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

    fun date(epochMs: Long): String =
        if (epochMs <= 0) "—" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))

    fun ago(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        if (epochMs <= 0) return "never"
        val diff = now - epochMs
        return if (diff < 60_000) "just now" else "${duration(diff).substringBefore(' ')} ago"
    }
}
