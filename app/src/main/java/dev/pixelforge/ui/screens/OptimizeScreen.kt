package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.optimize.Analysis
import dev.pixelforge.core.optimize.ExecutionReport
import dev.pixelforge.core.optimize.Recommendation
import dev.pixelforge.core.optimize.Severity
import dev.pixelforge.core.storage.ScanProgress
import dev.pixelforge.di.AppContainer
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.ProgressPanel
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.TierBadge
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.navigation.pfViewModel
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OptState {
    data object Idle : OptState
    data class Analyzing(val progress: ScanProgress?) : OptState
    data class Review(val analysis: Analysis, val selected: Set<String>) : OptState
    data class Executing(val count: Int) : OptState
    data class Done(val analysis: Analysis, val report: ExecutionReport) : OptState
    data class Failed(val message: String) : OptState
}

class OptimizeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<OptState>(OptState.Idle)
    val state = _state.asStateFlow()
    private var job: Job? = null

    fun analyze() {
        job?.cancel()
        _state.value = OptState.Analyzing(null)
        job = viewModelScope.launch {
            try {
                val a = c.optimizer.analyze { p -> _state.value = OptState.Analyzing(p) }
                _state.value = OptState.Review(a, a.recommendations.filter { it.defaultSelected }.map { it.id }.toSet())
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                _state.value = OptState.Failed(t.message ?: "Analysis failed")
            }
        }
    }

    fun toggle(id: String) {
        val s = _state.value as? OptState.Review ?: return
        _state.value = s.copy(selected = if (id in s.selected) s.selected - id else s.selected + id)
    }

    fun execute() {
        val s = _state.value as? OptState.Review ?: return
        val chosen = s.analysis.recommendations.filter { it.id in s.selected && it.action != null }
        _state.value = OptState.Executing(chosen.size)
        job = viewModelScope.launch {
            val report = c.optimizer.execute(chosen)
            _state.value = OptState.Done(s.analysis, report)
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = OptState.Idle
    }
}

@Composable
fun OptimizeScreen(nav: Navigator) {
    val vm = pfViewModel { OptimizeViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }

    PfScreen("Smart Optimize", "Analyse → recommend → confirm → execute → measure", onBack = nav::back) {
        when (val s = state) {
            OptState.Idle -> item {
                PfCard(Modifier.fillMaxWidth(), highlight = true) {
                    Text("What happens", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    listOf(
                        "1. PixelForge samples memory, thermal, battery and storage, and indexes accessible storage.",
                        "2. Rules propose only actions Android allows; each is tagged Safe, Advanced or Unavailable.",
                        "3. You review and confirm. Nothing runs without confirmation.",
                        "4. Selected actions run; storage, caches, RAM and thermal state are measured before and after.",
                        "5. A report is saved to History. Deleted files go to the recycle bin when enabled.",
                    ).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted, modifier = Modifier.padding(vertical = 2.dp)) }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = vm::analyze, modifier = Modifier.fillMaxWidth()) { Text("Analyse now") }
                }
            }
            is OptState.Analyzing -> item {
                ProgressPanel(
                    s.progress?.phase ?: "Sampling system",
                    s.progress?.let { "${it.filesSeen} files · ${dev.pixelforge.core.util.Format.bytes(it.bytesSeen)} · ${it.currentPath}" } ?: "Reading memory, thermal and battery state…",
                    null, onCancel = vm::cancel,
                )
            }
            is OptState.Review -> {
                item { AnalysisSummary(s.analysis) }
                val actionable = s.analysis.recommendations.filter { it.action != null }
                val advice = s.analysis.recommendations.filter { it.action == null }
                if (actionable.isNotEmpty()) {
                    item { SectionLabel("Actions (${s.selected.size} selected)") }
                    items(actionable, key = { it.id }) { r ->
                        RecommendationCard(r, checked = r.id in s.selected, onToggle = { vm.toggle(r.id) }, onOpen = null)
                    }
                    item {
                        Button(onClick = { confirm = true }, enabled = s.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                            Text("Review & run ${s.selected.size} action(s)")
                        }
                    }
                }
                if (advice.isNotEmpty()) {
                    item { SectionLabel("Advice & system shortcuts") }
                    items(advice, key = { it.id }) { r ->
                        RecommendationCard(r, checked = null, onToggle = {}, onOpen = r.settingsIntent?.let { i -> { ctx.launch(i) } })
                    }
                }
            }
            is OptState.Executing -> item { ProgressPanel("Running ${s.count} action(s)", "Measuring before and after…", null) }
            is OptState.Done -> {
                item {
                    PfCard(Modifier.fillMaxWidth(), highlight = true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (s.report.failures == 0) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = if (s.report.failures == 0) Pf.colors.success else Pf.colors.warning)
                            Spacer(Modifier.width(8.dp))
                            Text("Before / after report", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        s.report.metrics.forEach { m ->
                            KeyValue(m.name, "${m.before} → ${m.after}")
                            m.note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted) }
                        }
                        Text("All values above were measured on this device.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item { SectionLabel("Executed actions") }
                items(s.report.results) { (r, msg) ->
                    PfCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) { TierBadge(r.tier); Spacer(Modifier.width(8.dp)); Text(r.title, style = MaterialTheme.typography.titleSmall) }
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::analyze) { Text("Analyse again") }
                        OutlinedButton(onClick = { nav.go(dev.pixelforge.ui.navigation.Dest.TRASH) }) { Text("Recycle bin") }
                    }
                }
            }
            is OptState.Failed -> item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Analysis failed", style = MaterialTheme.typography.titleMedium, color = Pf.colors.danger)
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = vm::analyze) { Text("Retry") }
                }
            }
        }
    }

    val review = state as? OptState.Review
    if (confirm && review != null) {
        val chosen = review.analysis.recommendations.filter { it.id in review.selected }
        ConfirmDialog(
            "Confirm optimisation",
            "The following will run now:",
            "Run",
            onConfirm = { confirm = false; vm.execute() },
            onDismiss = { confirm = false },
        ) {
            chosen.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TierBadge(r.tier); Spacer(Modifier.width(6.dp))
                    Text(r.title, style = MaterialTheme.typography.bodySmall)
                }
                Text("Expected: ${r.benefit}${if (r.estimated) " (estimate)" else ""}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
            }
        }
    }
}

@Composable
private fun AnalysisSummary(a: Analysis) {
    PfCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Health index ${a.healthScore}/100", style = MaterialTheme.typography.titleMedium, color = scoreColor(a.healthScore))
                Text(
                    "${a.recommendations.count { it.action != null }} actionable · ${a.recommendations.count { it.action == null }} advisory" +
                        (a.scan?.let { " · ${it.totalFiles} files indexed in ${it.durationMs / 1000.0}s" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted,
                )
            }
        }
    }
}

@Composable
fun RecommendationCard(r: Recommendation, checked: Boolean?, onToggle: () -> Unit, onOpen: (() -> Unit)?) {
    val sevColor = when (r.severity) {
        Severity.HIGH -> Pf.colors.danger
        Severity.MEDIUM -> Pf.colors.warning
        Severity.LOW -> Pf.colors.cyan
        Severity.INFO -> Pf.colors.muted
    }
    PfCard(Modifier.fillMaxWidth(), onClick = if (checked != null) onToggle else onOpen) {
        Row(verticalAlignment = Alignment.Top) {
            if (checked != null) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = r.tier != Tier.UNAVAILABLE)
                Spacer(Modifier.width(4.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TierBadge(r.tier)
                }
                Spacer(Modifier.height(4.dp))
                Text(r.detail, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (r.estimated) "Estimate" else "Measured", if (r.estimated) Pf.colors.muted else Pf.colors.success)
                    Spacer(Modifier.width(6.dp))
                    Text(r.benefit, style = MaterialTheme.typography.labelMedium, color = sevColor)
                }
                if (onOpen != null) {
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = onOpen) { Text("Open") }
                }
            }
        }
    }
}
