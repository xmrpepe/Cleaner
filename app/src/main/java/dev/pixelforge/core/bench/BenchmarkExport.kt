package dev.pixelforge.core.bench

import dev.pixelforge.core.data.BenchmarkRun
import org.json.JSONArray
import org.json.JSONObject

object BenchmarkExport {
    fun toJson(runs: List<BenchmarkRun>): String = JSONObject()
        .put("format", "pixelforge-benchmarks")
        .put("formatVersion", 1)
        .put("exportedAt", System.currentTimeMillis())
        .put("runs", JSONArray().apply {
            runs.forEach { r ->
                put(
                    JSONObject()
                        .put("time", r.time).put("testId", r.testId).put("testVersion", r.testVersion)
                        .put("score", r.score).put("durationMs", r.durationMs).put("device", r.deviceModel)
                        .put("metrics", JSONArray(r.metricsJson))
                        .put("conditions", JSONObject(r.conditionsJson)),
                )
            }
        }).toString(2)

    fun toCsv(runs: List<BenchmarkRun>): String {
        val sb = StringBuilder("time,test_id,test_version,score,duration_ms,device,metric,value,unit,battery_temp_start_c,battery_temp_end_c,thermal_start,thermal_end\n")
        runs.forEach { r ->
            val cond = runCatching { JSONObject(r.conditionsJson) }.getOrDefault(JSONObject())
            val metrics = parseMetrics(r.metricsJson).ifEmpty { listOf(Metric("", Double.NaN, "")) }
            metrics.forEach { m ->
                sb.append(r.time).append(',').append(r.testId).append(',').append(r.testVersion).append(',')
                    .append("%.2f".format(r.score)).append(',').append(r.durationMs).append(',').append(csv(r.deviceModel)).append(',')
                    .append(csv(m.name)).append(',').append(if (m.value.isNaN()) "" else m.value.toString()).append(',').append(csv(m.unit)).append(',')
                    .append(cond.optDouble("batteryTempStartC")).append(',').append(cond.optDouble("batteryTempEndC")).append(',')
                    .append(cond.optInt("thermalStatusStart")).append(',').append(cond.optInt("thermalStatusEnd")).append('\n')
            }
        }
        return sb.toString()
    }

    private fun csv(s: String) = if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s
}
