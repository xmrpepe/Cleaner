package dev.pixelforge.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.monitor.CpuInfoReader
import dev.pixelforge.core.monitor.CpuLoadSource
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.BarRow
import dev.pixelforge.ui.components.ChartSeries
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.LineChart
import dev.pixelforge.ui.components.MetricTile
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.SegmentBar
import dev.pixelforge.ui.components.StatusDot
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MonitorScreen(nav: Navigator) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snap = rememberSnapshot()
    val history = rememberHistory()
    val c = LocalContainer.current
    val topo = c.monitor.topology
    val caps = rememberCaps()
    val ctx = LocalContext.current

    PfScreen("Monitor", "Live hardware telemetry · sampling pauses when hidden") {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                listOf("CPU", "Memory").forEachIndexed { i, t -> FilterChip(tab == i, { tab = i }, { Text(t) }, Modifier.padding(end = 6.dp)) }
                FilterChip(false, { nav.go(Dest.GPU) }, { Text("GPU") }, Modifier.padding(end = 6.dp))
                FilterChip(false, { nav.go(Dest.BATTERY) }, { Text("Battery") }, Modifier.padding(end = 6.dp))
                FilterChip(false, { nav.go(Dest.THERMAL) }, { Text("Thermal") })
            }
        }
        if (snap == null) return@PfScreen
        if (tab == 0) {
            item {
                PfCard(Modifier.fillMaxWidth(), highlight = topo.isTensorG4) {
                    Text("${topo.socManufacturer} ${topo.socModel}" + if (topo.isTensorG4) " · Google Tensor G4" else "", style = MaterialTheme.typography.titleMedium)
                    Text("${topo.coreCount} cores · ${topo.abis.firstOrNull() ?: ""}", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    Spacer(Modifier.height(8.dp))
                    topo.clusters.reversed().forEach { cl ->
                        KeyValue(cl.name, "cpu${cl.cores.first()}–${cl.cores.last()} · up to ${Format.mhz(cl.maxKhz)}")
                    }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Per-core", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(snap.cpu.loadSource.label, style = MaterialTheme.typography.labelSmall, color = if (snap.cpu.loadSource == CpuLoadSource.FREQUENCY) Pf.colors.warning else Pf.colors.success)
                    }
                    Spacer(Modifier.height(8.dp))
                    BarRow(snap.cpu.coreLoad, labels = snap.cpu.coreLoad.indices.map { "c$it" }, height = 90.dp)
                    Spacer(Modifier.height(8.dp))
                    snap.cpu.freqKhz.forEachIndexed { i, f ->
                        val core = topo.cores.getOrNull(i)
                        KeyValue("cpu$i · ${core?.microarchitecture ?: "?"}", "${Format.mhz(f ?: 0)} / ${Format.mhz(core?.maxKhz ?: 0)}" + (snap.cpu.coreLoad.getOrNull(i)?.let { " · ${Format.percent(it.toDouble())}" } ?: ""))
                    }
                    if (snap.cpu.loadSource == CpuLoadSource.FREQUENCY) {
                        Text(
                            "Android blocks /proc/stat for apps, so bars show current frequency as a fraction of maximum — a proxy, not true utilisation. Connect Shizuku for kernel-accurate utilisation.",
                            style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("History", style = MaterialTheme.typography.titleSmall)
                    LineChart(
                        listOf(
                            ChartSeries("Total %", history.map { (it.cpu.totalLoad ?: 0f) * 100 }, Pf.colors.accent),
                        ) + topo.clusters.mapIndexed { i, cl ->
                            ChartSeries(cl.name.substringBefore(" ·"), history.map { h ->
                                val fs = cl.cores.mapNotNull { h.cpu.freqKhz.getOrNull(it) }
                                if (fs.isEmpty() || cl.maxKhz <= 0) 0f else fs.average().toFloat() / cl.maxKhz * 100
                            }, listOf(Pf.colors.cyan, Color(0xFFA78BFA), Pf.colors.success)[i % 3])
                        },
                        minValue = 0f, maxValue = 100f, yFormatter = { "${it.toInt()}%" },
                    )
                    Text("Cluster lines: average frequency as % of cluster maximum.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                }
            }
            item {
                val throttled = topo.clusters.mapNotNull { cl ->
                    val cap = cl.cores.firstOrNull()?.let { CpuInfoReader.scalingMaxKhz(it) }
                    if (cap != null && cap < cl.maxKhz) cl.name to cap else null
                }
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Throttling detection", style = MaterialTheme.typography.titleSmall)
                    if (throttled.isEmpty()) {
                        Text("No frequency caps below hardware maximum detected (scaling_max_freq = cpuinfo_max_freq). Thermal status: ${snap.thermal.statusLabel}.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    } else {
                        throttled.forEach { (name, cap) -> KeyValue(name, "capped at ${Format.mhz(cap)}", valueColor = Pf.colors.warning) }
                    }
                }
            }
            item {
                TileGrid(3, 150.dp) { i, m ->
                    when (i) {
                        0 -> MetricTile("PixelForge CPU", Format.percent(snap.cpu.selfLoad.toDouble(), 1), m, sub = "of one core (self-overhead)")
                        1 -> MetricTile("Online cores", "${snap.cpu.onlineCores}/${topo.coreCount}", m)
                        else -> MetricTile("Governor", topo.cores.firstOrNull()?.governor ?: "n/a", m, sub = "Kernel cpufreq governor")
                    }
                }
            }
            if (!caps.shizukuReady) {
                item {
                    NoticeCard(
                        "CPU frequency & governor control",
                        "Changing CPU frequencies or governors requires root on Pixel devices and is not offered. PixelForge uses supported controls instead: profiles, Game Mode, fixed performance mode (Shizuku) and Android's own thermal management.",
                        color = Pf.colors.muted,
                    )
                }
            }
        } else {
            val m = snap.memory
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("RAM", style = MaterialTheme.typography.titleSmall)
                    Text("${Format.bytes(m.usedBytes)} used of ${Format.bytes(m.totalBytes)} · ${Format.bytes(m.availableBytes)} available", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    Spacer(Modifier.height(10.dp))
                    val cached = (m.meminfo["Cached"] ?: 0) + (m.meminfo["Buffers"] ?: 0)
                    val free = m.meminfo["MemFree"] ?: 0
                    val used = (m.totalBytes - free - cached).coerceAtLeast(0)
                    SegmentBar(listOf(used.toFloat() to Pf.colors.accent, cached.toFloat() to Pf.colors.cyan, free.toFloat() to Pf.colors.success), height = 14.dp)
                    Spacer(Modifier.height(6.dp))
                    Row {
                        Legend("Apps & kernel", Pf.colors.accent, Format.bytes(used)); Legend("Cache", Pf.colors.cyan, Format.bytes(cached)); Legend("Free", Pf.colors.success, Format.bytes(free))
                    }
                    Text("Cached memory is reclaimable instantly. Free RAM is wasted RAM — Android keeps apps cached so they resume fast.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.padding(top = 6.dp))
                }
            }
            item {
                TileGrid(4, 150.dp) { i, mod ->
                    when (i) {
                        0 -> MetricTile("Pressure", listOf("Low", "Mild", "High", "Critical")[m.pressureLevel.coerceIn(0, 3)], mod, sub = if (m.psiSome != null) "Kernel PSI" else "LMK headroom")
                        1 -> MetricTile("LMK threshold", Format.bytes(m.thresholdBytes), mod, sub = "Low-memory killer starts below")
                        2 -> MetricTile("zRAM swap", Format.bytes(m.swapTotal - m.swapFree), mod, sub = "of ${Format.bytes(m.swapTotal)} compressed")
                        else -> MetricTile("PixelForge", Format.bytes(m.selfPssBytes), mod, sub = "PSS · heap ${Format.bytes(m.selfJavaHeapBytes)}")
                    }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Memory trend", style = MaterialTheme.typography.titleSmall)
                    LineChart(
                        listOfNotNull(
                            ChartSeries("Used %", history.map { it.memory.usedFraction * 100 }, Pf.colors.cyan),
                            if (history.any { it.memory.psiSome != null }) ChartSeries("PSI some avg10", history.map { it.memory.psiSome?.avg10 ?: 0f }, Pf.colors.warning) else null,
                        ),
                        minValue = 0f, maxValue = 100f, yFormatter = { "${it.toInt()}%" },
                    )
                }
            }
            m.psiSome?.let { p ->
                item {
                    PfCard(Modifier.fillMaxWidth()) {
                        Text("Pressure stall information", style = MaterialTheme.typography.titleSmall)
                        KeyValue("some avg10 / 60 / 300", "${p.avg10}% / ${p.avg60}% / ${p.avg300}%")
                        m.psiFull?.let { f -> KeyValue("full avg10 / 60 / 300", "${f.avg10}% / ${f.avg60}% / ${f.avg300}%") }
                        Text("Share of time tasks stalled waiting for memory — the most direct responsiveness signal.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                    }
                }
            }
            item { SectionLabel("Low-memory events (this session)") }
            item {
                val events by c.monitor.memoryEvents.collectAsStateWithLifecycle()
                PfCard(Modifier.fillMaxWidth()) {
                    if (events.isEmpty()) Text("None observed while PixelForge was running.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    events.takeLast(10).reversed().forEach { e -> KeyValue(Format.dateTime(e.time), e.detail) }
                }
            }
            item { ProcessMemorySection(caps.canDump) }
            item {
                NoticeCard(
                    "About RAM boosters",
                    "PixelForge does not force-stop apps to “free RAM”. Killed apps restart, costing CPU time and battery, and Android's memory manager already reclaims memory when needed. Use per-app details to inspect heavy apps instead.",
                    color = Pf.colors.muted, actionLabel = "Running services (Developer options)",
                    onAction = { ctx.launch(Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"), Intent(Settings.ACTION_SETTINGS)) },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Legend(label: String, color: Color, value: String) {
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color); Spacer(Modifier.width(4.dp))
        Column { Text(label, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted); Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Per-process PSS from `dumpsys meminfo` — only when Shizuku or ADB-granted DUMP is available. */
@Composable
private fun ProcessMemorySection(canDump: Boolean) {
    val c = LocalContainer.current
    var load by rememberSaveable { mutableStateOf(false) }
    val rows by produceState<List<Pair<String, Long>>?>(null, load) {
        if (load) value = withContext(Dispatchers.IO) { c.privileged.dumpsys("meminfo", 30_000)?.let(::parseMeminfoDump) }
    }
    PfCard(Modifier.fillMaxWidth()) {
        Text("Memory by process", style = MaterialTheme.typography.titleSmall)
        if (!canDump) {
            Text("Android only reveals other apps' memory to privileged callers. Connect Shizuku or grant DUMP via ADB (Advanced) to list per-process PSS.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
        } else if (!load) {
            OutlinedButton(onClick = { load = true }) { Text("Load (dumpsys meminfo)") }
        } else if (rows == null) {
            Text("Collecting…", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
        } else {
            val list = rows.orEmpty()
            val max = list.maxOfOrNull { it.second } ?: 1
            list.take(25).forEach { (name, bytes) ->
                Column(Modifier.padding(vertical = 3.dp)) {
                    Row { Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1); Text(Format.bytes(bytes), style = MaterialTheme.typography.bodySmall) }
                    SegmentBar(listOf(bytes.toFloat() to Pf.colors.accent, (max - bytes).toFloat() to Color.Transparent), height = 4.dp)
                }
            }
        }
    }
}

/** Parses the "Total PSS by process" section of `dumpsys meminfo`. */
fun parseMeminfoDump(text: String): List<Pair<String, Long>> {
    val section = text.substringAfter("Total PSS by process:", "").substringBefore("Total PSS by OOM adjustment:", "")
    val re = Regex("""^\s*([\d,]+)K: ([^\s(]+)""", RegexOption.MULTILINE)
    return re.findAll(section).mapNotNull { m ->
        val kb = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
        m.groupValues[2] to kb * 1024
    }.sortedByDescending { it.second }.toList()
}
