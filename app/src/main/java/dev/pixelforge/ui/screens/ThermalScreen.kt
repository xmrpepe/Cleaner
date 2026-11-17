package dev.pixelforge.ui.screens

import android.os.PowerManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.monitor.ThermalSample
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ArcGauge
import dev.pixelforge.ui.components.ChartSeries
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.LineChart
import dev.pixelforge.ui.components.MetricTile
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.SegmentBar
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf

@Composable
fun ThermalScreen(nav: Navigator) {
    val c = LocalContainer.current
    val snap = rememberSnapshot()
    val history = rememberHistory()
    val caps = rememberCaps()
    val samples by c.db.telemetry().observeSince(System.currentTimeMillis() - 24 * 3_600_000L).collectAsStateWithLifecycle(emptyList())

    PfScreen("Thermal", "Android thermal status, headroom and sensor temperatures", onBack = nav::back) {
        val t = snap?.thermal ?: return@PfScreen
        val b = snap.battery
        item {
            PfCard(Modifier.fillMaxWidth(), highlight = t.status >= PowerManager.THERMAL_STATUS_MODERATE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArcGauge(t.headroom?.coerceIn(0f, 1f), t.headroom?.let { "%.2f".format(it) } ?: "n/a", "Headroom", size = 140.dp, color = thermalColor(t.status), sub = "1.0 = severe")
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.statusLabel, style = MaterialTheme.typography.headlineSmall, color = thermalColor(t.status))
                        Text("Thermal status (PowerManager)", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                        Spacer(Modifier.height(8.dp))
                        KeyValue("Forecast (10 s)", t.headroomForecast10s?.let { "%.2f".format(it) } ?: "n/a")
                        KeyValue("Battery", Format.celsius(b.temperatureC))
                        KeyValue("Throttling", when {
                            t.status >= PowerManager.THERMAL_STATUS_SEVERE -> "Heavy — performance strongly limited"
                            t.status >= PowerManager.THERMAL_STATUS_MODERATE -> "Active — sustained performance reduced"
                            t.status == PowerManager.THERMAL_STATUS_LIGHT -> "Light — minor UX impact"
                            else -> "None"
                        })
                    }
                }
                Spacer(Modifier.height(10.dp))
                SegmentBar((0..6).map { 1f to if (it <= t.status) thermalColor(it) else Pf.colors.grid }, height = 8.dp)
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { Text(ThermalSample.statusLabel(it).take(4), style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.weight(1f)) }
                }
            }
        }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Live", style = MaterialTheme.typography.titleSmall)
                LineChart(
                    listOf(
                        ChartSeries("Battery °C", history.map { it.battery.temperatureC }, Pf.colors.warning),
                        ChartSeries("Headroom ×50", history.map { (it.thermal.headroom ?: 0f) * 50 }, Pf.colors.cyan),
                    ) + if (t.sensors.isNotEmpty()) listOf(ChartSeries("Max sensor °C", history.map { h -> h.thermal.sensors.maxOfOrNull { it.celsius } ?: 0f }, Pf.colors.danger)) else emptyList(),
                    minValue = 15f, maxValue = 60f, yFormatter = { "${it.toInt()}" },
                )
            }
        }
        item { SectionLabel("Sensors") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                if (t.sensors.isEmpty()) {
                    Text(
                        if (caps.canDump) "Reading thermal HAL…" else "Per-sensor temperatures (CPU clusters, GPU, TPU, skin, modem) come from the thermal HAL via `dumpsys thermalservice`, which requires Shizuku or the ADB-granted DUMP permission. Battery temperature is always available.",
                        style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted,
                    )
                } else {
                    t.sensors.sortedByDescending { it.celsius }.forEach { s -> KeyValue("${s.name} · ${s.typeLabel}", Format.celsius(s.celsius), valueColor = if (s.celsius >= 45) Pf.colors.warning else MaterialTheme.colorScheme.onSurface) }
                }
            }
        }
        item { SectionLabel("Last 24 h") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                if (samples.size < 2) {
                    Text("History builds up from periodic samples (Automation).", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                } else {
                    LineChart(listOf(ChartSeries("Battery °C", samples.map { it.batteryTempC }, Pf.colors.warning)), minValue = 15f, maxValue = 50f)
                    val chargingHot = samples.filter { it.charging }.maxOfOrNull { it.batteryTempC }
                    KeyValue("Peak while charging", chargingHot?.let { Format.celsius(it) } ?: "—")
                    KeyValue("Time at Moderate+", "${samples.count { it.thermalStatus >= 2 }} of ${samples.size} samples")
                }
            }
        }
        item {
            TileGrid(2, 200.dp) { i, m ->
                if (i == 0) MetricTile("Sustained test", "Run in Lab", m, sub = "Measure throttling over minutes", onClick = { nav.go(Dest.LAB) })
                else MetricTile("Thermal guard", "On", m, sub = "Benchmarks stop at Severe")
            }
        }
        item {
            NoticeCard(
                "How PixelForge handles heat",
                "When thermal pressure rises PixelForge slows its own sampling, stops its benchmarks at Severe, and recommends cooling steps. It never bypasses or alters hardware thermal protection.",
                color = Pf.colors.muted,
            )
        }
    }
}
