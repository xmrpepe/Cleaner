package dev.pixelforge.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.pixelforge.core.settings.AppSettings
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.launch

@Composable
fun AutomationScreen(nav: Navigator) {
    val c = LocalContainer.current
    val s = rememberSettings()
    val caps = rememberCaps()
    val scope = rememberCoroutineScope()
    val work by WorkManager.getInstance(c.appContext).getWorkInfosForUniqueWorkFlow("pf_telemetry").collectAsStateWithLifecycle(emptyList())
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { c.capabilities.refresh() }
    fun upd(f: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(f) }

    PfScreen("Automation", "WorkManager routines — no persistent background service", onBack = nav::back) {
        if (!caps.notifications) {
            item {
                NoticeCard("Notifications are off", "Alerts need notification permission.", actionLabel = "Allow", onAction = {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }
        }
        item { SectionLabel("Routines") }
        item {
            Routine("Telemetry sampling", "Battery, RAM, storage and thermal snapshot every ${s.telemetryIntervalMin} min (feeds history charts and alerts). Each run takes milliseconds.", s.telemetryEnabled) { on -> upd { it.copy(telemetryEnabled = on) } }
        }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Sampling interval: ${s.telemetryIntervalMin} min", style = MaterialTheme.typography.bodyMedium)
                Slider(s.telemetryIntervalMin.toFloat(), { v -> upd { it.copy(telemetryIntervalMin = (v.toInt() / 15 * 15).coerceAtLeast(15)) } }, valueRange = 15f..120f, steps = 6)
                Text("Android enforces a 15-minute minimum and may defer runs in Doze.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                work.firstOrNull()?.let { w -> Text("State: ${w.state}${if (w.state == WorkInfo.State.ENQUEUED) " · next run scheduled by the system" else ""}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted) }
            }
        }
        item { Routine("Daily maintenance", "When idle and battery is not low: purge expired recycle-bin items and analyse storage. Notifies if > 50 MB of safe junk exists. Never deletes your files automatically.", s.autoDailyMaintenance) { on -> upd { it.copy(autoDailyMaintenance = on) } } }
        item {
            Routine("Low storage alert", "Notify when free space drops below ${s.lowStorageThresholdPct}%.", s.lowStorageAlert) { on -> upd { it.copy(lowStorageAlert = on) } }
        }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Threshold ${s.lowStorageThresholdPct}%", style = MaterialTheme.typography.bodySmall)
                Slider(s.lowStorageThresholdPct.toFloat(), { v -> upd { it.copy(lowStorageThresholdPct = v.toInt()) } }, valueRange = 5f..30f)
            }
        }
        item { Routine("High temperature alert", "Notify when battery ≥ ${s.highTempThresholdC.toInt()} °C or thermal status reaches Moderate.", s.highTempAlert) { on -> upd { it.copy(highTempAlert = on) } } }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Temperature ${s.highTempThresholdC.toInt()} °C", style = MaterialTheme.typography.bodySmall)
                Slider(s.highTempThresholdC, { v -> upd { it.copy(highTempThresholdC = v) } }, valueRange = 36f..50f)
            }
        }
        item { Routine("Battery saver advice", "Recommend Battery Saver at ≤ ${s.lowBatteryThresholdPct}% when unplugged and it is off.", s.lowBatteryAdvice) { on -> upd { it.copy(lowBatteryAdvice = on) } } }
        item { Routine("Developer mode diagnostics", "Show per-core, PSI and PixelForge self-overhead on the dashboard.", s.developerDiagnostics) { on -> upd { it.copy(developerDiagnostics = on) } } }
        item { Routine("Desktop mode", "When an external display is connected, automatically present the large dashboard on it.", s.desktopAutoPresent) { on -> upd { it.copy(desktopAutoPresent = on) } } }
    }
}

@Composable
private fun Routine(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    PfCard(Modifier.fillMaxWidth(), onClick = { onChange(!checked) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked, onChange)
        }
    }
}
