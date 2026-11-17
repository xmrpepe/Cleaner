package dev.pixelforge.core.bench

import android.content.Context
import android.os.Build
import android.os.PowerManager
import dev.pixelforge.core.data.BenchmarkDao
import dev.pixelforge.core.data.BenchmarkRun
import dev.pixelforge.core.gpu.GlesSession
import dev.pixelforge.core.monitor.BatteryReader
import dev.pixelforge.core.monitor.CpuInfoReader
import dev.pixelforge.core.nativebridge.NativeLab
import dev.pixelforge.core.settings.SettingsRepository
import dev.pixelforge.core.shizuku.PrivilegedExec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.sqrt

data class BenchProgress(
    val running: Boolean = false,
    val test: BenchTest? = null,
    val step: String = "",
    val fraction: Float = 0f,
    val liveSeries: List<Double> = emptyList(),
    val lastOutcome: BenchOutcome? = null,
    val error: String? = null,
    val thermalAbort: Boolean = false,
)

class ThermalAbortException(msg: String) : Exception(msg)

/**
 * Runs benchmarks one at a time on background threads, records test conditions (battery /
 * thermal before & after) and stops immediately when the thermal guard trips.
 */
class BenchmarkRunner(
    private val context: Context,
    private val dao: BenchmarkDao,
    private val settings: SettingsRepository,
    private val privileged: PrivilegedExec,
) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private val battery = BatteryReader(context)
    private val glThread = Executors.newSingleThreadExecutor { r -> Thread(r, "pf-gles") }.asCoroutineDispatcher()

    private val _progress = MutableStateFlow(BenchProgress())
    val progress: StateFlow<BenchProgress> = _progress.asStateFlow()

    fun cancel() = NativeLab.setCancelled(true)

    private fun step(test: BenchTest, text: String, fraction: Float) {
        _progress.value = _progress.value.copy(running = true, test = test, step = text, fraction = fraction)
    }

    private suspend fun checkThermal() {
        if (!settings.current().thermalGuard) return
        val status = pm.currentThermalStatus
        if (status >= PowerManager.THERMAL_STATUS_SEVERE) {
            NativeLab.setCancelled(true)
            throw ThermalAbortException("Stopped: thermal status ${status} (severe or worse). PixelForge never bypasses thermal protection.")
        }
    }

    private fun conditionsStart() = battery.sample() to (pm.currentThermalStatus to pm.getThermalHeadroom(0).takeUnless { it.isNaN() })

    private fun conditions(start: Pair<dev.pixelforge.core.monitor.BatterySample, Pair<Int, Float?>>): Conditions {
        val end = battery.sample()
        val (b0, t0) = start
        return Conditions(
            b0.temperatureC, end.temperatureC, t0.first, pm.currentThermalStatus, t0.second,
            pm.getThermalHeadroom(0).takeUnless { it.isNaN() }, b0.levelPct, end.levelPct,
            b0.chargeCounterUah, end.chargeCounterUah, b0.isCharging || end.isCharging, pm.isPowerSaveMode,
        )
    }

    suspend fun run(test: BenchTest, launchPackages: List<String> = emptyList()): BenchOutcome? {
        if (_progress.value.running) return null
        NativeLab.setCancelled(false)
        _progress.value = BenchProgress(running = true, test = test, step = "Preparing")
        val started = System.currentTimeMillis()
        val start = conditionsStart()
        return try {
            checkThermal()
            val outcome = when (test) {
                BenchTest.CPU_SINGLE -> cpuSingle()
                BenchTest.CPU_MULTI -> cpuMulti()
                BenchTest.MEM_BANDWIDTH -> memBandwidth()
                BenchTest.MEM_LATENCY -> memLatency()
                BenchTest.STORAGE -> storage()
                BenchTest.GPU_GLES -> gles()
                BenchTest.GPU_VULKAN -> vulkan()
                BenchTest.SUSTAINED_CPU -> sustained(test, gpu = false)
                BenchTest.SUSTAINED_GPU -> sustained(test, gpu = true)
                BenchTest.APP_LAUNCH -> appLaunch(launchPackages)
            }
            val result = BenchOutcome(test, outcome.first, outcome.second, conditions(start), System.currentTimeMillis() - started, outcome.third.first, outcome.third.second)
            dao.insert(
                BenchmarkRun(
                    time = started, testId = test.id, testVersion = test.version, score = result.score,
                    durationMs = result.durationMs, metricsJson = result.metricsJson(),
                    conditionsJson = result.conditions.toJson().apply { result.note?.let { put("note", it) } }.toString(),
                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                ),
            )
            _progress.value = BenchProgress(lastOutcome = result)
            result
        } catch (t: ThermalAbortException) {
            _progress.value = BenchProgress(error = t.message, thermalAbort = true)
            null
        } catch (t: CancellationException) {
            NativeLab.setCancelled(true)
            _progress.value = BenchProgress(error = "Cancelled")
            throw t
        } catch (t: Throwable) {
            _progress.value = BenchProgress(error = t.message ?: t.javaClass.simpleName)
            null
        } finally {
            NativeLab.setCancelled(false)
        }
    }

    private suspend fun seconds(): Int = settings.current().benchmarkSeconds.coerceIn(1, 15)

    private fun ops(o: JSONObject) = o.optDouble("opsPerSecond", 0.0)

    // ---- CPU --------------------------------------------------------------------------------

    private suspend fun cpuSingle(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.Default) {
        val topo = CpuInfoReader.topology()
        val ms = seconds() * 1000
        val prime = topo.primeCluster
        val mask = prime?.let { 1L shl it.cores.first() } ?: 0L
        val metrics = mutableListOf<Metric>()
        step(BenchTest.CPU_SINGLE, "Integer · ${prime?.name ?: "any core"}", 0.05f)
        val int = ops(NativeLab.cpuBench(NativeLab.CPU_INTEGER, 1, ms, mask)); checkThermal()
        step(BenchTest.CPU_SINGLE, "FP32 SGEMM", 0.3f)
        val fp = ops(NativeLab.cpuBench(NativeLab.CPU_FLOAT, 1, ms, mask)); checkThermal()
        step(BenchTest.CPU_SINGLE, "Sort (branchy)", 0.55f)
        val sort = ops(NativeLab.cpuBench(NativeLab.CPU_SORT, 1, ms, mask)); checkThermal()
        metrics += Metric("Integer mix", int / 1e9, "Gops/s")
        metrics += Metric("FP32 SGEMM", fp / 1e9, "GFLOPS")
        metrics += Metric("Sort", sort / 1e6, "M elem/s")
        // Per-cluster integer throughput (1 core each) — shows Tensor G4's X4 / A720 / A520 split.
        topo.clusters.forEachIndexed { i, c ->
            coroutineContext.ensureActive()
            step(BenchTest.CPU_SINGLE, "Cluster ${c.name}", 0.75f + 0.25f * i / topo.clusters.size)
            val r = ops(NativeLab.cpuBench(NativeLab.CPU_INTEGER, 1, (ms / 2).coerceAtLeast(500), 1L shl c.cores.first()))
            metrics += Metric("Int 1-core · ${c.name}", r / 1e9, "Gops/s")
        }
        val score = 1000.0 * geomean(listOf(int / REF_INT_1T, fp / REF_FP_1T, sort / REF_SORT_1T))
        Triple(score, metrics, emptyList<Double>() to null)
    }

    private suspend fun cpuMulti(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.Default) {
        val n = CpuInfoReader.topology().coreCount.coerceAtLeast(1)
        val ms = seconds() * 1000
        step(BenchTest.CPU_MULTI, "Integer × $n", 0.05f)
        val int = ops(NativeLab.cpuBench(NativeLab.CPU_INTEGER, n, ms)); checkThermal()
        step(BenchTest.CPU_MULTI, "FP32 SGEMM × $n", 0.35f)
        val fp = ops(NativeLab.cpuBench(NativeLab.CPU_FLOAT, n, ms)); checkThermal()
        step(BenchTest.CPU_MULTI, "Sort × $n", 0.7f)
        val sort = ops(NativeLab.cpuBench(NativeLab.CPU_SORT, n, ms))
        val metrics = listOf(
            Metric("Integer mix", int / 1e9, "Gops/s"),
            Metric("FP32 SGEMM", fp / 1e9, "GFLOPS"),
            Metric("Sort", sort / 1e6, "M elem/s"),
            Metric("Threads", n.toDouble(), "threads"),
        )
        Triple(1000.0 * geomean(listOf(int / REF_INT_MT, fp / REF_FP_MT, sort / REF_SORT_MT)), metrics, emptyList<Double>() to null)
    }

    // ---- Memory & storage -------------------------------------------------------------------

    private suspend fun memBandwidth(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.Default) {
        step(BenchTest.MEM_BANDWIDTH, "Streaming 64 MiB buffers", 0.1f)
        val o = NativeLab.memBandwidth(64L shl 20, seconds() * 1500)
        o.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
        val copy = o.optDouble("copyGBs"); val read = o.optDouble("readGBs"); val write = o.optDouble("writeGBs")
        Triple(
            1000.0 * geomean(listOf(copy / REF_BW, read / REF_BW, write / REF_BW)),
            listOf(Metric("Copy", copy, "GB/s"), Metric("Read", read, "GB/s"), Metric("Write", write, "GB/s")),
            emptyList<Double>() to null,
        )
    }

    private suspend fun memLatency(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.Default) {
        step(BenchTest.MEM_LATENCY, "Pointer chasing 8 KiB → 128 MiB", 0.1f)
        val o = NativeLab.memLatency(128L shl 20)
        val pts = o.optJSONArray("points") ?: error("No data")
        val metrics = (0 until pts.length()).map { i ->
            val p = pts.getJSONObject(i)
            val bytes = p.getLong("bytes")
            val label = if (bytes >= 1 shl 20) "${bytes shr 20} MiB" else "${bytes shr 10} KiB"
            Metric("Latency @ $label", p.getDouble("ns"), "ns", higherIsBetter = false)
        }
        val dram = metrics.lastOrNull()?.value ?: 0.0
        val l1 = metrics.firstOrNull()?.value ?: 0.0
        val score = if (dram > 0) 1000.0 * geomean(listOf(REF_LAT_DRAM / dram, REF_LAT_L1 / l1.coerceAtLeast(0.01))) else 0.0
        Triple(score, metrics, metrics.map { it.value } to null)
    }

    private suspend fun storage(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.IO) {
        step(BenchTest.STORAGE, "Writing 512 MiB test file", 0.1f)
        val o = NativeLab.storageBench(context.filesDir.absolutePath, 512)
        o.optString("error").takeIf { it.isNotEmpty() }?.let { error(it) }
        val w = o.optDouble("seqWriteMBs"); val r = o.optDouble("seqReadMBs"); val iops = o.optDouble("rand4kReadIops")
        val direct = o.optBoolean("directIo")
        Triple(
            1000.0 * geomean(listOf(w / REF_SEQ_W, r / REF_SEQ_R, iops / REF_IOPS)),
            listOf(Metric("Sequential write (fsync)", w, "MB/s"), Metric("Sequential read", r, "MB/s"), Metric("Random 4K read", iops, "IOPS")),
            emptyList<Double>() to if (direct) "Reads used O_DIRECT (page cache bypassed)" else "O_DIRECT unavailable: reads may include page-cache effects",
        )
    }

    // ---- GPU --------------------------------------------------------------------------------

    private suspend fun gles(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(glThread) {
        val ms = seconds() * 1000
        val session = GlesSession.create() ?: error("Could not create an OpenGL ES 3 context")
        try {
            step(BenchTest.GPU_GLES, "Fill rate", 0.05f)
            val fill = session.fillRate(ms); checkThermal()
            step(BenchTest.GPU_GLES, "Geometry", 0.3f)
            val geo = session.geometry(ms); checkThermal()
            step(BenchTest.GPU_GLES, "Texturing", 0.55f)
            val tex = session.texture(ms); checkThermal()
            step(BenchTest.GPU_GLES, "Compute (ES 3.1)", 0.8f)
            val comp = session.compute(ms)
            val parts = listOfNotNull(fill.value / REF_GL_FILL, geo.value / REF_GL_GEO, tex.value / REF_GL_TEX, comp?.let { it.value / REF_GL_COMP })
            val metrics = mutableListOf(
                Metric("Fill rate", fill.value, fill.unit), Metric("Geometry", geo.value, geo.unit), Metric("Texturing", tex.value, tex.unit),
            )
            comp?.let { metrics += Metric("Compute", it.value, it.unit) }
            val ft = fill.frameMs
            metrics += Metric("Fill frame-time p50", percentile(ft, 0.5), "ms", false)
            metrics += Metric("Fill frame-time p99", percentile(ft, 0.99), "ms", false)
            metrics += Metric("Frame-time stability", stability(ft), "%")
            Triple(1000.0 * geomean(parts), metrics, ft to null)
        } finally {
            session.close()
        }
    }

    private suspend fun vulkan(): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.Default) {
        val ms = seconds() * 1000
        step(BenchTest.GPU_VULKAN, "Vulkan fill (1080p, 32 blended layers)", 0.1f)
        val fill = NativeLab.vulkanFill(ms, 1920, 1080)
        if (!fill.optBoolean("available")) error(fill.optString("error", "Vulkan unavailable"))
        checkThermal()
        step(BenchTest.GPU_VULKAN, "Vulkan compute (FMA)", 0.55f)
        val comp = NativeLab.vulkanCompute(ms)
        val gpix = fill.optDouble("gpixelsPerSecond")
        val gflops = if (comp.optBoolean("available")) comp.optDouble("gflops") else 0.0
        val frames = fill.optJSONArray("frameMs")?.let { a -> (0 until a.length()).map { a.getDouble(it) } } ?: emptyList()
        val metrics = listOf(
            Metric("Fill rate", gpix, "Gpix/s"),
            Metric("Compute", gflops, "GFLOPS"),
            Metric("Frame-time p50", percentile(frames, 0.5), "ms", false),
            Metric("Frame-time p99", percentile(frames, 0.99), "ms", false),
            Metric("Frame-time stability", stability(frames), "%"),
        )
        val parts = listOfNotNull(gpix / REF_VK_FILL, gflops.takeIf { it > 0 }?.let { it / REF_VK_COMP })
        Triple(1000.0 * geomean(parts), metrics, frames to null)
    }

    // ---- Sustained --------------------------------------------------------------------------

    private suspend fun sustained(test: BenchTest, gpu: Boolean): Triple<Double, List<Metric>, Pair<List<Double>, String?>> = withContext(Dispatchers.Default) {
        val minutes = settings.current().sustainedMinutes.coerceIn(1, 30)
        val windowMs = 5000
        val windows = minutes * 60_000 / windowMs
        val n = CpuInfoReader.topology().coreCount
        val series = ArrayList<Double>()
        val temps = ArrayList<Double>()
        val statuses = ArrayList<Int>()
        for (w in 0 until windows) {
            coroutineContext.ensureActive()
            checkThermal()
            val v = if (gpu) {
                NativeLab.vulkanFill(windowMs, 1920, 1080).optDouble("gpixelsPerSecond", 0.0)
            } else {
                ops(NativeLab.cpuBench(NativeLab.CPU_INTEGER, n, windowMs)) / 1e9
            }
            series += v
            temps += battery.sample().temperatureC.toDouble()
            statuses += pm.currentThermalStatus
            _progress.value = _progress.value.copy(
                running = true, test = test, step = "Window ${w + 1}/$windows · status ${statuses.last()}",
                fraction = (w + 1f) / windows, liveSeries = series.toList(),
            )
        }
        val peak = series.maxOrNull() ?: 0.0
        val low = series.minOrNull() ?: 0.0
        val last = series.takeLast(3).average()
        val stabilityPct = if (peak > 0) low / peak * 100 else 0.0
        val firstThrottle = series.indexOfFirst { it < peak * 0.9 }.takeIf { it >= 0 }
        val unit = if (gpu) "Gpix/s" else "Gops/s"
        val metrics = listOf(
            Metric("Peak", peak, unit), Metric("Lowest", low, unit), Metric("Final (avg last 3)", last, unit),
            Metric("Stability (lowest / peak)", stabilityPct, "%"),
            Metric("Time to −10%", firstThrottle?.let { (it * windowMs / 1000).toDouble() } ?: (windows * windowMs / 1000).toDouble(), "s"),
            Metric("Max battery temp", temps.maxOrNull() ?: 0.0, "°C", false),
            Metric("Max thermal status", (statuses.maxOrNull() ?: 0).toDouble(), "level", false),
        )
        val ref = if (gpu) REF_VK_FILL else REF_INT_MT / 1e9
        Triple(1000.0 * (last / ref) * (stabilityPct / 100.0).coerceAtLeast(0.01).let { sqrt(it) }, metrics, series to null)
    }

    // ---- App launch -------------------------------------------------------------------------

    private suspend fun appLaunch(packages: List<String>): Triple<Double, List<Metric>, Pair<List<Double>, String?>> {
        require(packages.isNotEmpty()) { "Select at least one app" }
        val metrics = mutableListOf<Metric>()
        val selfComponent = "${context.packageName}/dev.pixelforge.MainActivity"
        packages.forEachIndexed { i, pkg ->
            coroutineContext.ensureActive()
            require(PrivilegedExec.isValidPackageName(pkg))
            step(BenchTest.APP_LAUNCH, "Cold start $pkg", i.toFloat() / packages.size)
            val component = privileged.shell("cmd package resolve-activity --brief $pkg | tail -n 1", "Resolve launcher of $pkg", audit = false)?.output?.trim()
                ?: error("Shizuku required for launch timing")
            if (!component.contains('/')) error("No launcher activity for $pkg")
            val r = privileged.shell("am start -W -S -n $component", "Cold-launch timing for $pkg", timeoutMs = 30_000)
                ?: error("Shizuku required for launch timing")
            val total = Regex("TotalTime: (\\d+)").find(r.output)?.groupValues?.get(1)?.toDoubleOrNull()
            if (total != null) metrics += Metric(pkg, total, "ms", higherIsBetter = false)
            privileged.shell("am start -n $selfComponent", "Return to PixelForge", audit = false)
            kotlinx.coroutines.delay(1500)
        }
        if (metrics.isEmpty()) error("No timing reported by the activity manager")
        val avg = metrics.map { it.value }.average()
        return Triple(1000.0 * REF_LAUNCH_MS / avg, metrics + Metric("Average", avg, "ms", false), emptyList<Double>() to null)
    }

    // ---- Helpers ----------------------------------------------------------------------------

    suspend fun previousRuns(test: BenchTest) = dao.history(test.id, test.version)

    /** Regression if this score is >10 % below the median of up to 10 earlier compatible runs. */
    suspend fun regression(test: BenchTest, score: Double): Double? {
        val prev = dao.history(test.id, test.version, 11).drop(1).map { it.score }
        if (prev.size < 2) return null
        val median = prev.sorted()[prev.size / 2]
        val delta = (score - median) / median
        return if (delta < -0.10) delta else null
    }

    companion object {
        // Fixed normalisation constants (v1). Not device claims: they only scale scores.
        const val REF_INT_1T = 4.0e9; const val REF_FP_1T = 8.0e9; const val REF_SORT_1T = 60.0e6
        const val REF_INT_MT = 20.0e9; const val REF_FP_MT = 40.0e9; const val REF_SORT_MT = 300.0e6
        const val REF_BW = 20.0; const val REF_LAT_DRAM = 150.0; const val REF_LAT_L1 = 1.5
        const val REF_SEQ_W = 1000.0; const val REF_SEQ_R = 1500.0; const val REF_IOPS = 15_000.0
        const val REF_GL_FILL = 5.0; const val REF_GL_GEO = 1000.0; const val REF_GL_TEX = 20.0; const val REF_GL_COMP = 500.0
        const val REF_VK_FILL = 5.0; const val REF_VK_COMP = 800.0
        const val REF_LAUNCH_MS = 500.0

        fun geomean(values: List<Double>): Double {
            val v = values.filter { it > 0 && !it.isNaN() && !it.isInfinite() }
            if (v.isEmpty()) return 0.0
            return Math.exp(v.sumOf { Math.log(it) } / v.size)
        }

        fun percentile(values: List<Double>, p: Double): Double {
            if (values.isEmpty()) return 0.0
            val s = values.sorted()
            return s[((s.size - 1) * p).toInt().coerceIn(0, s.lastIndex)]
        }

        /** 100 × median / p99 frame time: 100 % means perfectly even frame pacing. */
        fun stability(frames: List<Double>): Double {
            if (frames.size < 3) return 0.0
            val p99 = percentile(frames, 0.99)
            return if (p99 > 0) percentile(frames, 0.5) / p99 * 100 else 0.0
        }
    }
}
