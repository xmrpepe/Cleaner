package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.TierBadge
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import org.json.JSONArray

@Composable
fun HistoryScreen(nav: Navigator) {
    val c = LocalContainer.current
    var tab by rememberSaveable { mutableStateOf(0) }
    val reports by c.db.reports().observeAll().collectAsStateWithLifecycle(emptyList())
    val log by c.db.actionLog().observeRecent().collectAsStateWithLifecycle(emptyList())

    PfScreen("History", "Optimisation reports and the complete audit trail", onBack = nav::back) {
        item {
            Row {
                FilterChip(tab == 0, { tab = 0 }, { Text("Reports (${reports.size})") }, Modifier.padding(end = 6.dp))
                FilterChip(tab == 1, { tab = 1 }, { Text("Audit log (${log.size})") })
            }
        }
        if (tab == 0) {
            if (reports.isEmpty()) item { Text("No Smart Optimize runs yet.", color = Pf.colors.muted) }
            items(reports, key = { it.id }) { r ->
                var open by remember { mutableStateOf(false) }
                PfCard(Modifier.fillMaxWidth(), onClick = { open = !open }) {
                    Text(Format.dateTime(r.time), style = MaterialTheme.typography.titleSmall)
                    Text(r.summary, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    if (open) {
                        runCatching { JSONArray(r.metricsJson) }.getOrNull()?.let { a ->
                            for (i in 0 until a.length()) {
                                val m = a.getJSONObject(i)
                                KeyValue(m.optString("name"), "${m.optString("before")} → ${m.optString("after")}")
                            }
                        }
                        runCatching { JSONArray(r.actionsJson) }.getOrNull()?.let { a ->
                            for (i in 0 until a.length()) {
                                val o = a.getJSONObject(i)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    TierBadge(runCatching { Tier.valueOf(o.optString("tier")) }.getOrDefault(Tier.SAFE))
                                    Column(Modifier.padding(start = 8.dp)) {
                                        Text(o.optString("title"), style = MaterialTheme.typography.bodySmall)
                                        Text(o.optString("result"), style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            if (log.isEmpty()) item { Text("Nothing logged yet.", color = Pf.colors.muted) }
            items(log, key = { it.id }) { e ->
                var open by remember { mutableStateOf(false) }
                PfCard(Modifier.fillMaxWidth(), onClick = { open = !open }, contentPadding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.bodyMedium)
                            Text("${Format.dateTime(e.time)} · ${e.category}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                        }
                        Pill(if (e.success) "OK" else "FAILED", if (e.success) Pf.colors.success else Pf.colors.danger)
                        TierBadge(runCatching { Tier.valueOf(e.tier) }.getOrDefault(Tier.SAFE), Modifier.padding(start = 6.dp))
                    }
                    if (open) Text(e.detail, style = MaterialTheme.typography.labelSmall, fontFamily = dev.pixelforge.ui.theme.Mono, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}
