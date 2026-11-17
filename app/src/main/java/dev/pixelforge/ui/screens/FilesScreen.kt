package dev.pixelforge.ui.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.pixelforge.core.data.FileBookmark
import dev.pixelforge.core.files.Entry
import dev.pixelforge.core.files.OpProgress
import dev.pixelforge.core.files.Volume
import dev.pixelforge.core.storage.FileKind
import dev.pixelforge.core.storage.FileKinds
import dev.pixelforge.core.util.Format
import dev.pixelforge.di.AppContainer
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.ProgressPanel
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.components.SegmentBar
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.navigation.pfViewModel
import dev.pixelforge.ui.theme.Mono
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class FileSort(val label: String) { NAME("Name"), SIZE("Size"), DATE("Date"), TYPE("Type") }

data class FilesUi(
    val stack: List<Entry> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val selection: Set<String> = emptySet(),
    val clipboard: List<Entry> = emptyList(),
    val clipboardMove: Boolean = false,
    val sort: FileSort = FileSort.NAME,
    val showHidden: Boolean = false,
    val searchResults: List<Entry>? = null,
    val busy: OpProgress? = null,
    val busyTitle: String = "",
    val message: String? = null,
    val loading: Boolean = false,
)

class FilesViewModel(private val c: AppContainer) : ViewModel() {
    private val _ui = MutableStateFlow(FilesUi())
    val ui = _ui.asStateFlow()
    val favourites = c.db.bookmarks().observe("FAVORITE")
    val recents = c.db.bookmarks().observe("RECENT", 15)
    val safRoots = c.db.bookmarks().observe("SAF_ROOT")

    val current: Entry? get() = _ui.value.stack.lastOrNull()

    fun open(e: Entry) {
        _ui.update { it.copy(stack = it.stack + e, selection = emptySet(), searchResults = null) }
        reload()
    }

    fun openPath(path: String) {
        val f = File(path)
        if (!f.isDirectory) return
        // Rebuild the stack from the volume root so "up" works naturally.
        val root = c.files.volumes().firstOrNull { path.startsWith(it.root.absolutePath) }?.root ?: c.files.primaryRoot()
        val chain = generateSequence(f) { it.parentFile }.takeWhile { it.absolutePath.length >= root.absolutePath.length }.toList().reversed()
        _ui.update { it.copy(stack = chain.map(Entry::of), selection = emptySet(), searchResults = null) }
        reload()
    }

    fun openTree(uri: Uri) {
        val e = c.files.openTree(uri) ?: return
        viewModelScope.launch { c.db.bookmarks().upsert(FileBookmark(uri.toString(), "SAF_ROOT", e.name, System.currentTimeMillis())) }
        _ui.update { it.copy(stack = listOf(e), selection = emptySet(), searchResults = null) }
        reload()
    }

    fun up(): Boolean {
        val s = _ui.value
        if (s.searchResults != null) { _ui.update { it.copy(searchResults = null) }; return true }
        if (s.selection.isNotEmpty()) { _ui.update { it.copy(selection = emptySet()) }; return true }
        if (s.stack.isEmpty()) return false
        _ui.update { it.copy(stack = it.stack.dropLast(1), selection = emptySet()) }
        reload()
        return true
    }

    fun home() = _ui.update { it.copy(stack = emptyList(), entries = emptyList(), selection = emptySet(), searchResults = null) }

    fun reload() {
        val dir = current ?: return
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val list = c.files.list(dir, _ui.value.showHidden)
            _ui.update { it.copy(entries = sortList(list, it.sort), loading = false) }
        }
    }

    private fun sortList(l: List<Entry>, s: FileSort): List<Entry> {
        val dirsFirst = compareByDescending<Entry> { it.isDir }
        return l.sortedWith(
            when (s) {
                FileSort.NAME -> dirsFirst.thenBy { it.name.lowercase() }
                FileSort.SIZE -> dirsFirst.thenByDescending { it.size }
                FileSort.DATE -> dirsFirst.thenByDescending { it.modified }
                FileSort.TYPE -> dirsFirst.thenBy { FileKinds.ext(it.name) }.thenBy { it.name.lowercase() }
            },
        )
    }

    fun setSort(s: FileSort) = _ui.update { it.copy(sort = s, entries = sortList(it.entries, s)) }
    fun toggleHidden() { _ui.update { it.copy(showHidden = !it.showHidden) }; reload() }
    fun toggleSelect(e: Entry) = _ui.update { it.copy(selection = if (e.id in it.selection) it.selection - e.id else it.selection + e.id) }
    fun selectAll() = _ui.update { it.copy(selection = it.entries.map { e -> e.id }.toSet()) }
    private fun selected(): List<Entry> = (_ui.value.searchResults ?: _ui.value.entries).filter { it.id in _ui.value.selection }

    fun clip(move: Boolean) = _ui.update { it.copy(clipboard = selected(), clipboardMove = move, selection = emptySet(), message = "${selected().size} item(s) ready to ${if (move) "move" else "copy"}") }

    private fun op(title: String, block: suspend ((OpProgress) -> Unit) -> String) {
        _ui.update { it.copy(busy = OpProgress(0, 0, ""), busyTitle = title) }
        viewModelScope.launch {
            val msg = runCatching { block { p -> _ui.update { it.copy(busy = p) } } }.getOrElse { "Failed: ${it.message}" }
            _ui.update { it.copy(busy = null, message = msg, selection = emptySet()) }
            reload()
        }
    }

    fun paste() {
        val dest = current ?: return
        val s = _ui.value
        op(if (s.clipboardMove) "Moving" else "Copying") { p ->
            val n = c.files.copy(s.clipboard, dest, s.clipboardMove, p)
            _ui.update { it.copy(clipboard = emptyList()) }
            "$n of ${s.clipboard.size} item(s) ${if (s.clipboardMove) "moved" else "copied"}"
        }
    }

    fun delete() {
        val items = selected()
        op("Deleting") { _ ->
            val n = c.files.delete(items, c.settings.current().useRecycleBin)
            "$n of ${items.size} item(s) deleted" + if (items.any { it.local != null } && c.settings.current().useRecycleBin) " (recycle bin)" else ""
        }
    }

    fun rename(e: Entry, name: String) = op("Renaming") { _ -> if (c.files.rename(e, name)) "Renamed" else "Rename failed" }
    fun mkdir(name: String) { val d = current ?: return; op("Creating folder") { _ -> if (c.files.mkdir(d, name)) "Folder created" else "Failed" } }
    fun zip(name: String) {
        val d = current ?: return
        val items = selected()
        op("Compressing") { p -> c.files.zip(items, d, name, p)?.let { "Created ${it.name}" } ?: "Failed" }
    }
    fun unzip(e: Entry) { val d = current ?: return; op("Extracting") { p -> "Extracted ${c.files.unzip(e, d, p)} file(s)" } }

    fun search(q: String) {
        val d = current ?: return
        if (q.isBlank()) { _ui.update { it.copy(searchResults = null) }; return }
        _ui.update { it.copy(loading = true) }
        viewModelScope.launch {
            val r = c.files.search(d, q)
            _ui.update { it.copy(searchResults = r, loading = false) }
        }
    }

    fun toggleFavourite(e: Entry, fav: Boolean) = viewModelScope.launch {
        if (fav) c.db.bookmarks().remove(e.id, "FAVORITE") else c.db.bookmarks().upsert(FileBookmark(e.id, "FAVORITE", e.name, System.currentTimeMillis()))
    }

    fun recordRecent(e: Entry) = viewModelScope.launch {
        c.db.bookmarks().upsert(FileBookmark(e.id, "RECENT", e.name, System.currentTimeMillis()))
        c.db.bookmarks().trim("RECENT", 30)
    }

    fun dismissMessage() = _ui.update { it.copy(message = null) }
    fun volumes(): List<Volume> = c.files.volumes()
    fun preview(e: Entry): String? = c.files.readTextPreview(e)
    fun uri(e: Entry) = c.files.uriFor(e)
    suspend fun stats(e: Entry) = c.files.folderStats(e)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(nav: Navigator) {
    val vm = pfViewModel { FilesViewModel(it) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val favs by vm.favourites.collectAsStateWithLifecycle(emptyList())
    val recents by vm.recents.collectAsStateWithLifecycle(emptyList())
    val safRoots by vm.safRoots.collectAsStateWithLifecycle(emptyList())
    val caps = rememberCaps()
    val ctx = LocalContext.current
    var dialog by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<Entry?>(null) }
    var searchText by remember { mutableStateOf("") }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::openTree) }

    BackHandler(enabled = ui.stack.isNotEmpty() || ui.selection.isNotEmpty() || ui.searchResults != null) { vm.up() }

    fun openEntry(e: Entry) {
        when {
            ui.selection.isNotEmpty() -> vm.toggleSelect(e)
            e.isDir -> vm.open(e)
            else -> { vm.recordRecent(e); preview = e }
        }
    }

    val title = ui.stack.lastOrNull()?.name ?: "Files"
    val crumb = ui.stack.joinToString(" / ") { it.name }.ifEmpty { "Storage locations" }
    PfScreen(title, crumb, onBack = if (ui.stack.isNotEmpty()) ({ vm.up() }) else null) {
        ui.message?.let { m -> item { NoticeCard(m, "", actionLabel = "OK", onAction = vm::dismissMessage) } }
        ui.busy?.let { b -> item { ProgressPanel(ui.busyTitle, b.current, if (b.total > 0) b.done.toFloat() / b.total else null) } }

        if (ui.stack.isEmpty()) {
            if (!caps.allFilesAccess) {
                item {
                    NoticeCard("All files access needed", "Grant access to browse shared storage, USB drives and SD cards. Or open a specific folder with the system picker below.",
                        actionLabel = "Grant", onAction = { ctx.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))) })
                }
            }
            item { SectionLabel("Volumes") }
            items(vm.volumes(), key = { it.root.absolutePath }) { v ->
                PfCard(Modifier.fillMaxWidth(), onClick = { if (caps.allFilesAccess) vm.open(Entry.of(v.root)) else ctx.toast("Grant All files access first") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (v.removable) (if (v.label.contains("USB", true)) Icons.Filled.Usb else Icons.Filled.SdStorage) else Icons.Filled.Folder, null, tint = Pf.colors.accent)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.label, style = MaterialTheme.typography.titleSmall)
                            Text("${Format.bytes(v.freeBytes)} free of ${Format.bytes(v.totalBytes)}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                            SegmentBar(listOf((v.totalBytes - v.freeBytes).toFloat() to Pf.colors.accent, v.freeBytes.toFloat() to Pf.colors.grid), height = 5.dp)
                        }
                    }
                }
            }
            item { SectionLabel("Other locations (system picker)") }
            items(safRoots, key = { it.location }) { b ->
                PfCard(Modifier.fillMaxWidth(), onClick = { vm.openTree(Uri.parse(b.location)) }) { Text(b.label, style = MaterialTheme.typography.titleSmall); Text("Storage Access Framework", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted) }
            }
            item { OutlinedButton(onClick = { treePicker.launch(null) }) { Text("Add location…") } }
            if (favs.isNotEmpty()) {
                item { SectionLabel("Favourites") }
                items(favs, key = { "f" + it.location }) { b -> BookmarkRow(b) { if (File(b.location).isDirectory) vm.openPath(b.location) else preview = Entry.of(File(b.location)) } }
            }
            if (recents.isNotEmpty()) {
                item { SectionLabel("Recent files") }
                items(recents, key = { "r" + it.location }) { b -> BookmarkRow(b) { File(b.location).takeIf { it.exists() }?.let { preview = Entry.of(it) } } }
            }
            return@PfScreen
        }

        // Toolbar
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(searchText, { searchText = it; if (it.isBlank()) vm.search("") }, Modifier.fillMaxWidth(), placeholder = { Text("Search in this folder") }, singleLine = true,
                    trailingIcon = { TextButton(onClick = { vm.search(searchText) }) { Text("Find") } })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FileSort.entries.forEach { s -> FilterChip(ui.sort == s, { vm.setSort(s) }, { Text(s.label) }) }
                    FilterChip(ui.showHidden, vm::toggleHidden, { Text("Hidden") })
                    OutlinedButton(onClick = { dialog = "mkdir" }) { Text("New folder") }
                    if (ui.clipboard.isNotEmpty()) OutlinedButton(onClick = vm::paste) { Text("Paste ${ui.clipboard.size}") }
                    TextButton(onClick = vm::home) { Text("Locations") }
                }
            }
        }
        if (ui.selection.isNotEmpty()) {
            item {
                PfCard(Modifier.fillMaxWidth(), highlight = true, contentPadding = 10.dp) {
                    Text("${ui.selection.size} selected", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { vm.clip(false) }) { Text("Copy") }
                        TextButton(onClick = { vm.clip(true) }) { Text("Move") }
                        TextButton(onClick = { dialog = "delete" }) { Text("Delete") }
                        if (ui.selection.size == 1) TextButton(onClick = { dialog = "rename" }) { Text("Rename") }
                        TextButton(onClick = { dialog = "zip" }) { Text("Compress") }
                        TextButton(onClick = {
                            val files = (ui.searchResults ?: ui.entries).filter { it.id in ui.selection && !it.isDir }
                            val uris = files.mapNotNull { vm.uri(it) }
                            if (uris.isNotEmpty()) {
                                val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(files.first().mime).putExtra(Intent.EXTRA_STREAM, uris.first())
                                else Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                                ctx.launch(Intent.createChooser(i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share"))
                            }
                        }) { Text("Share") }
                        TextButton(onClick = vm::selectAll) { Text("All") }
                    }
                }
            }
        }
        val list = ui.searchResults ?: ui.entries
        if (ui.loading) item { Text("Loading…", color = Pf.colors.muted) }
        if (!ui.loading && list.isEmpty()) item { Text(if (ui.searchResults != null) "No matches." else "Empty folder.", color = Pf.colors.muted) }
        items(list, key = { it.id }) { e ->
            val fav = favs.any { it.location == e.id }
            PfCard(
                Modifier.fillMaxWidth().combinedClickable(onClick = { openEntry(e) }, onLongClick = { vm.toggleSelect(e) }),
                contentPadding = 10.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (ui.selection.isNotEmpty()) Checkbox(e.id in ui.selection, { vm.toggleSelect(e) })
                    Icon(iconFor(e), null, tint = if (e.isDir) Pf.colors.accent else Pf.colors.muted, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (e.isDir) Format.date(e.modified) else "${Format.bytes(e.size)} · ${Format.date(e.modified)}", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                    }
                    if (e.local != null) IconButton(onClick = { vm.toggleFavourite(e, fav) }) { Icon(if (fav) Icons.Filled.Star else Icons.Filled.StarBorder, "Favourite", tint = if (fav) Pf.colors.warning else Pf.colors.muted) }
                }
            }
        }
    }

    val selected = (ui.searchResults ?: ui.entries).filter { it.id in ui.selection }
    when (dialog) {
        "mkdir" -> NameDialog("New folder", "") { n -> dialog = null; n?.let(vm::mkdir) }
        "rename" -> selected.firstOrNull()?.let { e -> NameDialog("Rename", e.name) { n -> dialog = null; n?.let { vm.rename(e, it) } } }
        "zip" -> NameDialog("Archive name", (selected.firstOrNull()?.name?.substringBeforeLast('.') ?: "archive") + ".zip") { n -> dialog = null; n?.let(vm::zip) }
        "delete" -> ConfirmDialog(
            "Delete ${selected.size} item(s)?",
            if (rememberSettings().useRecycleBin) "Local files go to the recycle bin. Items from picker locations are deleted permanently." else "Items will be permanently deleted.",
            "Delete", destructive = true, onConfirm = { dialog = null; vm.delete() }, onDismiss = { dialog = null },
        )
    }
    preview?.let { e -> PreviewDialog(e, vm, onDismiss = { preview = null }, onUnzip = { preview = null; vm.unzip(e) }) }
}

@Composable
private fun BookmarkRow(b: FileBookmark, onClick: () -> Unit) {
    PfCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 10.dp) {
        Text(b.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(b.location.removePrefix("/storage/emulated/0"), style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun iconFor(e: Entry): ImageVector = if (e.isDir) Icons.Filled.Folder else when (FileKinds.kindOf(e.name)) {
    FileKind.IMAGE -> Icons.Filled.Image
    FileKind.VIDEO -> Icons.Filled.Movie
    FileKind.AUDIO -> Icons.Filled.AudioFile
    FileKind.DOCUMENT -> Icons.Filled.Description
    FileKind.ARCHIVE -> Icons.Filled.Archive
    FileKind.APK -> Icons.Filled.Android
    FileKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
private fun NameDialog(title: String, initial: String, onResult: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onResult(null) },
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onResult(text.trim().takeIf { it.isNotEmpty() && !it.contains('/') }) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = { onResult(null) }) { Text("Cancel") } },
    )
}

@Composable
private fun PreviewDialog(e: Entry, vm: FilesViewModel, onDismiss: () -> Unit, onUnzip: () -> Unit) {
    val ctx = LocalContext.current
    val kind = FileKinds.kindOf(e.name)
    val bitmap by produceState<android.graphics.Bitmap?>(null, e.id) {
        if (kind == FileKind.IMAGE && e.local != null) value = withContext(Dispatchers.IO) {
            runCatching {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(e.local.absolutePath, o)
                var s = 1
                while (o.outWidth / s > 1600 || o.outHeight / s > 1600) s *= 2
                BitmapFactory.decodeFile(e.local.absolutePath, BitmapFactory.Options().apply { inSampleSize = s })
            }.getOrNull()
        }
    }
    val text by produceState<String?>(null, e.id) { if (FileKinds.isTextLike(e.name)) value = withContext(Dispatchers.IO) { vm.preview(e) } }
    var stats by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(e.id) { if (e.isDir) stats = vm.stats(e) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().padding(bottom = 8.dp)) }
                text?.let { Text(it, fontFamily = Mono, style = MaterialTheme.typography.bodySmall, maxLines = 60) }
                KeyValue("Size", Format.bytes(e.size))
                KeyValue("Modified", Format.dateTime(e.modified))
                KeyValue("Type", e.mime)
                e.local?.let { KeyValue("Path", it.absolutePath) }
                stats?.let { KeyValue("Contents", "${it.first} files · ${Format.bytes(it.second)}") }
            }
        },
        confirmButton = {
            Row {
                if (FileKinds.ext(e.name) == "zip") TextButton(onClick = onUnzip) { Text("Extract") }
                TextButton(onClick = {
                    vm.uri(e)?.let { u -> ctx.launch(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(u, e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Open with")) }
                }) { Text("Open with") }
                TextButton(onClick = {
                    vm.uri(e)?.let { u -> ctx.launch(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(e.mime).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share")) }
                }) { Text("Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
