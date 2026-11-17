package dev.pixelforge.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.shizuku.ShizukuManager
import dev.pixelforge.core.shizuku.ShizukuState
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.StatusDot
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Mono
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.launch

@Composable
fun AdvancedScreen(nav: Navigator) {
    val c = LocalContainer.current
    val caps = rememberCaps()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val changes by c.db.changes().observeAll().collectAsStateWithLifecycle(emptyList())
    var console by remember { mutableStateOf("") }
    var consoleOut by remember { mutableStateOf<String?>(null) }
    var confirmCmd by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }
    val pkg = ctx.packageName

    fun copy(text: String) {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("adb", text))
        ctx.toast("Copied")
    }

    PfScreen("Advanced", "Optional elevated access · never root · fully audited", onBack = nav::back) {
        item {
            PfCard(Modifier.fillMaxWidth(), highlight = caps.shizukuReady) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(if (caps.shizukuReady) Pf.colors.success else Pf.colors.muted)
                    Spacer(Modifier.width(8.dp))
                    Text("Shizuku · ${caps.shizuku.label}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                if (caps.shizukuReady) {
                    val uid = c.shizuku.serverUid()
                    KeyValue("Privilege", when (uid) { 0 -> "root (uid 0) — PixelForge still only uses shell-level commands"; 2000 -> "ADB shell (uid 2000)"; else -> "uid $uid" })
                    KeyValue("Server version", c.shizuku.serverVersion()?.toString() ?: "?")
                    Text("Shizuku gives PixelForge the same rights as `adb shell`: package management, settings, dumpsys. It does not grant root and cannot bypass Android's security model.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                } else {
                    Text(
                        "Shizuku is a separate open-source app that exposes ADB-level APIs to apps you approve. Start it with Wireless debugging (no PC needed on Android 11+), then grant PixelForge permission.",
                        style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (caps.shizuku) {
                            ShizukuState.NO_PERMISSION -> Button(onClick = { c.shizuku.requestPermission() }) { Text("Grant permission") }
                            ShizukuState.NOT_INSTALLED -> Button(onClick = { ctx.launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuManager.SHIZUKU_PACKAGE}")), Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))) }) { Text("Get Shizuku") }
                            ShizukuState.NOT_RUNNING -> Button(onClick = { ctx.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)?.let { ctx.launch(it) } }) { Text("Open Shizuku") }
                            ShizukuState.DISABLED -> Button(onClick = { scope.launch { c.settings.update { it.copy(shizukuEnabled = true) } } }) { Text("Enable integration") }
                            ShizukuState.READY -> Unit
                        }
                        OutlinedButton(onClick = { ctx.launch(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Developer options") }
                    }
                }
            }
        }
        item { SectionLabel("ADB-granted permissions") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text("Alternatively grant individual development permissions once from a computer (or via Shizuku). Each unlocks specific features without a running service.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                listOf(
                    Triple("WRITE_SECURE_SETTINGS", caps.writeSecureSettings, "Profiles: animation scales, stay-awake, desktop flags"),
                    Triple("DUMP", caps.dump, "Thermal sensors, per-process memory, battery stats"),
                    Triple("BATTERY_STATS", caps.batteryStats, "Battery state-of-health when the system exposes it"),
                ).forEach { (perm, granted, use) ->
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (granted) "GRANTED" else "NOT GRANTED", if (granted) Pf.colors.success else Pf.colors.muted)
                        Spacer(Modifier.width(8.dp))
                        Text(perm, style = MaterialTheme.typography.titleSmall)
                    }
                    Text(use, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                    val cmd = "adb shell pm grant $pkg android.permission.$perm"
                    Text(cmd, fontFamily = Mono, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { copy(cmd) }) { Text("Copy command") }
                        if (caps.shizukuReady && !granted) TextButton(onClick = {
                            scope.launch {
                                val r = c.privileged.shell("pm grant $pkg android.permission.$perm", "Grant $perm to PixelForge")
                                c.capabilities.refresh()
                                msg = if (r?.ok == true) "$perm granted" else "Failed: ${r?.output}"
                            }
                        }) { Text("Grant via Shizuku") }
                        if (granted && caps.shizukuReady) TextButton(onClick = {
                            scope.launch { c.privileged.shell("pm revoke $pkg android.permission.$perm", "Revoke $perm"); c.capabilities.refresh() }
                        }) { Text("Revoke") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("Usage Access via ADB: adb shell appops set $pkg GET_USAGE_STATS allow", fontFamily = Mono, style = MaterialTheme.typography.labelSmall)
            }
        }
        msg?.let { m -> item { NoticeCard("Result", m) } }
        item { SectionLabel("Wireless debugging workflow") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                listOf(
                    "1. Settings → About phone → tap Build number 7× to enable Developer options.",
                    "2. Developer options → Wireless debugging → On (same Wi-Fi network).",
                    "3. In Shizuku tap “Start via Wireless debugging” and pair using the notification code.",
                    "4. Return here and tap Grant permission.",
                    "Shizuku stops on reboot; repeat step 3 after restarting.",
                ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                OutlinedButton(onClick = { ctx.launch(Intent("android.settings.ADB_WIRELESS_SETTINGS"), Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }) { Text("Open Wireless debugging") }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Reversible changes (${changes.count { !it.reverted }} active)", Modifier.weight(1f))
                TextButton(onClick = { scope.launch { val (ok, n) = c.reverter.revertAll(); msg = "Restored $ok of $n" } }, enabled = caps.shizukuReady || caps.writeSecureSettings) { Text("Restore all") }
            }
        }
        if (changes.isEmpty()) item { Text("No system changes have been made by PixelForge.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted) }
        items(changes.take(100), key = { it.id }) { ch ->
            PfCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ch.label, style = MaterialTheme.typography.bodyMedium)
                        Text("${ch.target}/${ch.key}: ${ch.previousValue ?: "unset"} → ${ch.newValue} · ${Format.dateTime(ch.time)} · ${ch.source}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                    }
                    if (ch.reverted) Pill("Restored", Pf.colors.muted)
                    else TextButton(onClick = { scope.launch { msg = if (c.reverter.revert(ch)) "Restored ${ch.label}" else "Could not restore (privileges missing?)" } }) { Text("Undo") }
                }
            }
        }
        if (caps.shizukuReady) {
            item { SectionLabel("ADB shell console") }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Runs with ADB shell privileges via Shizuku. Every command is confirmed and written to the audit log.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    OutlinedTextField(console, { console = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono), placeholder = { Text("e.g. dumpsys battery") })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { confirmCmd = console }, enabled = console.isNotBlank()) { Text("Run") }
                        listOf("dumpsys battery", "getprop ro.soc.model", "pm list packages -d").forEach { q ->
                            TextButton(onClick = { console = q }) { Text(q.substringBefore(' '), style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                    consoleOut?.let { out ->
                        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                            Text(out, fontFamily = Mono, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }

    confirmCmd?.let { cmd ->
        val dangerous = Regex("""\b(rm|reboot|wipe|format|uninstall|pm clear(?! --cache-only)|svc power|dd)\b""").containsMatchIn(cmd)
        ConfirmDialog(
            "Run shell command?", cmd + if (dangerous) "\n\nThis command can delete data or disrupt the device." else "", "Run", destructive = dangerous,
            onConfirm = {
                confirmCmd = null
                scope.launch { consoleOut = c.privileged.shell(cmd, "Console: $cmd", timeoutMs = 60_000)?.let { "exit ${it.exitCode}\n${it.output}" } ?: "Shizuku unavailable" }
            }, onDismiss = { confirmCmd = null },
        )
    }
}
