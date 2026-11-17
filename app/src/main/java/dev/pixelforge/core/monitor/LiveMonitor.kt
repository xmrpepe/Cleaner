package dev.pixelforge.core.monitor

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.os.storage.StorageManager
import dev.pixelforge.core.settings.SettingsRepository
import dev.pixelforge.core.shizuku.Capabilities
import dev.pixelforge.core.shizuku.PrivilegedExec
import dev.pixelforge.core.shizuku.ShizukuManager
import dev.pixelforge.core.util.SysFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StorageSample(val totalBytes: Long, val freeBytes: Long) {
    val usedBytes: Long get() = totalBytes - freeBytes
    val usedFraction: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

data class GpuSample(val freqHz: Long?, val utilizationPct: Int?, val source: String?)

data class SystemSnapshot(
    val time: Long,
    val uptimeMs: Long,
    val cpu: CpuSample,
    val memory: MemorySample,
    val battery: BatterySample,
    val thermal: ThermalSample,
    val storage: StorageSample,
    val gpu: GpuSample,
)

data class MemoryEvent(val time: Long, val kind: String, val detail: String)

/**
 * Event-driven/adaptive live sampler. It only runs while something collects [snapshots]
 * (UI visible); sampling stops 3 s after the last collector leaves, so PixelForge costs
 * nothing in the background.
 */
class LiveMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val shizuku: ShizukuManager,
    private val capabilities: Capabilities,
    private val privileged: PrivilegedExec,
) {
    val topology: CpuTopology by lazy { CpuInfoReader.topology() }
    private val cpuSampler = CpuSampler { path -> shizuku.readFileBlocking(path) }
    private val memoryReader = MemoryReader(context) { path -> shizuku.readFileBlocking(path) }
    val batteryReader = BatteryReader(context)
    private val thermalReader = ThermalReader(context)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val storageStats = context.getSystemService(StorageStatsManager::class.java)

    private val _history = MutableStateFlow<List<SystemSnapshot>>(emptyList())
    val history: StateFlow<List<SystemSnapshot>> = _history.asStateFlow()

    private val _memoryEvents = MutableStateFlow<List<MemoryEvent>>(emptyList())
    val memoryEvents: StateFlow<List<MemoryEvent>> = _memoryEvents.asStateFlow()

    private var lastStorage: StorageSample? = null
    private var lastStorageAt = 0L
    @Volatile private var sensors: List<SensorTemperature> = emptyList()
    private var sensorsAt = 0L
    private var lastPressure = 0
    private var gpuPaths: Pair<String?, String?>? = null
    private var gpuDiscoveredAt = 0L

    val snapshots: StateFlow<SystemSnapshot?> = flow {
        if (capabilities.current().shizukuReady) shizuku.warmUp()
        while (true) {
            val s = sample()
            emit(s)
            val cfg = settings.current()
            var interval = cfg.monitorIntervalMs.coerceIn(500, 10_000)
            if (cfg.adaptiveMonitoring) {
                // Back off under thermal pressure or Battery Saver to keep our own overhead negligible.
                if (s.thermal.status >= PowerManager.THERMAL_STATUS_MODERATE || pm.isPowerSaveMode) interval *= 2
            }
            delay(interval)
        }
    }.flowOn(Dispatchers.Default)
        .onEach { snap ->
            _history.update { (it + snap).takeLast(HISTORY) }
            trackMemoryEvents(snap)
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(3000), null)

    fun recordTrimMemory(level: Int) {
        val label = when (level) {
            80 -> "COMPLETE"; 60 -> "MODERATE"; 40 -> "BACKGROUND"; 20 -> "UI_HIDDEN"
            15 -> "RUNNING_CRITICAL"; 10 -> "RUNNING_LOW"; 5 -> "RUNNING_MODERATE"; else -> "level $level"
        }
        if (level == 20) return // UI hidden is not a pressure signal
        addEvent("onTrimMemory", "System asked PixelForge to trim memory ($label)")
    }

    private fun trackMemoryEvents(s: SystemSnapshot) {
        val p = s.memory.pressureLevel
        if (s.memory.lowMemory) addEvent("lowMemory", "System reported low-memory state (available below LMK threshold)")
        else if (p >= 2 && lastPressure < 2) addEvent("pressure", "Memory pressure rose to level $p")
        lastPressure = p
    }

    private fun addEvent(kind: String, detail: String) {
        val now = System.currentTimeMillis()
        _memoryEvents.update { list ->
            if (list.lastOrNull()?.let { it.kind == kind && now - it.time < 60_000 } == true) list
            else (list + MemoryEvent(now, kind, detail)).takeLast(100)
        }
    }

    /** Takes a single sample synchronously (used by workers, optimiser and benchmarks). */
    fun sample(): SystemSnapshot {
        val now = System.currentTimeMillis()
        if (lastStorage == null || now - lastStorageAt > 10_000) {
            lastStorage = readStorage()
            lastStorageAt = now
        }
        if (capabilities.current().canDump && now - sensorsAt > 5_000) {
            sensorsAt = now
            // Refresh HAL sensor temperatures asynchronously so sampling never blocks on dumpsys.
            scope.launch(Dispatchers.IO) {
                privileged.dumpsys("thermalservice", 5000)
                    ?.let { ThermalReader.parseThermalService(it) }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { sensors = it }
            }
        }
        return SystemSnapshot(
            time = now,
            uptimeMs = SystemClock.elapsedRealtime(),
            cpu = cpuSampler.sample(topology),
            memory = memoryReader.sample(),
            battery = batteryReader.sample(),
            thermal = thermalReader.sample(sensors),
            storage = lastStorage!!,
            gpu = readGpu(),
        )
    }

    fun readStorage(): StorageSample = try {
        StorageSample(
            totalBytes = storageStats.getTotalBytes(StorageManager.UUID_DEFAULT),
            freeBytes = storageStats.getFreeBytes(StorageManager.UUID_DEFAULT),
        )
    } catch (_: Throwable) {
        val f = context.filesDir
        StorageSample(f.totalSpace, f.usableSpace)
    }

    private fun readGpu(): GpuSample {
        val now = System.currentTimeMillis()
        val cached = gpuPaths
        val paths = if (cached != null && (cached.first != null || cached.second != null || now - gpuDiscoveredAt < 60_000)) {
            cached
        } else {
            gpuDiscoveredAt = now
            discoverGpuPaths().also { gpuPaths = it }
        }
        val freq = paths.first?.let { p -> (SysFs.readLine(p) ?: shizuku.readFileBlocking(p, 64)?.trim())?.toLongOrNull() }
        val util = paths.second?.let { p -> (SysFs.readLine(p) ?: shizuku.readFileBlocking(p, 64)?.trim())?.substringBefore(' ')?.toIntOrNull() }
        return GpuSample(
            freqHz = freq?.let { if (it < 10_000_000) it * 1000 else it }, // Mali reports kHz on some kernels
            utilizationPct = util,
            source = if (freq != null || util != null) "sysfs (Mali kbase)" else null,
        )
    }

    private fun discoverGpuPaths(): Pair<String?, String?> {
        val roots = listOf(
            "/sys/devices/platform/1f000000.mali",
            "/sys/devices/platform/1c500000.mali",
            "/sys/class/misc/mali0/device",
        )
        for (root in roots) {
            val f = "$root/cur_freq"
            val u = "$root/utilization"
            val freqReadable = SysFs.readLine(f) != null || shizuku.readFileBlocking(f, 64) != null
            val utilReadable = SysFs.readLine(u) != null || shizuku.readFileBlocking(u, 64) != null
            if (freqReadable || utilReadable) return (if (freqReadable) f else null) to (if (utilReadable) u else null)
        }
        return null to null
    }

    fun resetGpuDiscovery() {
        gpuPaths = null
    }

    companion object {
        const val HISTORY = 180
    }
}
