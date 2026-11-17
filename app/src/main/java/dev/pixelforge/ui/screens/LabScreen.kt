package dev.pixelforge.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.bench.BenchOutcome
import dev.pixelforge.core.bench.BenchTest
import dev.pixelforge.core.bench.BenchmarkExport
import dev.pixelforge.core.bench.BenchmarkRunner
import dev.pixelforge.core.bench.Conditions
import dev.pixelforge.core.bench.parseMetrics
import dev.pixelforge.core.data.BenchmarkRun
import dev.pixelforge.core.monitor.ThermalSample
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ChartSeries
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.LineChart
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.ProgressPanel
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.Sparkline
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LabScreen(nav: Navigator) {
    val c = LocalContainer.current
    val progress by c.benchmarks.progress.collectAsStateWithLifecycle()
    val runs by c.db.benchmarks().observeAll().collectAsStateWithLifecycle(emptyList())
    val caps = rememberCaps()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var launchPicker by remember { mutableStateOf(false) }
    var regression by remember { mutableStateOf<Pair<BenchTest, Double>?>(null) }
    var showFramePacing by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    // Keep the screen on and request sustained-performance mode while a benchmark runs (own window only).
    val view = LocalView.current
    DisposableEffect(progress.running) {
        val window = (view.context as? Activity)?.window
        if (progress.running) {
            view.keepScreenOn = true
            runCatching { window?.setSustainedPerformanceMode(true) }
        }
        onDispose {
            view.keepScreenOn = false
            runCatching { window?.setSustainedPerformanceMode(false) }
        }
    }

    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(BenchmarkExport.toJson(c.db.benchmarks().all()).toByteArray()) }
            withContext(Dispatchers.Main) { ctx.toast("Exported JSON") }
        }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(BenchmarkExport.toCsv(c.db.benchmarks().all()).toByteArray()) }
            withContext(Dispatchers.Main) { ctx.toast("Exported CSV") }
        }
    }

    fun run(test: BenchTest, pkgs: List<String> = emptyList()) {
        scope.launch {
            val outcome = c.benchmarks.run(test, pkgs)
            if (outcome != null) c.benchmarks.regression(test, outcome.score)?.let { regression = test to it }
        }
    }

    PfScreen("Benchmark Lab", "Native C++, Vulkan and OpenGL ES workloads · thermal-guarded", actions = {
        TextButton(onClick = { exportJson.launch("pixelforge-benchmarks.json") }, enabled = runs.isNotEmpty()) { Text("JSON") }
        TextButton(onClick = { exportCsv.launch("pixelforge-benchmarks.csv") }, enabled = runs.isNotEmpty()) { Text("CSV") }
    }) {
        if (progress.running) {
            item {
                Column {
                    ProgressPanel(progress.test?.title ?: "Benchmark", progress.step, progress.fraction.takeIf { it > 0 }, onCancel = { c.benchmarks.cancel() })
                    if (progress.liveSeries.size > 1) {
                        Spacer(Modifier.height(8.dp))
                        PfCard(Modifier.fillMaxWidth()) {
                            Text("Throughput per window", style = MaterialTheme.typography.labelMedium, color = Pf.colors.muted)
                            Sparkline(progress.liveSeries.map { it.toFloat() }, minValue = 0f, height = 70.dp)
                        }
                    }
                }
            }
        }
        progress.error?.let { e -> item { NoticeCard(if (progress.thermalAbort) "Thermal guard stopped the test" else "Benchmark did not complete", e, color = if (progress.thermalAbort) Pf.colors.warning else Pf.colors.danger) } }
        regression?.let { (t, d) ->
            item { NoticeCard("Possible regression: ${t.title}", "Score is ${Format.percent(-d)} below the median of your previous compatible runs. Check thermal state, Battery Saver and background activity.", color = Pf.colors.warning) }
        }
        progress.lastOutcome?.let { o -> item { OutcomeCard(o) } }

        BenchTest.entries.groupBy { it.group }.forEach { (group, tests) ->
            item { SectionLabel(group) }
            items(tests, key = { it.id }) { t ->
                val last = runs.firstOrNull { it.testId == t.id && it.testVersion == t.version }
                val needsShizuku = t == BenchTest.APP_LAUNCH && !caps.shizukuReady
                PfCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(t.description, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                            last?.let { Text("Last: ${"%.0f".format(it.score)} pts · ${Format.ago(it.time)}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.accent) }
                            if (needsShizuku) Text("Requires Shizuku", style = MaterialTheme.typography.labelSmall, color = Pf.colors.warning)
                        }
                        Button(
                            onClick = { if (t == BenchTest.APP_LAUNCH) launchPicker = true else run(t) },
                            enabled = !progress.running && !needsShizuku,
                        ) { Text("Run") }
                    }
                }
            }
        }
        item { SectionLabel("Frame pacing") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("UI frame pacing", style = MaterialTheme.typography.titleSmall)
                Text("Measures PixelForge's own Compose frame intervals for 5 s at the current refresh rate — reveals jank and refresh-rate behaviour.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                Spacer(Modifier.height(8.dp))
                if (showFramePacing) FramePacingTest { showFramePacing = false } else OutlinedButton(onClick = { showFramePacing = true }, enabled = !progress.running) { Text("Start") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("History (${runs.size})", Modifier.weight(1f))
                TextButton(onClick = { confirmClear = true }, enabled = runs.isNotEmpty()) { Text("Clear") }
            }
        }
        items(runs.take(40), key = { it.id }) { r -> HistoryRow(r) }
        item {
            NoticeCard(
                "About scores",
                "Score = 1000 × measured ÷ fixed reference (v${BenchTest.CPU_SINGLE.version} constants). References only normalise units; they are not claims about any device. Results are compared only with runs of the same test version.",
                color = Pf.colors.muted,
            )
        }
    }

    if (launchPicker) {
        AppLaunchPicker(onDismiss = { launchPicker = false }, onRun = { launchPicker = false; run(BenchTest.APP_LAUNCH, it) })
    }
    if (confirmClear) {
        ConfirmDialog("Clear benchmark history?", "All saved results will be deleted.", "Clear", destructive = true,
            onConfirm = { confirmClear = false; scope.launch { c.db.benchmarks().clear() } }, onDismiss = { confirmClear = false })
    }
}

@Composable
private fun OutcomeCard(o: BenchOutcome) {
    PfCard(Modifier.fillMaxWidth(), highlight = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(o.test.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("%.0f".format(o.score), style = MaterialTheme.typography.headlineMedium, color = Pf.colors.accent)
        }
        o.metrics.forEach { m -> KeyValue(m.name, "${formatValue(m.value)} ${m.unit}") }
        if (o.series.size > 2) {
            Spacer(Modifier.height(6.dp))
            Sparkline(o.series.map { it.toFloat() }, minValue = 0f, height = 60.dp, color = Pf.colors.cyan)
        }
        ConditionsBlock(o.conditions, o.durationMs)
        o.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted) }
    }
}

@Composable
private fun ConditionsBlock(c: Conditions, durationMs: Long) {
    Spacer(Modifier.height(6.dp))
    Text("Test conditions", style = MaterialTheme.typography.labelMedium, color = Pf.colors.muted)
    KeyValue("Duration", Format.duration(durationMs))
    KeyValue("Battery temp", "${Format.celsius(c.batteryTempStartC)} → ${Format.celsius(c.batteryTempEndC)}")
    KeyValue("Thermal status", "${ThermalSample.statusLabel(c.thermalStatusStart)} → ${ThermalSample.statusLabel(c.thermalStatusEnd)}")
    KeyValue("Battery", "${c.batteryStartPct}% → ${c.batteryEndPct}%" + (c.consumedMah?.let { " · ${"%.1f".format(it)} mAh used (measured)" } ?: if (c.charging) " · charging (energy not measured)" else ""))
    if (c.powerSave) KeyValue("Battery Saver", "ON — results reduced", valueColor = Pf.colors.warning)
}

@Composable
private fun HistoryRow(r: BenchmarkRun) {
    var expanded by remember { mutableStateOf(false) }
    val test = BenchTest.entries.firstOrNull { it.id == r.testId }
    PfCard(Modifier.fillMaxWidth(), onClick = { expanded = !expanded }, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(test?.title ?: r.testId, style = MaterialTheme.typography.bodyMedium)
                Text("${Format.dateTime(r.time)} · v${r.testVersion}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
            }
            Pill("%.0f".format(r.score), Pf.colors.accent)
        }
        if (expanded) {
            parseMetrics(r.metricsJson).forEach { m -> KeyValue(m.name, "${formatValue(m.value)} ${m.unit}") }
            runCatching { Conditions.fromJson(JSONObject(r.conditionsJson)) }.getOrNull()?.let { ConditionsBlock(it, r.durationMs) }
        }
    }
}

private fun formatValue(v: Double): String = when {
    v >= 1000 -> "%.0f".format(v)
    v >= 10 -> "%.1f".format(v)
    else -> "%.2f".format(v)
}

@Composable
private fun FramePacingTest(onDone: () -> Unit) {
    val intervals = remember { mutableStateListOf<Double>() }
    var finished by remember { mutableStateOf(false) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        val start = withFrameNanos { it }
        last = start
        while (true) {
            val now = withFrameNanos { it }
            intervals += (now - last) / 1e6
            last = now
            phase = ((now - start) / 1e9f)
            if (now - start > 5_000_000_000L) break
        }
        finished = true
    }
    val accent = Pf.colors.accent
    Canvas(Modifier.fillMaxWidth().height(48.dp)) {
        val x = (kotlin.math.sin(phase * 3.0) * 0.5 + 0.5).toFloat() * size.width
        drawCircle(accent, radius = 14.dp.toPx(), center = Offset(x, size.height / 2))
    }
    if (finished && intervals.size > 10) {
        val sorted = intervals.drop(1).sorted()
        val median = sorted[sorted.size / 2]
        val p99 = sorted[((sorted.size - 1) * 0.99).toInt()]
        val hz = 1000.0 / median
        val janky = intervals.count { it > median * 1.5 }
        KeyValue("Frames", "${intervals.size} in 5 s")
        KeyValue("Median interval", "%.2f ms (≈%.0f Hz)".format(median, hz))
        KeyValue("p99 interval", "%.2f ms".format(p99))
        KeyValue("Janky frames (>1.5× median)", "$janky (${Format.percent(janky.toDouble() / intervals.size, 1)})")
        LineChart(listOf(ChartSeries("Frame ms", intervals.map { it.toFloat() }, Pf.colors.cyan)), minValue = 0f, maxValue = (p99 * 1.5).toFloat().coerceAtLeast(20f), height = 100.dp)
        TextButton(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun AppLaunchPicker(onDismiss: () -> Unit, onRun: (List<String>) -> Unit) {
    val c = LocalContainer.current
    val apps by c.apps.apps.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { c.apps.refresh() }
    val selected = remember { mutableStateListOf<String>() }
    ConfirmDialog(
        "Cold-launch timing", "Each app is force-stopped and started with `am start -W -S`; the activity manager reports TotalTime. PixelForge returns to the foreground afterwards.",
        "Run (${selected.size})", onConfirm = { if (selected.isNotEmpty()) onRun(selected.toList()) }, onDismiss = onDismiss,
    ) {
        Column(Modifier.height(320.dp).let { it }) {
            androidx.compose.foundation.lazy.LazyColumn {
                items(apps.filter { it.hasLauncher && it.packageName != c.appContext.packageName }.take(150), key = { it.packageName }) { a ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Checkbox(a.packageName in selected, { on -> if (on) { if (selected.size < 5) selected += a.packageName } else selected -= a.packageName })
                        Spacer(Modifier.width(6.dp))
                        Text(a.label, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Up to 5 apps.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
    }
}
