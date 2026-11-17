package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.optimize.ActionOutcome
import dev.pixelforge.core.optimize.PerformanceProfile
import dev.pixelforge.core.optimize.ProfileCatalog
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.TierBadge
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.launch

@Composable
fun ProfilesScreen(nav: Navigator) {
    val c = LocalContainer.current
    val caps = rememberCaps()
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<PerformanceProfile?>(null) }
    var results by remember { mutableStateOf<Pair<String, List<ActionOutcome>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var restoreMsg by remember { mutableStateOf<String?>(null) }

    PfScreen("Performance profiles", "Only supported Android controls — each step verified and reversible", onBack = nav::back) {
        if (!caps.shizukuReady && !caps.writeSecureSettings) {
            item {
                NoticeCard(
                    "Most profile steps need elevated access",
                    "Refresh rate, animation scales, Battery Saver, Game Mode and fixed performance mode are system settings. Without Shizuku or an ADB-granted permission, PixelForge applies only its own preferences and opens the relevant system screens. Unavailable steps are skipped, never faked.",
                    color = Pf.colors.warning, actionLabel = "Set up Advanced access", onAction = { nav.go(Dest.ADVANCED) },
                )
            }
        }
        results?.let { (name, list) ->
            item {
                PfCard(Modifier.fillMaxWidth(), highlight = true) {
                    Text("$name applied", style = MaterialTheme.typography.titleMedium)
                    list.forEach { o ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                o.status.name.replace('_', ' ').lowercase(),
                                when (o.status) {
                                    ActionOutcome.Status.VERIFIED, ActionOutcome.Status.APPLIED -> Pf.colors.success
                                    ActionOutcome.Status.OPENED -> Pf.colors.cyan
                                    ActionOutcome.Status.SKIPPED_UNAVAILABLE -> Pf.colors.muted
                                    ActionOutcome.Status.FAILED -> Pf.colors.danger
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(o.action.title, style = MaterialTheme.typography.bodySmall)
                                if (o.detail.isNotBlank()) Text(o.detail, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                            }
                        }
                    }
                }
            }
        }
        restoreMsg?.let { item { NoticeCard("Restore", it) } }
        items(ProfileCatalog.all, key = { it.id }) { p ->
            val active = settings.activeProfile == p.id
            PfCard(Modifier.fillMaxWidth(), highlight = active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (active) Pill("ACTIVE", Pf.colors.accent)
                }
                Text(p.summary, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                Spacer(Modifier.height(8.dp))
                p.actions.forEach { a ->
                    val tier = c.profiles.availability(a)
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        TierBadge(tier)
                        Spacer(Modifier.width(8.dp))
                        Text(a.title, style = MaterialTheme.typography.bodySmall, color = if (tier == Tier.UNAVAILABLE) Pf.colors.muted else MaterialTheme.colorScheme.onSurface)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { confirm = p }, enabled = !busy) { Text(if (active) "Re-apply" else "Apply") }
            }
        }
        item { SectionLabel("Custom profile") }
        item {
            NoticeCard(
                "Build your own", "Combine individual steps from the Advanced screen (settings, Game Mode, background restrictions per app). Every change appears in Reversible changes and can be undone individually.",
                actionLabel = "Open Advanced", onAction = { nav.go(Dest.ADVANCED) },
            )
        }
        item {
            OutlinedButton(onClick = {
                scope.launch {
                    val (ok, total) = c.reverter.revertAll()
                    restoreMsg = "Restored $ok of $total changed setting(s) to their original values."
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("Restore all original settings") }
        }
    }

    confirm?.let { p ->
        ConfirmDialog(
            "Apply ${p.name}?",
            "Each step is checked against current privileges. System settings are recorded with their previous values so they can be restored.",
            "Apply", onConfirm = {
                confirm = null
                busy = true
                scope.launch {
                    results = p.name to c.profiles.apply(p) { intent -> ctx.launch(intent) }
                    busy = false
                }
            }, onDismiss = { confirm = null },
        ) {
            p.actions.forEach { a -> Text("• ${a.title}: ${a.explanation}", style = MaterialTheme.typography.labelSmall) }
        }
    }
}
