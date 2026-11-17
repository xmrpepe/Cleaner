package dev.pixelforge.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.data.TelemetrySample
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ArcGauge
import dev.pixelforge.ui.components.ChartSeries
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.LineChart
import dev.pixelforge.ui.components.MetricTile
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.Sparkline
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun BatteryScreen(nav: Navigator) {
    val c = LocalContainer.current
    val snap = rememberSnapshot()
    val history = rememberHistory()
    val caps = rememberCaps()
    val settings = rememberSettings()
    val ctx = LocalContext.current
    val samples by c.db.telemetry().observeSince(System.currentTimeMillis() - 48 * 3_600_000L).collectAsStateWithLifecycle(emptyList())
    val designMah by produceState<Int?>(null, settings.designCapacityMah) {
        value = settings.designCapacityMah.takeIf { it > 0 } ?: withContext(Dispatchers.Default) { designCapacityFromPowerProfile(ctx) }
    }
    val perApp by produceState<List<Pair<String, Double>>?>(null, caps.canDump) {
        if (caps.canDump) value = withContext(Dispatchers.IO) { c.privileged.dumpsys("batterystats --charged", 30_000)?.let { parseBatteryStats(it, ctx) } }
    }

    PfScreen("Battery", "Fuel-gauge data straight from BatteryManager", onBack = nav::back) {
        val b = snap?.battery ?: return@PfScreen
        item {
            PfCard(Modifier.fillMaxWidth(), highlight = b.isCharging) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArcGauge(b.levelPct / 100f, "${b.levelPct}%", b.statusLabel, size = 140.dp, color = if (b.levelPct <= 15) Pf.colors.danger else Pf.colors.success)
                    Spacer(Modifier.padding(8.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        KeyValue("Source", b.chargingSource)
                        KeyValue("Power", b.powerW?.let { "${"%.2f".format(abs(it))} W ${if (it > 0) "in" else "out"}" } ?: "Not reported")
                        KeyValue("Current", b.currentNowUa?.let { "${it / 1000} mA" } ?: "Not reported")
                        KeyValue("Voltage", "${b.voltageMv} mV")
                        KeyValue("Temperature", Format.celsius(b.temperatureC))
                        b.chargeTimeRemainingMs?.let { KeyValue("Full in", Format.duration(it)) }
                    }
                }
            }
        }
        item {
            TileGrid(4, 150.dp) { i, m ->
                when (i) {
                    0 -> MetricTile("Health", b.healthLabel, m, sub = b.stateOfHealthPct?.let { "State of health $it% (system)" } ?: "Android health status")
                    1 -> MetricTile("Cycle count", b.cycleCount?.toString() ?: "n/a", m, sub = "Reported by the fuel gauge")
                    2 -> MetricTile(
                        "Full capacity", b.estimatedFullCapacityMah?.let { "${it.toInt()} mAh" } ?: "n/a", m,
                        sub = designMah?.let { d -> b.estimatedFullCapacityMah?.let { "≈${(it / d * 100).toInt()}% of $d mAh design (estimate)" } ?: "Design $d mAh" } ?: "Estimate from charge counter",
                    )
                    else -> MetricTile("Battery Saver", if (b.powerSaveMode) "On" else "Off", m, sub = b.technology ?: "", onClick = { ctx.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) })
                }
            }
        }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Live power (W)", style = MaterialTheme.typography.titleSmall)
                Sparkline(history.mapNotNull { it.battery.powerW?.toFloat() }, color = Pf.colors.warning, height = 60.dp)
                Text("Positive = charging. Pixel reports instantaneous current from the fuel gauge.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
            }
        }
        item { SectionLabel("Discharge history (48 h)") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                if (samples.size < 2) {
                    Text("Collecting… PixelForge records a lightweight sample every ${settings.telemetryIntervalMin} min (Automation).", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                } else {
                    LineChart(
                        listOf(
                            ChartSeries("Level %", samples.map { it.batteryLevel.toFloat() }, Pf.colors.success),
                            ChartSeries("Temp °C", samples.map { it.batteryTempC }, Pf.colors.warning),
                        ),
                        minValue = 0f, maxValue = 100f,
                    )
                    val proj = projection(samples)
                    Spacer(Modifier.height(8.dp))
                    KeyValue("Discharge rate (unplugged)", proj?.let { "${"%.1f".format(it.first)} %/h" } ?: "Not enough unplugged data")
                    KeyValue("Projected endurance", proj?.second?.let { "≈ ${Format.duration((it * 3_600_000).toLong())} remaining (estimate)" } ?: "—")
                    val hot = samples.count { it.charging && it.batteryTempC >= 40f }
                    if (hot > 0) KeyValue("Hot while charging", "$hot sample(s) ≥ 40 °C", valueColor = Pf.colors.warning)
                }
            }
        }
        item { SectionLabel("App battery usage") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                if (!caps.canDump) {
                    Text("Per-app energy (mAh) comes from batterystats, which Android only exposes to privileged callers. Connect Shizuku or grant DUMP via ADB to see it. Otherwise use Android's battery usage screen.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                } else if (perApp == null) {
                    Text("Reading batterystats…", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                } else if (perApp.orEmpty().isEmpty()) {
                    Text("No per-app estimates since last full charge.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                } else {
                    perApp.orEmpty().take(15).forEach { (name, mah) -> KeyValue(name, "${"%.1f".format(mah)} mAh") }
                    Text("System estimates since last full charge (batterystats).", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { ctx.launch(Intent(Intent.ACTION_POWER_USAGE_SUMMARY)) }) { Text("Battery usage") }
                    OutlinedButton(onClick = { ctx.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("Optimisation list") }
                }
            }
        }
        item {
            NoticeCard(
                "Charging tips (Pixel)",
                "Pixel's Adaptive Charging and charging optimisation (limit to 80%) reduce time spent at 100% and battery wear. Heat is the main ageing factor — avoid gaming while fast-charging.",
                actionLabel = "Battery settings", onAction = { ctx.launch(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) },
            )
        }
    }
}

/** (%/h, hours remaining) from unplugged telemetry segments. */
private fun projection(samples: List<TelemetrySample>): Pair<Double, Double?>? {
    val unplugged = samples.takeLastWhile { !it.charging }
    if (unplugged.size < 3) return null
    val hours = (unplugged.last().time - unplugged.first().time) / 3_600_000.0
    val drop = unplugged.first().batteryLevel - unplugged.last().batteryLevel
    if (hours < 0.5 || drop <= 0) return null
    val rate = drop / hours
    return rate to unplugged.last().batteryLevel / rate
}

/** Design capacity from the framework power profile (best effort; null if hidden). */
private fun designCapacityFromPowerProfile(ctx: android.content.Context): Int? = try {
    val cls = Class.forName("com.android.internal.os.PowerProfile")
    val profile = cls.getConstructor(android.content.Context::class.java).newInstance(ctx)
    (cls.getMethod("getBatteryCapacity").invoke(profile) as Double).toInt().takeIf { it in 1000..10000 }
} catch (_: Throwable) {
    null
}

/** Parses "Estimated power use (mAh)" UID lines from `dumpsys batterystats`. */
fun parseBatteryStats(text: String, ctx: android.content.Context): List<Pair<String, Double>> {
    val section = text.substringAfter("Estimated power use (mAh):", "").substringBefore("\n\n")
    val re = Regex("""UID (u\d+a\d+|\d+): ([\d.]+)""")
    val pm = ctx.packageManager
    return re.findAll(section).mapNotNull { m ->
        val uidStr = m.groupValues[1]
        val mah = m.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
        val uid = Regex("""u(\d+)a(\d+)""").matchEntire(uidStr)?.let { it.groupValues[1].toInt() * 100000 + 10000 + it.groupValues[2].toInt() } ?: uidStr.toIntOrNull()
        val name = uid?.let { u -> pm.getPackagesForUid(u)?.firstOrNull()?.let { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) } } ?: "UID $uidStr"
        name to mah
    }.groupBy { it.first }.map { (k, v) -> k to v.sumOf { it.second } }.sortedByDescending { it.second }.toList()
}
