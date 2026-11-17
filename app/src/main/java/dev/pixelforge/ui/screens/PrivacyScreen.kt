package dev.pixelforge.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pixelforge.core.privacy.PrivacyReport
import dev.pixelforge.core.privacy.SpecialAccessApp
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.StatusDot
import dev.pixelforge.ui.components.postureColor
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PrivacyScreen(nav: Navigator) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val report by produceState<PrivacyReport?>(null) {
        value = withContext(Dispatchers.Default) { c.privacy.analyze(c.apps.refresh()) }
    }
    var expanded by remember { mutableStateOf<String?>(null) }

    PfScreen("Privacy & Security", "Review powerful access — based only on what Android reports", onBack = nav::back) {
        val r = report
        if (r == null) {
            item { Text("Analysing permissions…", color = Pf.colors.muted) }
            return@PfScreen
        }
        item { SectionLabel("Security posture") }
        items(r.checks) { ch ->
            PfCard(Modifier.fillMaxWidth(), onClick = ch.settingsAction?.let { a -> { ctx.launch(Intent(a)) } }, contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(postureColor(ch.posture))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(ch.title, style = MaterialTheme.typography.bodyMedium)
                        ch.advice?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted) }
                    }
                    Text(ch.value, style = MaterialTheme.typography.bodySmall, color = postureColor(ch.posture))
                }
            }
        }
        item { SectionLabel("Permission overview (user apps)") }
        items(r.groups, key = { it.id }) { g ->
            PfCard(Modifier.fillMaxWidth(), onClick = { expanded = if (expanded == g.id) null else g.id }, contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Pill("${g.apps.size} app(s)", if (g.apps.isEmpty()) Pf.colors.muted else if (g.id in setOf("bg_location", "microphone", "camera", "sms")) Pf.colors.warning else Pf.colors.cyan)
                }
                if (expanded == g.id) {
                    g.apps.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(a.packageName, 28.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(a.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { nav.app(a.packageName) }) { Text("Manage") }
                        }
                    }
                }
            }
        }
        item {
            OutlinedButton(onClick = { ctx.launch(Intent("android.intent.action.REVIEW_PERMISSION_USAGE"), Intent(Settings.ACTION_PRIVACY_SETTINGS)) }) { Text("Privacy dashboard (recent usage)") }
        }
        specialSection("Accessibility services", "Can observe and control the screen. Keep only ones you trust.", r.accessibility, Settings.ACTION_ACCESSIBILITY_SETTINGS, nav)
        specialSection("Notification access", "Can read every notification, including one-time codes.", r.notificationListeners, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, nav)
        specialSection("Device administrators", "Can lock or wipe the device and enforce policies.", r.deviceAdmins, Settings.ACTION_SECURITY_SETTINGS, nav)
        specialSection("Can request app installs", "Check “Install unknown apps” for each.", r.installSources, Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, nav)
        specialSection("VPN providers", if (r.vpnActive) "A VPN is active now." else "No VPN active.", r.vpnApps, Settings.ACTION_VPN_SETTINGS, nav)
        item {
            NoticeCard(
                "No malware scanning claims",
                "PixelForge does not claim to detect malware. Google Play Protect is Android's built-in scanner; this screen highlights access you may want to revoke.",
                color = Pf.colors.muted, actionLabel = "Security settings", onAction = { ctx.launch(Intent(Settings.ACTION_SECURITY_SETTINGS)) },
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.specialSection(title: String, explain: String, apps: List<SpecialAccessApp>, action: String, nav: Navigator) {
    item { SectionLabel("$title (${apps.size})") }
    item {
        val ctx = LocalContext.current
        PfCard(Modifier.fillMaxWidth()) {
            Text(explain, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
            apps.forEach { a ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(a.packageName, 28.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).let { m -> m }) {
                        Text(a.label, style = MaterialTheme.typography.bodySmall)
                        Text(a.detail, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                    }
                    OutlinedButton(onClick = { nav.app(a.packageName) }) { Text("App") }
                }
            }
            OutlinedButton(onClick = { ctx.launch(Intent(action), Intent(Settings.ACTION_SETTINGS)) }, modifier = Modifier.padding(top = 6.dp)) { Text("Review in Settings") }
        }
    }
}
