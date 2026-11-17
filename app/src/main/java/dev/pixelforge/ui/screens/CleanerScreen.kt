package dev.pixelforge.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.pixelforge.core.storage.DeleteOutcome
import dev.pixelforge.core.storage.DirUsage
import dev.pixelforge.core.storage.FileKind
import dev.pixelforge.core.storage.JunkCategory
import dev.pixelforge.core.storage.ScanOptions
import dev.pixelforge.core.storage.ScanProgress
import dev.pixelforge.core.storage.ScanResult
import dev.pixelforge.core.storage.ScannedFile
import dev.pixelforge.core.util.Format
import dev.pixelforge.di.AppContainer
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.ProgressPanel
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.SegmentBar
import dev.pixelforge.ui.components.TreeNode
import dev.pixelforge.ui.components.Treemap
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.navigation.pfViewModel
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class ScanMode(val label: String, val description: String) {
    QUICK("Quick", "Temporary files, obsolete APKs and empty folders."),
    DEEP("Deep", "Everything accessible, including verified duplicates."),
    MEDIA("Media", "Duplicates, similar photos, old screenshots and large videos."),
    APK("APK", "Installer packages: obsolete and not installed."),
    LARGE("Large", "Biggest files across accessible storage."),
}

data class CleanerUi(
    val mode: ScanMode = ScanMode.QUICK,
    val scanning: Boolean = false,
    val progress: ScanProgress? = null,
    val result: ScanResult? = null,
    val selected: Set<String> = emptySet(),
    val openCategory: JunkCategory? = null,
    val mapPath: List<DirUsage> = emptyList(),
    val lastOutcome: DeleteOutcome? = null,
    val deleting: Boolean = false,
    val error: String? = null,
)

class CleanerViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(CleanerUi())
    val ui = _ui.asStateFlow()
    private var job: Job? = null

    fun setMode(m: ScanMode) = _ui.update { it.copy(mode = m) }

    fun scan() {
        job?.cancel()
        val mode = _ui.value.mode
        _ui.update { it.copy(scanning = true, progress = null, result = null, selected = emptySet(), openCategory = null, mapPath = emptyList(), lastOutcome = null, error = null) }
        job = viewModelScope.launch {
            try {
                val s = c.settings.current()
                val r = c.scanner.scan(
                    options = ScanOptions(
                        deep = mode == ScanMode.DEEP,
                        findDuplicates = mode == ScanMode.DEEP || mode == ScanMode.MEDIA,
                        findSimilarImages = mode == ScanMode.MEDIA,
                        largeFileBytes = s.largeFileThresholdMb * 1024L * 1024L,
                        oldDownloadDays = s.oldDownloadDays,
                        oldScreenshotDays = s.oldScreenshotDays,
                        exclusions = s.scanExclusions,
                    ),
                    onProgress = { p -> _ui.update { it.copy(progress = p) } },
                )
                val pre = r.categories.filterKeys { it in visibleCategories(mode) }.values.flatten().filter { it.selectedByDefault }.map { it.path }.toSet()
                _ui.update { it.copy(scanning = false, result = r, selected = pre, mapPath = listOf(r.tree)) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) { _ui.update { it.copy(scanning = false) }; throw t }
                _ui.update { it.copy(scanning = false, error = t.message) }
            }
        }
    }

    fun cancel() { job?.cancel(); _ui.update { it.copy(scanning = false) } }

    fun toggle(path: String) = _ui.update { it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path) }

    fun setAll(cat: JunkCategory, on: Boolean) = _ui.update { u ->
        val paths = u.result?.categories?.get(cat).orEmpty().filter { !it.keep || !on }.map { it.path }.toSet()
        u.copy(selected = if (on) u.selected + paths else u.selected - paths)
    }

    fun open(cat: JunkCategory?) = _ui.update { it.copy(openCategory = cat) }

    fun drill(d: DirUsage) = _ui.update { it.copy(mapPath = it.mapPath + d) }
    fun up() = _ui.update { if (it.mapPath.size > 1) it.copy(mapPath = it.mapPath.dropLast(1)) else it }

    fun delete(paths: List<String>) {
        _ui.update { it.copy(deleting = true) }
        viewModelScope.launch {
            val s = c.settings.current()
            val outcome = c.trash.delete(paths, s.useRecycleBin, "Cleaner (${_ui.value.mode.label})")
            val removed = outcome.succeeded.toSet()
            _ui.update { u ->
                u.copy(
                    deleting = false, lastOutcome = outcome, selected = u.selected - removed,
                    result = u.result?.copy(categories = u.result.categories.mapValues { (_, l) -> l.filter { it.path !in removed } }),
                )
            }
        }
    }

    companion object {
        fun visibleCategories(m: ScanMode): List<JunkCategory> = when (m) {
            ScanMode.QUICK -> listOf(JunkCategory.TEMP_FILES, JunkCategory.OBSOLETE_APKS, JunkCategory.EMPTY_FOLDERS)
            ScanMode.DEEP -> JunkCategory.entries.filter { it != JunkCategory.SIMILAR_IMAGES }
            ScanMode.MEDIA -> listOf(JunkCategory.DUPLICATES, JunkCategory.SIMILAR_IMAGES, JunkCategory.OLD_SCREENSHOTS, JunkCategory.LARGE_FILES)
            ScanMode.APK -> listOf(JunkCategory.OBSOLETE_APKS, JunkCategory.UNINSTALLED_APKS)
            ScanMode.LARGE -> listOf(JunkCategory.LARGE_FILES, JunkCategory.OLD_DOWNLOADS, JunkCategory.REDUNDANT_ARCHIVES)
        }
    }
}

@Composable
fun CleanerScreen(nav: Navigator) {
    val vm = pfViewModel { CleanerViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val caps = rememberCaps()
    val settings = rememberSettings()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<List<ScannedFile>?>(null) }

    val open = ui.openCategory
    if (open != null && ui.result != null) {
        CategoryDetail(open, ui, vm, onBack = { vm.open(null) }, onDelete = { confirm = it })
    } else {
        PfScreen("Cleaner", "Storage analysis within Android's permission model", actions = {
            TextButton(onClick = { nav.go(Dest.TRASH) }) { Text("Recycle bin") }
        }) {
            if (!caps.allFilesAccess) {
                item {
                    NoticeCard(
                        "File access required",
                        "To analyse shared storage PixelForge needs “All files access”. It never reads other apps' private data (Android/data and Android/obb stay protected), and nothing is deleted without your confirmation.",
                        icon = Icons.Filled.Lock, color = Pf.colors.accent, actionLabel = "Grant access",
                        onAction = { ctx.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")), Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) },
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    ScanMode.entries.forEach { m -> FilterChip(selected = ui.mode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) }) }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text(ui.mode.description, style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = vm::scan, enabled = caps.allFilesAccess && !ui.scanning, modifier = Modifier.fillMaxWidth()) {
                        Text(if (ui.result == null) "Start ${ui.mode.label.lowercase()} scan" else "Rescan")
                    }
                }
            }
            if (ui.scanning) {
                item {
                    val p = ui.progress
                    ProgressPanel(p?.phase ?: "Starting", p?.let { "${it.filesSeen} files · ${Format.bytes(it.bytesSeen)}\n${it.currentPath}" } ?: "", null, onCancel = vm::cancel)
                }
            }
            ui.error?.let { e -> item { NoticeCard("Scan failed", e, color = Pf.colors.danger) } }
            ui.lastOutcome?.let { o ->
                item {
                    NoticeCard(
                        "Cleanup complete",
                        "${o.succeeded.size} item(s) removed" + (if (o.bytesTrashed > 0) " · ${Format.bytes(o.bytesTrashed)} moved to recycle bin (restorable for ${settings.recycleRetentionDays} days)" else "") +
                            (if (o.bytesFreed > 0) " · ${Format.bytes(o.bytesFreed)} freed" else "") + (if (o.failed.isNotEmpty()) " · ${o.failed.size} failed: ${o.failed.first().second}" else ""),
                        color = if (o.failed.isEmpty()) Pf.colors.success else Pf.colors.warning,
                    )
                }
            }
            val r = ui.result
            if (r != null) {
                item { SummaryCard(r) }
                val cats = CleanerViewModel.visibleCategories(ui.mode)
                item { SectionLabel("Findings") }
                items(cats, key = { it.name }) { cat ->
                    val list = r.categories[cat].orEmpty()
                    val sel = list.filter { it.path in ui.selected }
                    PfCard(Modifier.fillMaxWidth(), onClick = { vm.open(cat) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cat.label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (list.isEmpty()) "Nothing found" else "${list.size} item(s) · ${Format.bytes(r.bytesIn(cat))}",
                                    style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted,
                                )
                            }
                            if (sel.isNotEmpty()) Pill("${sel.size} selected", Pf.colors.accent)
                        }
                    }
                }
                val selectedFiles = r.categories.filterKeys { it in cats }.values.flatten().filter { it.path in ui.selected }.distinctBy { it.path }
                item {
                    Button(
                        onClick = { confirm = selectedFiles },
                        enabled = selectedFiles.isNotEmpty() && !ui.deleting,
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                    ) { Text(if (selectedFiles.isEmpty()) "Select items to clean" else "Review ${selectedFiles.size} item(s) · ${Format.bytes(selectedFiles.sumOf { it.size })}") }
                }
                item { SectionLabel("Storage map") }
                item { StorageMap(ui, vm) }
                if (r.inaccessible.isNotEmpty()) {
                    item {
                        NoticeCard(
                            "Protected locations not scanned", "${r.inaccessible.size} location(s), e.g. Android/data and Android/obb, belong to individual apps. Use each app's storage settings (Apps → app → Storage) to clear its data or cache.",
                            icon = Icons.Filled.FolderOpen, actionLabel = "Open Apps", onAction = { nav.go(Dest.APPS) },
                        )
                    }
                }
            }
            item {
                NoticeCard(
                    "Application storage", "Per-app code, data and cache sizes are in Apps (requires Usage Access). Android only lets each app clear its own cache; system-wide trimming is available in Smart Optimize with Shizuku.",
                    actionLabel = "Open Apps", onAction = { nav.go(Dest.APPS) },
                )
            }
        }
    }

    confirm?.let { files ->
        ConfirmDialog(
            title = "Delete ${files.size} item(s)?",
            text = (if (settings.useRecycleBin) "Files move to PixelForge's recycle bin and can be restored for ${settings.recycleRetentionDays} days." else "Recycle bin is OFF — files will be permanently deleted.") +
                " Total ${Format.bytes(files.sumOf { it.size })}.",
            confirmLabel = if (settings.useRecycleBin) "Move to recycle bin" else "Delete permanently",
            destructive = !settings.useRecycleBin,
            onConfirm = { vm.delete(files.map { it.path }); confirm = null },
            onDismiss = { confirm = null },
        ) {
            files.take(8).forEach { Text("• ${it.name} (${Format.bytes(it.size)})", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            if (files.size > 8) Text("…and ${files.size - 8} more", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
        }
    }
}

@Composable
private fun SummaryCard(r: ScanResult) {
    val colors = listOf(Pf.colors.accent, Pf.colors.cyan, androidx.compose.ui.graphics.Color(0xFFA78BFA), Pf.colors.success, Pf.colors.warning, androidx.compose.ui.graphics.Color(0xFF4C8DFF), Pf.colors.muted)
    PfCard(Modifier.fillMaxWidth()) {
        Text("${r.totalFiles} files · ${Format.bytes(r.totalBytes)} in shared storage", style = MaterialTheme.typography.titleSmall)
        Text("Indexed in ${"%.1f".format(r.durationMs / 1000.0)} s", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
        Spacer(Modifier.height(10.dp))
        SegmentBar(FileKind.entries.mapIndexed { i, k -> (r.byKind[k] ?: 0L).toFloat() to colors[i % colors.size] })
        Spacer(Modifier.height(8.dp))
        FileKind.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth()) {
                pair.forEach { k ->
                    val i = FileKind.entries.indexOf(k)
                    Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        dev.pixelforge.ui.components.StatusDot(colors[i % colors.size])
                        Spacer(Modifier.width(6.dp))
                        Text("${k.label} ${Format.bytes(r.byKind[k] ?: 0)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageMap(ui: CleanerUi, vm: CleanerViewModel) {
    val node = ui.mapPath.lastOrNull() ?: return
    PfCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.mapPath.joinToString(" / ") { if (it == ui.mapPath.first()) "Internal" else it.name },
                style = MaterialTheme.typography.labelMedium, color = Pf.colors.muted, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (ui.mapPath.size > 1) TextButton(onClick = vm::up) { Text("Up") }
        }
        val children = node.children
        val other = node.bytes - children.sumOf { it.bytes }
        Treemap(
            children.map { TreeNode(it.path, it.name, it.bytes) } + listOfNotNull(if (other > 0) TreeNode("__files", "Files here", other) else null),
            onClick = { t -> children.firstOrNull { it.path == t.key }?.takeIf { it.children.isNotEmpty() }?.let(vm::drill) },
        )
        Text("Tap a block to drill in. Map depth is limited to three levels for speed.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
    }
}

@Composable
private fun CategoryDetail(cat: JunkCategory, ui: CleanerUi, vm: CleanerViewModel, onBack: () -> Unit, onDelete: (List<ScannedFile>) -> Unit) {
    val list = ui.result?.categories?.get(cat).orEmpty()
    val ctx = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onBack)
    PfScreen(cat.label, cat.description, onBack = onBack) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val selected = list.filter { it.path in ui.selected }
                Text("${selected.size}/${list.size} selected · ${Format.bytes(selected.sumOf { it.size })}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setAll(cat, true) }) { Text("Select all") }
                TextButton(onClick = { vm.setAll(cat, false) }) { Text("None") }
            }
        }
        if (cat == JunkCategory.DUPLICATES || cat == JunkCategory.SIMILAR_IMAGES) {
            item { Text("The item marked KEEP is proposed as the copy to retain. Review groups before deleting.", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted) }
        }
        items(list.size) { i ->
            val f = list[i]
            Column {
                if (f.groupId >= 0 && (i == 0 || list[i - 1].groupId != f.groupId)) {
                    Text("Group ${f.groupId + 1}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                }
                PfCard(Modifier.fillMaxWidth(), onClick = { vm.toggle(f.path) }, contentPadding = 10.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = f.path in ui.selected, onCheckedChange = { vm.toggle(f.path) })
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                if (f.keep) { Spacer(Modifier.width(6.dp)); Pill("KEEP", Pf.colors.success) }
                            }
                            Text("${Format.bytes(f.size)} · ${Format.date(f.modified)} · ${f.reason}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(File(f.path).parent?.removePrefix("/storage/emulated/0") ?: "", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (!f.isDirectory) {
                            TextButton(onClick = { FileOpen.open(ctx, File(f.path)) }) { Text("Open") }
                        }
                    }
                }
            }
        }
        item {
            val sel = list.filter { it.path in ui.selected }
            Button(onClick = { onDelete(sel) }, enabled = sel.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Delete ${sel.size} selected") }
        }
    }
}
