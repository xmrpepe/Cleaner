package dev.pixelforge.core.bench

import org.json.JSONArray
import org.json.JSONObject

data class Metric(val name: String, val value: Double, val unit: String, val higherIsBetter: Boolean = true)

data class Conditions(
    val batteryTempStartC: Float,
    val batteryTempEndC: Float,
    val thermalStatusStart: Int,
    val thermalStatusEnd: Int,
    val headroomStart: Float?,
    val headroomEnd: Float?,
    val batteryStartPct: Int,
    val batteryEndPct: Int,
    val chargeStartUah: Long?,
    val chargeEndUah: Long?,
    val charging: Boolean,
    val powerSave: Boolean,
) {
    /** Measured charge consumed during the test (fuel-gauge resolution applies). */
    val consumedMah: Double?
        get() = if (chargeStartUah != null && chargeEndUah != null && !charging) (chargeStartUah - chargeEndUah) / 1000.0 else null

    fun toJson(): JSONObject = JSONObject()
        .put("batteryTempStartC", batteryTempStartC.toDouble()).put("batteryTempEndC", batteryTempEndC.toDouble())
        .put("thermalStatusStart", thermalStatusStart).put("thermalStatusEnd", thermalStatusEnd)
        .put("headroomStart", headroomStart?.toDouble()).put("headroomEnd", headroomEnd?.toDouble())
        .put("batteryStartPct", batteryStartPct).put("batteryEndPct", batteryEndPct)
        .put("chargeStartUah", chargeStartUah).put("chargeEndUah", chargeEndUah)
        .put("charging", charging).put("powerSave", powerSave).put("consumedMah", consumedMah)

    companion object {
        fun fromJson(o: JSONObject) = Conditions(
            o.optDouble("batteryTempStartC").toFloat(), o.optDouble("batteryTempEndC").toFloat(),
            o.optInt("thermalStatusStart"), o.optInt("thermalStatusEnd"),
            o.optDouble("headroomStart").takeUnless { it.isNaN() }?.toFloat(), o.optDouble("headroomEnd").takeUnless { it.isNaN() }?.toFloat(),
            o.optInt("batteryStartPct"), o.optInt("batteryEndPct"),
            if (o.isNull("chargeStartUah")) null else o.optLong("chargeStartUah"),
            if (o.isNull("chargeEndUah")) null else o.optLong("chargeEndUah"),
            o.optBoolean("charging"), o.optBoolean("powerSave"),
        )
    }
}

/**
 * Benchmark catalogue. [version] is bumped whenever the workload or score formula changes so
 * results from incompatible versions are never compared. Scores are 1000 × measured/reference,
 * where the reference is a fixed normalisation constant (not a claim about any device).
 */
enum class BenchTest(val id: String, val title: String, val group: String, val version: Int, val description: String) {
    CPU_SINGLE("cpu_single", "CPU single-thread", "CPU", 1, "Integer, FP32 SGEMM and sort kernels on the fastest core, then one core of each cluster."),
    CPU_MULTI("cpu_multi", "CPU multi-thread", "CPU", 1, "The same kernels on all cores simultaneously."),
    MEM_BANDWIDTH("mem_bw", "Memory bandwidth", "Memory", 1, "64 MiB streaming copy / read / write (LPDDR5X)."),
    MEM_LATENCY("mem_lat", "Memory latency", "Memory", 1, "Random pointer-chase from 8 KiB to 128 MiB: shows L1, L2, L3/SLC and DRAM."),
    STORAGE("storage", "Storage throughput", "Storage", 1, "Sequential write+fsync, direct-I/O sequential read and random 4 KiB reads."),
    GPU_GLES("gpu_gles", "OpenGL ES graphics", "GPU", 1, "Offscreen 1080p fill-rate, geometry, texturing and ES 3.1 compute."),
    GPU_VULKAN("gpu_vulkan", "Vulkan graphics & compute", "GPU", 1, "Offscreen 1080p blended fill and FMA compute throughput via Vulkan."),
    SUSTAINED_CPU("sustained_cpu", "Sustained CPU", "Sustained", 1, "All-core load in repeated windows; tracks throttling and thermal status."),
    SUSTAINED_GPU("sustained_gpu", "Sustained GPU", "Sustained", 1, "Repeated Vulkan fill windows; tracks GPU throttling."),
    APP_LAUNCH("app_launch", "App launch (cold)", "Responsiveness", 1, "`am start -W -S` cold-start timing for selected apps (Shizuku)."),
}

data class BenchOutcome(
    val test: BenchTest,
    val score: Double,
    val metrics: List<Metric>,
    val conditions: Conditions,
    val durationMs: Long,
    val series: List<Double> = emptyList(),
    val note: String? = null,
) {
    fun metricsJson(): String = JSONArray().apply {
        metrics.forEach { put(JSONObject().put("name", it.name).put("value", it.value).put("unit", it.unit).put("higherIsBetter", it.higherIsBetter)) }
    }.toString()
}

fun parseMetrics(json: String): List<Metric> = try {
    val a = JSONArray(json)
    (0 until a.length()).map { i ->
        val o = a.getJSONObject(i)
        Metric(o.getString("name"), o.getDouble("value"), o.getString("unit"), o.optBoolean("higherIsBetter", true))
    }
} catch (_: Throwable) {
    emptyList()
}
