package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.apps.AppRepository
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.SegmentBar
import dev.pixelforge.ui.components.TierBadge
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.launch

private data class PendingAction(val title: String, val text: String, val destructive: Boolean, val run: suspend () -> Result<String>)

@Composable
fun AppDetailScreen(nav: Navigator, pkg: String) {
    val c = LocalContainer.current
    val apps by c.apps.apps.collectAsStateWithLifecycle()
    val caps = rememberCaps()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var bucket by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(pkg, caps.shizukuReady) {
        if (apps.isEmpty()) c.apps.refresh()
        if (caps.shizukuReady) bucket = c.appActions.standbyBucket(pkg)
    }
    val a = apps.firstOrNull { it.packageName == pkg }

    PfScreen(a?.label ?: pkg, pkg, onBack = nav::back) {
        if (a == null) {
            item { Text("Loading…", color = Pf.colors.muted) }
            return@PfScreen
        }
        val critical = AppRepository.isCritical(pkg)
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(pkg, 56.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.titleLarge)
                        Text("v${a.versionName ?: "?"} (${a.versionCode})", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (a.isSystem) Pill(if (a.isUpdatedSystem) "System (updated)" else "System", Pf.colors.cyan)
                            if (!a.enabled) Pill("Disabled", Pf.colors.warning)
                            if (a.isGame) Pill("Game", Pf.colors.accent)
                            if (critical) Pill("Protected", Pf.colors.danger)
                        }
                    }
                }
            }
        }
        message?.let { m -> item { NoticeCard("Result", m) } }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                c.appActions.launchIntent(pkg)?.let { i -> Button(onClick = { ctx.launch(i) }) { Text("Open") } }
                OutlinedButton(onClick = { ctx.launch(c.appActions.detailsIntent(pkg)) }) { Text("App info") }
                OutlinedButton(onClick = { ctx.launch(c.appActions.detailsIntent(pkg)) }) { Text("Storage & cache") }
                OutlinedButton(onClick = { ctx.launch(c.appActions.batteryOptimizationListIntent()) }) { Text("Battery") }
                if (!critical && (!a.isSystem || a.isUpdatedSystem)) {
                    OutlinedButton(onClick = { ctx.launch(c.appActions.uninstallIntent(pkg)) }) { Text(if (a.isUpdatedSystem) "Uninstall updates" else "Uninstall") }
                }
            }
        }
        item { SectionLabel("Details") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                KeyValue("Installed", Format.dateTime(a.firstInstall))
                KeyValue("Updated", Format.dateTime(a.lastUpdate))
                KeyValue("Installer", a.installer ?: "Unknown / preinstalled")
                KeyValue("Target / min SDK", "${a.targetSdk} / ${a.minSdk}")
                KeyValue("UID", a.uid.toString())
                KeyValue("Last used", a.lastUsed?.let { Format.ago(it) } ?: if (caps.usageAccess) "Not in last 30 days" else "Needs Usage Access")
                KeyValue("Foreground (30 d)", a.foregroundMs30d?.let { Format.duration(it) } ?: "—")
                KeyValue("Battery optimisation", if (a.batteryUnrestricted) "Exempt (unrestricted)" else "Optimised")
                bucket?.let { KeyValue("Standby bucket", it) }
                KeyValue("Stopped state", if (a.stopped) "Stopped (not run since install/force-stop)" else "Normal")
            }
        }
        a.sizes?.let { s ->
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Storage ${Format.bytes(s.total)}", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    SegmentBar(listOf(s.appBytes.toFloat() to Pf.colors.accent, (s.dataBytes - s.cacheBytes).coerceAtLeast(0).toFloat() to Pf.colors.cyan, s.cacheBytes.toFloat() to Pf.colors.warning))
                    KeyValue("App (code)", Format.bytes(s.appBytes))
                    KeyValue("User data", Format.bytes((s.dataBytes - s.cacheBytes).coerceAtLeast(0)))
                    KeyValue("Cache", Format.bytes(s.cacheBytes))
                }
            }
        }
        item { SectionLabel("Permissions (${a.permissions.count { it.granted }} granted)") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                val sorted = a.permissions.sortedWith(compareByDescending<dev.pixelforge.core.apps.PermissionGrant> { it.dangerous && it.granted }.thenBy { it.name })
                if (sorted.isEmpty()) Text("No permissions requested.", color = Pf.colors.muted)
                sorted.take(60).forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(p.name.substringAfterLast('.'), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = if (p.granted) MaterialTheme.colorScheme.onSurface else Pf.colors.muted)
                        if (p.dangerous) Pill(if (p.granted) "GRANTED" else "denied", if (p.granted) Pf.colors.warning else Pf.colors.muted)
                    }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Advanced management", Modifier.weight(1f))
                TierBadge(if (caps.shizukuReady) Tier.ADVANCED else Tier.UNAVAILABLE)
            }
        }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                if (!caps.shizukuReady) {
                    Text("Disabling packages, restricting background execution, clearing another app's cache and ART recompilation need Shizuku (ADB shell privileges). Connect it in Advanced.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                } else if (critical) {
                    Text("This is a protected system component. PixelForge will not disable or restrict it.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.warning)
                } else {
                    Text("Every change is logged and reversible from Advanced → Reversible changes.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = {
                            pending = PendingAction(
                                if (a.enabled) "Disable ${a.label}?" else "Enable ${a.label}?",
                                if (a.enabled) "The app will disappear from the launcher and stop running until re-enabled. Its data is kept. Reversible." else "Re-enables the package.",
                                a.enabled,
                            ) { c.appActions.setEnabled(pkg, a.label, !a.enabled) }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (a.enabled) "Disable package (reversible)" else "Enable package") }
                        OutlinedButton(onClick = {
                            pending = PendingAction("Restrict background?", "Sets RUN_ANY_IN_BACKGROUND to ignore (Android's “Restricted” battery mode). Notifications and sync may be delayed. Reversible.", false) {
                                c.appActions.setBackgroundRestricted(pkg, a.label, true)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Restrict background activity") }
                        OutlinedButton(onClick = {
                            pending = PendingAction("Clear cache?", "Runs `pm clear --cache-only`. Only cache files are removed; accounts and data stay.", false) { c.appActions.clearCache(pkg, a.label) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Clear cache") }
                        OutlinedButton(onClick = {
                            pending = PendingAction("Recompile with ART?", "Runs `cmd package compile -m speed-profile -f`. Uses the app's runtime profile; may take a minute.", false) { c.appActions.compileSpeedProfile(pkg, a.label) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("ART optimise (speed-profile)") }
                        OutlinedButton(onClick = {
                            pending = PendingAction("Force stop once?", "Stops all of the app's processes now. This is a one-off action, not an optimisation — the app restarts when needed.", false) { c.appActions.forceStop(pkg, a.label) }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Force stop (one-off)") }
                        if (a.isGame) {
                            OutlinedButton(onClick = {
                                pending = PendingAction("Game Mode: performance?", "Asks Android's GameManager to use performance mode for this game (only if the game supports it).", false) { c.appActions.setGameMode(pkg, a.label, "performance") }
                            }, modifier = Modifier.fillMaxWidth()) { Text("Game Mode → performance") }
                        }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        ConfirmDialog(p.title, p.text, "Confirm", destructive = p.destructive, onConfirm = {
            pending = null
            scope.launch {
                val r = p.run()
                message = r.fold({ "Done. ${it.take(200)}" }, { "Failed: ${it.message}" })
                c.apps.refresh(force = true)
            }
        }, onDismiss = { pending = null })
    }
}
