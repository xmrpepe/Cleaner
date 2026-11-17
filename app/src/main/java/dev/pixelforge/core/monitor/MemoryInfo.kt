package dev.pixelforge.core.monitor

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import dev.pixelforge.core.util.SysFs

data class PsiStat(val avg10: Float, val avg60: Float, val avg300: Float)

data class MemorySample(
    val totalBytes: Long,
    val availableBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    /** Selected /proc/meminfo fields in bytes (empty when unreadable). */
    val meminfo: Map<String, Long>,
    val psiSome: PsiStat?,
    val psiFull: PsiStat?,
    val selfPssBytes: Long,
    val selfJavaHeapBytes: Long,
    val selfNativeHeapBytes: Long,
) {
    val usedBytes: Long get() = totalBytes - availableBytes
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    val swapTotal: Long get() = meminfo["SwapTotal"] ?: 0
    val swapFree: Long get() = meminfo["SwapFree"] ?: 0

    /**
     * Pressure level 0..3 derived from kernel PSI when available, otherwise from how close
     * available memory is to the low-memory killer threshold.
     */
    val pressureLevel: Int
        get() {
            psiSome?.let { p ->
                return when {
                    p.avg10 >= 20f -> 3
                    p.avg10 >= 5f -> 2
                    p.avg10 >= 1f -> 1
                    else -> 0
                }
            }
            if (lowMemory) return 3
            val headroom = availableBytes - thresholdBytes
            return when {
                headroom < thresholdBytes / 2 -> 2
                availableBytes < totalBytes / 5 -> 1
                else -> 0
            }
        }
}

class MemoryReader(private val context: Context, private val privilegedReader: (String) -> String?) {
    private val am = context.getSystemService(ActivityManager::class.java)
    private var lastSelfPssAt = 0L
    private var lastSelfPss = 0L

    fun sample(): MemorySample {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val meminfo = SysFs.readText("/proc/meminfo")?.let(::parseMeminfo) ?: emptyMap()
        val psiText = SysFs.readText("/proc/pressure/memory") ?: privilegedReader("/proc/pressure/memory")
        val (some, full) = psiText?.let(::parsePsi) ?: (null to null)

        // Debug.getPss() is comparatively expensive; refresh at most every 10 s.
        val now = System.currentTimeMillis()
        if (now - lastSelfPssAt > 10_000) {
            lastSelfPss = Debug.getPss() * 1024
            lastSelfPssAt = now
        }
        val rt = Runtime.getRuntime()
        return MemorySample(
            totalBytes = mi.totalMem,
            availableBytes = mi.availMem,
            thresholdBytes = mi.threshold,
            lowMemory = mi.lowMemory,
            meminfo = meminfo,
            psiSome = some,
            psiFull = full,
            selfPssBytes = lastSelfPss,
            selfJavaHeapBytes = rt.totalMemory() - rt.freeMemory(),
            selfNativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
        )
    }

    companion object {
        fun parseMeminfo(text: String): Map<String, Long> = text.lineSequence().mapNotNull { line ->
            val idx = line.indexOf(':')
            if (idx < 0) return@mapNotNull null
            val key = line.substring(0, idx)
            val parts = line.substring(idx + 1).trim().split(Regex("\\s+"))
            val v = parts.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            key to if (parts.getOrNull(1) == "kB") v * 1024 else v
        }.toMap()

        fun parsePsi(text: String): Pair<PsiStat?, PsiStat?> {
            var some: PsiStat? = null
            var full: PsiStat? = null
            text.lineSequence().forEach { line ->
                val fields = line.trim().split(' ').mapNotNull {
                    val kv = it.split('=')
                    if (kv.size == 2) kv[0] to kv[1] else null
                }.toMap()
                val stat = PsiStat(
                    fields["avg10"]?.toFloatOrNull() ?: return@forEach,
                    fields["avg60"]?.toFloatOrNull() ?: 0f,
                    fields["avg300"]?.toFloatOrNull() ?: 0f,
                )
                if (line.startsWith("some")) some = stat else if (line.startsWith("full")) full = stat
            }
            return some to full
        }
    }
}
