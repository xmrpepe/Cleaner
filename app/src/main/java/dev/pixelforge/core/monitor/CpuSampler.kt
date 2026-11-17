package dev.pixelforge.core.monitor

import android.os.Process
import android.os.SystemClock
import dev.pixelforge.core.util.SysFs

enum class CpuLoadSource(val label: String) {
    PROC_STAT("Kernel /proc/stat"),
    PRIVILEGED_PROC_STAT("/proc/stat via Shizuku"),
    FREQUENCY("Frequency estimate"),
}

data class CpuSample(
    val freqKhz: List<Long?>,
    /** Utilisation per core 0..1, or null when not measurable. */
    val coreLoad: List<Float?>,
    /** Whole-device utilisation 0..1, or null. */
    val totalLoad: Float?,
    val loadSource: CpuLoadSource,
    /** PixelForge's own CPU usage as a fraction of one core. */
    val selfLoad: Float,
    val onlineCores: Int,
)

/**
 * Samples CPU state. Android blocks /proc/stat for apps since Android 8, so utilisation is
 * measured from /proc/stat only when it is readable (directly or via an authorised Shizuku
 * shell); otherwise PixelForge reports a clearly labelled frequency-based estimate.
 */
class CpuSampler(private val privilegedReader: (String) -> String?) {
    private var lastTimes: Map<Int, LongArray>? = null
    private var lastTotal: LongArray? = null
    private var lastSelfCpuMs = Process.getElapsedCpuTime()
    private var lastSelfWall = SystemClock.elapsedRealtime()

    fun sample(topology: CpuTopology): CpuSample {
        val cpus = topology.cores.map { it.id }
        val freqs = cpus.map { CpuInfoReader.currentFreqKhz(it) }

        var source = CpuLoadSource.PROC_STAT
        var stat = SysFs.readText("/proc/stat")
        if (stat == null) {
            stat = privilegedReader("/proc/stat")
            source = CpuLoadSource.PRIVILEGED_PROC_STAT
        }

        var coreLoad: List<Float?>
        var total: Float?
        if (stat != null) {
            val parsed = parseStat(stat)
            val prev = lastTimes
            val prevTotal = lastTotal
            coreLoad = cpus.map { cpu ->
                val now = parsed[cpu]
                val before = prev?.get(cpu)
                if (now != null && before != null) utilisation(before, now) else null
            }
            total = parsed[-1]?.let { now -> prevTotal?.let { utilisation(it, now) } }
            lastTimes = parsed.filterKeys { it >= 0 }
            lastTotal = parsed[-1]
        } else {
            source = CpuLoadSource.FREQUENCY
            coreLoad = topology.cores.mapIndexed { i, core ->
                val f = freqs[i]
                if (f != null && core.maxKhz > 0) (f.toFloat() / core.maxKhz).coerceIn(0f, 1f) else null
            }
            val valid = coreLoad.filterNotNull()
            total = if (valid.isEmpty()) null else valid.average().toFloat()
        }

        val nowCpu = Process.getElapsedCpuTime()
        val nowWall = SystemClock.elapsedRealtime()
        val self = if (nowWall > lastSelfWall) (nowCpu - lastSelfCpuMs).toFloat() / (nowWall - lastSelfWall) else 0f
        lastSelfCpuMs = nowCpu
        lastSelfWall = nowWall

        return CpuSample(
            freqKhz = freqs,
            coreLoad = coreLoad,
            totalLoad = total,
            loadSource = source,
            selfLoad = self.coerceAtLeast(0f),
            onlineCores = CpuInfoReader.onlineCpus().size.takeIf { it > 0 } ?: cpus.size,
        )
    }

    companion object {
        /** Returns cpu index → jiffies array; key -1 is the aggregate "cpu" line. */
        fun parseStat(text: String): Map<Int, LongArray> {
            val out = HashMap<Int, LongArray>()
            text.lineSequence().forEach { line ->
                if (!line.startsWith("cpu")) return@forEach
                val parts = line.trim().split(Regex("\\s+"))
                val name = parts[0]
                val idx = if (name == "cpu") -1 else name.removePrefix("cpu").toIntOrNull() ?: return@forEach
                out[idx] = parts.drop(1).mapNotNull { it.toLongOrNull() }.toLongArray()
            }
            return out
        }

        /** Utilisation between two /proc/stat samples: 1 - Δidle/Δtotal (idle + iowait). */
        fun utilisation(before: LongArray, after: LongArray): Float? {
            if (before.size < 5 || after.size < 5) return null
            val totalBefore = before.sum()
            val totalAfter = after.sum()
            val idleBefore = before[3] + before[4]
            val idleAfter = after[3] + after[4]
            val dt = totalAfter - totalBefore
            if (dt <= 0) return null
            return (1f - (idleAfter - idleBefore).toFloat() / dt).coerceIn(0f, 1f)
        }
    }
}
