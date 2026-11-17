package dev.pixelforge.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pixelforge.BuildConfig
import dev.pixelforge.core.settings.Accent
import dev.pixelforge.core.settings.AppSettings
import dev.pixelforge.core.settings.Density
import dev.pixelforge.core.settings.ThemeMode
import dev.pixelforge.di.AppContainer
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.StatusDot
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen(nav: Navigator) {
    val c = LocalContainer.current
    val s = rememberSettings()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClearLogs by remember { mutableStateOf(false) }
    var newExclusion by remember { mutableStateOf("") }
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }
    val exportDiag = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val json = diagnosticsJson(c)
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            withContext(Dispatchers.Main) { ctx.toast("Diagnostics exported") }
        }
    }

    PfScreen("Settings", "PixelForge ${BuildConfig.VERSION_NAME}", onBack = nav::back) {
        item { SectionLabel("Appearance") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ThemeMode.entries.forEach { m -> FilterChip(s.themeMode == m, { upd { it.copy(themeMode = m) } }, { Text(m.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }) }) }
                }
                Spacer(Modifier.width(8.dp))
                Text("Accent", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Accent.entries.forEach { a ->
                        FilterChip(s.accent == a, { upd { it.copy(accent = a) } }, { Text(a.label) }, leadingIcon = { StatusDot(Color(a.argb)) })
                    }
                }
                Text("Density", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Density.entries.forEach { d -> FilterChip(s.density == d, { upd { it.copy(density = d) } }, { Text(d.name.lowercase().replaceFirstChar { ch -> ch.uppercase() }) }) }
                }
                Toggle("Animations", "Gauge and chart transitions", s.animations) { v -> upd { it.copy(animations = v) } }
            }
        }
        item { SectionLabel("Performance & monitoring") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Live sampling every ${"%.1f".format(s.monitorIntervalMs / 1000.0)} s", style = MaterialTheme.typography.bodyMedium)
                Slider(s.monitorIntervalMs.toFloat(), { v -> upd { it.copy(monitorIntervalMs = (v / 250).toLong() * 250) } }, valueRange = 500f..5000f)
                Toggle("Adaptive monitoring", "Halve sampling rate under thermal pressure or Battery Saver", s.adaptiveMonitoring) { v -> upd { it.copy(adaptiveMonitoring = v) } }
                Text("Benchmark window: ${s.benchmarkSeconds} s per sub-test", style = MaterialTheme.typography.bodyMedium)
                Slider(s.benchmarkSeconds.toFloat(), { v -> upd { it.copy(benchmarkSeconds = v.toInt()) } }, valueRange = 1f..10f, steps = 8)
                Text("Sustained tests: ${s.sustainedMinutes} min", style = MaterialTheme.typography.bodyMedium)
                Slider(s.sustainedMinutes.toFloat(), { v -> upd { it.copy(sustainedMinutes = v.toInt()) } }, valueRange = 1f..20f)
                Toggle("Thermal guard", "Stop benchmarks at Severe thermal status (strongly recommended)", s.thermalGuard) { v -> upd { it.copy(thermalGuard = v) } }
                TextButton(onClick = { nav.go(Dest.PROFILES) }) { Text("Performance profiles →") }
            }
        }
        item { SectionLabel("Cleaning") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Toggle("Recycle bin", "Move cleaned files to a restorable bin instead of deleting", s.useRecycleBin) { v -> upd { it.copy(useRecycleBin = v) } }
                Text("Keep for ${s.recycleRetentionDays} days", style = MaterialTheme.typography.bodyMedium)
                Slider(s.recycleRetentionDays.toFloat(), { v -> upd { it.copy(recycleRetentionDays = v.toInt()) } }, valueRange = 1f..30f)
                Text("Large file threshold: ${s.largeFileThresholdMb} MB", style = MaterialTheme.typography.bodyMedium)
                Slider(s.largeFileThresholdMb.toFloat(), { v -> upd { it.copy(largeFileThresholdMb = (v.toInt() / 25 * 25).coerceAtLeast(25)) } }, valueRange = 25f..1000f)
                Text("Old downloads after ${s.oldDownloadDays} days", style = MaterialTheme.typography.bodyMedium)
                Slider(s.oldDownloadDays.toFloat(), { v -> upd { it.copy(oldDownloadDays = v.toInt()) } }, valueRange = 7f..365f)
                Text("Old screenshots after ${s.oldScreenshotDays} days", style = MaterialTheme.typography.bodyMedium)
                Slider(s.oldScreenshotDays.toFloat(), { v -> upd { it.copy(oldScreenshotDays = v.toInt()) } }, valueRange = 7f..365f)
                Text("Scan exclusions", style = MaterialTheme.typography.titleSmall)
                s.scanExclusions.forEach { ex ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(ex, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { upd { it.copy(scanExclusions = it.scanExclusions - ex) } }) { Text("Remove") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newExclusion, { newExclusion = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("/storage/emulated/0/MyFolder") })
                    TextButton(onClick = {
                        val p = newExclusion.trim().trimEnd('/')
                        if (p.startsWith("/")) { upd { it.copy(scanExclusions = it.scanExclusions + p) }; newExclusion = "" }
                    }) { Text("Add") }
                }
                TextButton(onClick = { nav.go(Dest.HISTORY) }) { Text("Cleanup history →") }
            }
        }
        item { SectionLabel("Privacy & data") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("All data stays on this device. PixelForge has no analytics, ads or accounts. Network access is used only for tests you start.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                Text("Keep telemetry for ${s.dataRetentionDays} days", style = MaterialTheme.typography.bodyMedium)
                Slider(s.dataRetentionDays.toFloat(), { v -> upd { it.copy(dataRetentionDays = v.toInt()) } }, valueRange = 1f..90f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportDiag.launch("pixelforge-diagnostics.json") }) { Text("Export diagnostics") }
                    OutlinedButton(onClick = { confirmClearLogs = true }) { Text("Clear audit log") }
                }
            }
        }
        item { SectionLabel("Advanced") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Toggle("Shizuku integration", "Allow optional ADB-level operations when Shizuku is running", s.shizukuEnabled) { v -> upd { it.copy(shizukuEnabled = v) } }
                Text("Battery design capacity override: ${if (s.designCapacityMah > 0) "${s.designCapacityMah} mAh" else "auto"}", style = MaterialTheme.typography.bodyMedium)
                Slider(s.designCapacityMah.toFloat(), { v -> upd { it.copy(designCapacityMah = (v.toInt() / 50 * 50)) } }, valueRange = 0f..6000f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.go(Dest.ADVANCED) }) { Text("ADB & Shizuku") }
                    OutlinedButton(onClick = { nav.go(Dest.DEVICE) }) { Text("Hardware info") }
                    OutlinedButton(onClick = { ctx.launch(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Developer options") }
                }
                TextButton(onClick = { confirmReset = true }) { Text("Reset all PixelForge settings", color = Pf.colors.danger) }
            }
        }
    }
    if (confirmReset) ConfirmDialog("Reset settings?", "Restores default preferences. History, benchmarks and the recycle bin are kept.", "Reset", destructive = true,
        onConfirm = { confirmReset = false; scope.launch { c.settings.reset(); c.settings.update { it.copy(onboardingDone = true) } } }, onDismiss = { confirmReset = false })
    if (confirmClearLogs) ConfirmDialog("Clear audit log?", "Removes all action-log entries.", "Clear", destructive = true,
        onConfirm = { confirmClearLogs = false; scope.launch { c.db.actionLog().clear() } }, onDismiss = { confirmClearLogs = false })
}

@Composable
fun Toggle(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
        }
        Switch(checked, onChange)
    }
}

private suspend fun diagnosticsJson(c: AppContainer): String {
    val snap = c.monitor.sample()
    val caps = c.capabilities.current()
    val (vk, gl) = GpuProbeCache.load()
    val sections = c.deviceInfo.sections(vk.optString("deviceName"), gl?.renderer)
    return JSONObject()
        .put("generatedAt", System.currentTimeMillis())
        .put("app", BuildConfig.VERSION_NAME)
        .put("capabilities", JSONObject().put("allFiles", caps.allFilesAccess).put("usageAccess", caps.usageAccess).put("shizuku", caps.shizuku.name).put("dump", caps.dump).put("writeSecureSettings", caps.writeSecureSettings))
        .put("device", JSONArray().apply { sections.forEach { s -> put(JSONObject().put("section", s.title).put("items", JSONArray().apply { s.items.forEach { i -> put(JSONObject().put("label", i.label).put("value", i.value).put("support", i.support?.name)) } })) } })
        .put("vulkan", vk)
        .put("gles", gl?.let { JSONObject().put("renderer", it.renderer).put("version", it.version).put("extensions", JSONArray(it.extensions)) })
        .put("snapshot", JSONObject()
            .put("cpuLoad", snap.cpu.totalLoad?.toDouble()).put("cpuLoadSource", snap.cpu.loadSource.name)
            .put("freqKhz", JSONArray(snap.cpu.freqKhz))
            .put("memAvail", snap.memory.availableBytes).put("memTotal", snap.memory.totalBytes).put("memPressure", snap.memory.pressureLevel)
            .put("batteryPct", snap.battery.levelPct).put("batteryTempC", snap.battery.temperatureC.toDouble()).put("cycleCount", snap.battery.cycleCount)
            .put("thermalStatus", snap.thermal.status).put("headroom", snap.thermal.headroom?.toDouble())
            .put("storageFree", snap.storage.freeBytes).put("storageTotal", snap.storage.totalBytes))
        .put("telemetry7d", JSONArray().apply {
            c.db.telemetry().since(System.currentTimeMillis() - 7 * 86_400_000L).forEach { t ->
                put(JSONObject().put("t", t.time).put("bat", t.batteryLevel).put("temp", t.batteryTempC.toDouble()).put("ram", t.availRamBytes).put("thermal", t.thermalStatus))
            }
        })
        .toString(2)
}
