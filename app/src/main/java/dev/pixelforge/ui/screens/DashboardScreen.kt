package dev.pixelforge.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.display.DashboardPresentation
import dev.pixelforge.core.monitor.CpuLoadSource
import dev.pixelforge.core.monitor.SystemSnapshot
import dev.pixelforge.core.optimize.ProfileCatalog
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ArcGauge
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.MetricTile
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.Sparkline
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(nav: Navigator) {
    val c = LocalContainer.current
    val snap = rememberSnapshot()
    val history = rememberHistory()
    val settings = rememberSettings()
    val caps = rememberCaps()
    val displays by c.displays.displays.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val network by produceState<dev.pixelforge.core.network.NetworkStatus?>(null) {
        while (true) {
            value = withContext(Dispatchers.IO) { runCatching { c.network.status() }.getOrNull() }
            delay(10_000)
        }
    }
    var presentation by remember { mutableStateOf<DashboardPresentation?>(null) }
    LaunchedEffect(displays, settings.desktopAutoPresent) {
        if (settings.desktopAutoPresent && displays.isNotEmpty() && presentation == null) {
            presentation = DashboardPresentation.show(ctx, c, displays.first().id)
        }
        if (displays.isEmpty()) presentation = null
    }

    PfScreen(
        title = "PixelForge",
        subtitle = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}" + if (c.monitor.topology.isTensorG4) " · Tensor G4" else "",
        actions = {
            Pill(ProfileCatalog.byId(settings.activeProfile)?.name ?: "Custom", Pf.colors.accent, Modifier.padding(end = 8.dp))
        },
    ) {
        if (snap == null) {
            item { PfCard(Modifier.fillMaxWidth()) { Text("Reading sensors…", color = Pf.colors.muted) } }
            return@PfScreen
        }
        item { HeroCard(snap, nav, c.optimizer.healthScore(snap)) }
        item { GaugeRow(snap) }
        item {
            val warnings = warningsFor(snap)
            if (warnings.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    warnings.forEach { (title, text, route) ->
                        NoticeCard(title, text, color = Pf.colors.warning, icon = Icons.Filled.Warning, actionLabel = "Details", onAction = { nav.go(route) })
                    }
                }
            }
        }
        item { SectionLabel("Live telemetry") }
        item {
            TileGrid(6, minTileWidth = 168.dp) { i, m ->
                when (i) {
                    0 -> MetricTile(
                        "Temperature", Format.celsius(snap.battery.temperatureC), m,
                        sub = "Thermal ${snap.thermal.statusLabel}" + (snap.thermal.headroom?.let { " · headroom ${"%.2f".format(it)}" } ?: ""),
                        color = thermalColor(snap.thermal.status), icon = Icons.Filled.Thermostat, onClick = { nav.go(Dest.THERMAL) },
                    )
                    1 -> MetricTile(
                        "GPU", snap.gpu.freqHz?.let { Format.mhz(it / 1000) } ?: "Mali-G715",
                        m, sub = snap.gpu.utilizationPct?.let { "$it% busy" } ?: "Open GPU lab for details", icon = Icons.Filled.ViewInAr, onClick = { nav.go(Dest.GPU) },
                    )
                    2 -> MetricTile("Uptime", Format.duration(snap.uptimeMs), m, sub = "Since last boot", icon = Icons.Filled.Schedule)
                    3 -> MetricTile(
                        "Network", network?.transport ?: "…", m,
                        sub = network?.let { n -> if (!n.connected) "Offline" else listOfNotNull(if (n.validated) "Internet OK" else "No internet", if (n.metered) "metered" else null, if (n.vpn) "VPN" else null).joinToString(" · ") },
                        icon = Icons.Filled.NetworkCheck, onClick = { nav.go(Dest.NETWORK) },
                    )
                    4 -> MetricTile(
                        "Memory pressure", listOf("Low", "Mild", "High", "Critical")[snap.memory.pressureLevel.coerceIn(0, 3)], m,
                        sub = snap.memory.psiSome?.let { "PSI some avg10 ${"%.2f".format(it.avg10)}%" } ?: "Swap ${Format.bytes(snap.memory.swapTotal - snap.memory.swapFree)} used",
                        color = listOf(Pf.colors.success, Pf.colors.cyan, Pf.colors.warning, Pf.colors.danger)[snap.memory.pressureLevel.coerceIn(0, 3)],
                        icon = Icons.Filled.Memory, onClick = { nav.go(Dest.MONITOR) },
                    )
                    else -> MetricTile(
                        "Battery", "${snap.battery.levelPct}%", m,
                        sub = snap.battery.statusLabel + (snap.battery.powerW?.let { " · ${"%.2f".format(kotlin.math.abs(it))} W" } ?: ""),
                        icon = Icons.Filled.BatteryChargingFull, onClick = { nav.go(Dest.BATTERY) },
                    )
                }
            }
        }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CPU", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(snap.cpu.loadSource.label, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                }
                Sparkline(history.map { (it.cpu.totalLoad ?: 0f) * 100 }, minValue = 0f, maxValue = 100f, color = Pf.colors.accent)
                Spacer(Modifier.height(10.dp))
                Text("Memory used", style = MaterialTheme.typography.titleSmall)
                Sparkline(history.map { it.memory.usedFraction * 100 }, minValue = 0f, maxValue = 100f, color = Pf.colors.cyan)
            }
        }
        if (displays.isNotEmpty()) {
            item {
                NoticeCard(
                    "External display connected", "${displays.first().name} · ${displays.first().width}×${displays.first().height}. Show the PixelForge dashboard there without mirroring the phone UI.",
                    icon = Icons.Filled.DesktopWindows, actionLabel = if (presentation == null) "Present dashboard" else "Stop presenting",
                    onAction = {
                        presentation = if (presentation == null) DashboardPresentation.show(ctx, c, displays.first().id) else {
                            presentation?.dismiss(); null
                        }
                    },
                )
            }
        }
        if (settings.developerDiagnostics) {
            item { SectionLabel("Developer diagnostics") }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    snap.cpu.freqKhz.forEachIndexed { i, f ->
                        KeyValue("cpu$i (${c.monitor.topology.cores.getOrNull(i)?.microarchitecture ?: "?"})", "${Format.mhz(f ?: 0)} · ${snap.cpu.coreLoad.getOrNull(i)?.let { Format.percent(it.toDouble()) } ?: "n/a"}")
                    }
                    KeyValue("PixelForge CPU", Format.percent(snap.cpu.selfLoad.toDouble(), 1) + " of one core")
                    KeyValue("PixelForge PSS", Format.bytes(snap.memory.selfPssBytes))
                    KeyValue("Java heap / native", "${Format.bytes(snap.memory.selfJavaHeapBytes)} / ${Format.bytes(snap.memory.selfNativeHeapBytes)}")
                    snap.memory.psiFull?.let { KeyValue("PSI full avg10/60/300", "${it.avg10} / ${it.avg60} / ${it.avg300}") }
                    KeyValue("Privileges", listOfNotNull(if (caps.shizukuReady) "Shizuku" else null, if (caps.dump) "DUMP" else null, if (caps.writeSecureSettings) "WRITE_SECURE_SETTINGS" else null).joinToString().ifEmpty { "Standard app" })
                }
            }
        }
    }
}

@Composable
private fun HeroCard(snap: SystemSnapshot, nav: Navigator, health: Pair<Int, List<Pair<String, Int>>>) {
    PfCard(Modifier.fillMaxWidth(), highlight = true) {
        BoxWithConstraints {
            val wide = maxWidth > 560.dp
            val gauge: @Composable () -> Unit = {
                ArcGauge(health.first / 100f, "${health.first}", "Health index", size = 150.dp, color = scoreColor(health.first))
            }
            val body: @Composable () -> Unit = {
                Column {
                    health.second.forEach { (label, pts) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted, modifier = Modifier.weight(1f))
                            Text("$pts", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(
                        "Index of measured headroom (storage, memory, thermal, battery) — not a speed score.",
                        style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { nav.go(Dest.OPTIMIZE) },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Pf.colors.accent),
                    ) {
                        Icon(Icons.Filled.AutoMode, null)
                        Spacer(Modifier.width(8.dp))
                        Text("SMART OPTIMIZE", style = MaterialTheme.typography.titleSmall)
                    }
                    TextButton(onClick = { nav.go(Dest.HISTORY) }, modifier = Modifier.align(Alignment.End)) { Text("History") }
                }
            }
            if (wide) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    gauge(); Spacer(Modifier.width(24.dp)); Box(Modifier.weight(1f)) { body() }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) { gauge(); Spacer(Modifier.height(8.dp)); body() }
            }
        }
    }
}

@Composable
private fun GaugeRow(snap: SystemSnapshot) {
    TileGrid(4, minTileWidth = 150.dp) { i, m ->
        PfCard(m, contentPadding = 8.dp) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (i) {
                    0 -> ArcGauge(
                        snap.cpu.totalLoad, snap.cpu.totalLoad?.let { Format.percent(it.toDouble()) } ?: "n/a", "CPU",
                        sub = if (snap.cpu.loadSource == CpuLoadSource.FREQUENCY) "freq. estimate" else "${snap.cpu.onlineCores} cores",
                        size = 118.dp,
                    )
                    1 -> ArcGauge(snap.memory.usedFraction, Format.bytes(snap.memory.availableBytes, 1), "RAM free", sub = "of ${Format.bytes(snap.memory.totalBytes, 0)}", size = 118.dp, color = Pf.colors.cyan)
                    2 -> ArcGauge(snap.storage.usedFraction, Format.bytes(snap.storage.freeBytes, 0), "Storage free", sub = "of ${Format.bytes(snap.storage.totalBytes, 0)}", size = 118.dp, color = Color(0xFFA78BFA))
                    else -> ArcGauge(snap.battery.levelPct / 100f, "${snap.battery.levelPct}%", "Battery", sub = snap.battery.statusLabel, size = 118.dp, color = Pf.colors.success)
                }
            }
        }
    }
}

private data class Warning(val title: String, val text: String, val route: String)

private fun warningsFor(s: SystemSnapshot): List<Warning> = buildList {
    if (s.thermal.status >= 2) add(Warning("Thermal throttling likely", "Thermal status is ${s.thermal.statusLabel}. Performance is being limited to protect the hardware.", Dest.THERMAL.route))
    if (s.storage.totalBytes > 0 && s.storage.freeBytes * 10 < s.storage.totalBytes) add(Warning("Storage nearly full", "${Format.bytes(s.storage.freeBytes)} free. Low free space slows writes and updates.", Dest.CLEANER.route))
    if (s.memory.pressureLevel >= 3) add(Warning("Critical memory pressure", "Android is close to killing background apps.", Dest.MONITOR.route))
    if (!s.battery.isCharging && s.battery.levelPct <= 15 && !s.battery.powerSaveMode) add(Warning("Battery low", "${s.battery.levelPct}% and Battery Saver is off.", Dest.BATTERY.route))
    if (s.battery.temperatureC >= 45) add(Warning("Battery hot", "Battery at ${Format.celsius(s.battery.temperatureC)}.", Dest.BATTERY.route))
}

@Composable
fun thermalColor(status: Int): Color = when (status) {
    0 -> Pf.colors.success
    1 -> Pf.colors.cyan
    2 -> Pf.colors.warning
    else -> Pf.colors.danger
}

@Composable
fun scoreColor(score: Int): Color = when {
    score >= 80 -> Pf.colors.success
    score >= 60 -> Pf.colors.accent
    score >= 40 -> Pf.colors.warning
    else -> Pf.colors.danger
}
