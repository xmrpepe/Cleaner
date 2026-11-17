package dev.pixelforge.core.monitor

import android.os.Build
import dev.pixelforge.core.util.SysFs

data class CpuCore(
    val id: Int,
    val clusterIndex: Int,
    val minKhz: Long,
    val maxKhz: Long,
    val partId: Int?,
    val microarchitecture: String,
    val governor: String?,
    val availableFrequenciesKhz: List<Long>,
)

data class CpuCluster(
    val index: Int,
    val name: String,
    val cores: List<Int>,
    val maxKhz: Long,
    val minKhz: Long,
) {
    val mask: Long get() = cores.fold(0L) { acc, c -> acc or (1L shl c) }
}

data class CpuTopology(
    val socModel: String,
    val socManufacturer: String,
    val hardware: String?,
    val cores: List<CpuCore>,
    val clusters: List<CpuCluster>,
    val abis: List<String>,
    val features: List<String>,
    val isTensorG4: Boolean,
) {
    val coreCount: Int get() = cores.size
    val primeCluster: CpuCluster? get() = clusters.maxByOrNull { it.maxKhz }
}

/**
 * Reads CPU topology from /proc/cpuinfo and cpufreq sysfs (both readable by apps on Pixel
 * devices at the time of writing). Values that cannot be read are omitted, never guessed.
 */
object CpuInfoReader {
    private const val CPU_DIR = "/sys/devices/system/cpu"

    /** ARM MIDR part numbers (implementer 0x41) → microarchitecture names. */
    private val armParts = mapOf(
        0xd03 to "Cortex-A53", 0xd04 to "Cortex-A35", 0xd05 to "Cortex-A55", 0xd07 to "Cortex-A57",
        0xd08 to "Cortex-A72", 0xd09 to "Cortex-A73", 0xd0a to "Cortex-A75", 0xd0b to "Cortex-A76",
        0xd0d to "Cortex-A77", 0xd41 to "Cortex-A78", 0xd44 to "Cortex-X1", 0xd46 to "Cortex-A510",
        0xd47 to "Cortex-A710", 0xd48 to "Cortex-X2", 0xd4d to "Cortex-A715", 0xd4e to "Cortex-X3",
        0xd80 to "Cortex-A520", 0xd81 to "Cortex-A720", 0xd82 to "Cortex-X4", 0xd84 to "Neoverse-V3",
        0xd85 to "Cortex-X925", 0xd87 to "Cortex-A725", 0xd88 to "Cortex-A520AE",
    )

    @Volatile private var cached: CpuTopology? = null

    fun topology(): CpuTopology = cached ?: read().also { cached = it }

    fun possibleCpus(): List<Int> {
        val list = SysFs.parseCpuList(SysFs.readLine("$CPU_DIR/possible"))
        return list.ifEmpty { (0 until Runtime.getRuntime().availableProcessors()).toList() }
    }

    fun onlineCpus(): List<Int> = SysFs.parseCpuList(SysFs.readLine("$CPU_DIR/online"))

    fun currentFreqKhz(cpu: Int): Long? =
        SysFs.readLong("$CPU_DIR/cpu$cpu/cpufreq/scaling_cur_freq")

    fun scalingMaxKhz(cpu: Int): Long? = SysFs.readLong("$CPU_DIR/cpu$cpu/cpufreq/scaling_max_freq")

    /** time_in_state: frequency (kHz) → time in 10 ms units. Often SELinux-restricted. */
    fun timeInState(cpu: Int): Map<Long, Long>? {
        val text = SysFs.readText("$CPU_DIR/cpu$cpu/cpufreq/stats/time_in_state") ?: return null
        return text.lineSequence().mapNotNull { line ->
            val p = line.trim().split(Regex("\\s+"))
            if (p.size == 2) p[0].toLongOrNull()?.let { f -> p[1].toLongOrNull()?.let { f to it } } else null
        }.toMap().ifEmpty { null }
    }

    private fun read(): CpuTopology {
        val cpuinfo = SysFs.readText("/proc/cpuinfo") ?: ""
        val partByCpu = HashMap<Int, Int>()
        var current = -1
        var features: List<String> = emptyList()
        var hardware: String? = null
        cpuinfo.lineSequence().forEach { raw ->
            val idx = raw.indexOf(':')
            if (idx < 0) return@forEach
            val key = raw.substring(0, idx).trim()
            val value = raw.substring(idx + 1).trim()
            when (key) {
                "processor" -> current = value.toIntOrNull() ?: -1
                "CPU part" -> if (current >= 0) value.removePrefix("0x").toIntOrNull(16)?.let { partByCpu[current] = it }
                "Features" -> if (features.isEmpty()) features = value.split(' ').filter { it.isNotBlank() }
                "Hardware" -> hardware = value
            }
        }

        val cpus = possibleCpus()
        val cores = cpus.map { cpu ->
            val base = "$CPU_DIR/cpu$cpu/cpufreq"
            val part = partByCpu[cpu]
            CpuCore(
                id = cpu,
                clusterIndex = -1,
                minKhz = SysFs.readLong("$base/cpuinfo_min_freq") ?: 0,
                maxKhz = SysFs.readLong("$base/cpuinfo_max_freq") ?: 0,
                partId = part,
                microarchitecture = part?.let { armParts[it] } ?: "Unknown",
                governor = SysFs.readLine("$base/scaling_governor"),
                availableFrequenciesKhz = SysFs.readLine("$base/scaling_available_frequencies")
                    ?.split(' ')?.mapNotNull { it.toLongOrNull() } ?: emptyList(),
            )
        }

        // Group cores into clusters by policy (related_cpus) or, failing that, by max frequency.
        val groups = LinkedHashMap<String, MutableList<CpuCore>>()
        cores.forEach { core ->
            val related = SysFs.readLine("$CPU_DIR/cpu${core.id}/cpufreq/related_cpus")
            val key = related ?: "max:${core.maxKhz}:${core.microarchitecture}"
            groups.getOrPut(key) { mutableListOf() }.add(core)
        }
        val sortedGroups = groups.values.sortedBy { g -> g.maxOf { it.maxKhz } }
        val clusterNames = clusterNames(sortedGroups.size)
        val clusters = sortedGroups.mapIndexed { i, g ->
            val arch = g.first().microarchitecture
            CpuCluster(
                index = i,
                name = "${clusterNames[i]} · ${g.size}× $arch",
                cores = g.map { it.id },
                maxKhz = g.maxOf { it.maxKhz },
                minKhz = g.minOf { it.minKhz },
            )
        }
        val withCluster = cores.map { c -> c.copy(clusterIndex = clusters.first { c.id in it.cores }.index) }

        val soc = Build.SOC_MODEL
        val tensorG4 = soc.contains("G4", ignoreCase = true) || soc.equals("zumapro", true) ||
            (Build.MODEL.contains("Pixel 9", true) && withCluster.any { it.partId == 0xd82 })

        return CpuTopology(
            socModel = soc.takeUnless { it == Build.UNKNOWN } ?: (hardware ?: "Unknown"),
            socManufacturer = Build.SOC_MANUFACTURER,
            hardware = hardware,
            cores = withCluster,
            clusters = clusters,
            abis = Build.SUPPORTED_ABIS.toList(),
            features = features,
            isTensorG4 = tensorG4,
        )
    }

    private fun clusterNames(n: Int): List<String> = when (n) {
        1 -> listOf("All cores")
        2 -> listOf("Efficiency", "Performance")
        3 -> listOf("Efficiency", "Performance", "Prime")
        else -> List(n) { "Cluster $it" }
    }
}
