package dev.pixelforge.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ArcGauge
import dev.pixelforge.ui.components.BarRow
import dev.pixelforge.ui.components.ChartSeries
import dev.pixelforge.ui.components.LineChart
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.theme.Pf

/** Large-screen live dashboard used on external displays (Presentation). */
@Composable
fun DesktopDashboard() {
    val snap = rememberSnapshot()
    val history = rememberHistory()
    val topo = LocalContainer.current.monitor.topology
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PixelForge · Live", style = MaterialTheme.typography.headlineMedium)
                    Text("${Build.MODEL} · ${topo.socModel} · ${topo.coreCount} cores", color = Pf.colors.muted)
                }
                Text(snap?.let { Format.duration(it.uptimeMs) + " uptime" } ?: "", color = Pf.colors.muted)
            }
            if (snap == null) {
                Text("Waiting for samples…", color = Pf.colors.muted)
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                listOf(
                    Triple(snap.cpu.totalLoad, snap.cpu.totalLoad?.let { Format.percent(it.toDouble()) } ?: "n/a", "CPU"),
                    Triple(snap.memory.usedFraction, Format.percent(snap.memory.usedFraction.toDouble()), "RAM"),
                    Triple(snap.storage.usedFraction, Format.percent(snap.storage.usedFraction.toDouble()), "Storage"),
                    Triple(snap.battery.levelPct / 100f, "${snap.battery.levelPct}%", "Battery"),
                    Triple((snap.battery.temperatureC / 50f).coerceIn(0f, 1f), Format.celsius(snap.battery.temperatureC), snap.thermal.statusLabel),
                ).forEachIndexed { i, (f, v, l) ->
                    PfCard(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            ArcGauge(f, v, l, size = 170.dp, color = listOf(Pf.colors.accent, Pf.colors.cyan, Color(0xFFA78BFA), Pf.colors.success, Pf.colors.warning)[i])
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                PfCard(Modifier.weight(2f)) {
                    Text("Utilisation history", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    LineChart(
                        listOf(
                            ChartSeries("CPU %", history.map { (it.cpu.totalLoad ?: 0f) * 100 }, Pf.colors.accent),
                            ChartSeries("RAM %", history.map { it.memory.usedFraction * 100 }, Pf.colors.cyan),
                            ChartSeries("Battery °C", history.map { it.battery.temperatureC }, Pf.colors.warning),
                        ),
                        minValue = 0f, maxValue = 100f, height = 220.dp,
                    )
                }
                PfCard(Modifier.weight(1f)) {
                    Text("Per-core frequency", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    BarRow(
                        snap.cpu.freqKhz.mapIndexed { i, f -> f?.let { v -> topo.cores.getOrNull(i)?.maxKhz?.takeIf { it > 0 }?.let { v.toFloat() / it } } },
                        height = 220.dp, labels = snap.cpu.freqKhz.indices.map { "c$it" },
                    )
                }
            }
        }
    }
}
