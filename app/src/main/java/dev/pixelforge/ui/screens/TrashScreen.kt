package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.launch

@Composable
fun TrashScreen(nav: Navigator) {
    val c = LocalContainer.current
    val entries by c.db.trash().observeAll().collectAsStateWithLifecycle(emptyList())
    val total by c.db.trash().observeTotalBytes().collectAsStateWithLifecycle(0L)
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()
    var confirmEmpty by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }

    PfScreen("Recycle bin", "${entries.size} item(s) · ${Format.bytes(total)} · kept ${settings.recycleRetentionDays} days", onBack = nav::back, actions = {
        TextButton(onClick = { confirmEmpty = true }, enabled = entries.isNotEmpty()) { Text("Empty") }
    }) {
        msg?.let { item { NoticeCard(it, "") } }
        if (entries.isEmpty()) item { Text("Nothing here. Files you clean with the recycle bin enabled can be restored from this screen.", color = Pf.colors.muted) }
        items(entries, key = { it.id }) { e ->
            PfCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(e.originalPath.substringAfterLast('/'), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${if (e.isDirectory && e.trashPath.isEmpty()) "Empty folder" else Format.bytes(e.sizeBytes)} · deleted ${Format.ago(e.deletedAt)} · ${e.originalPath.substringBeforeLast('/').removePrefix("/storage/emulated/0")}",
                            style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { scope.launch { msg = if (c.trash.restore(e)) "Restored ${e.originalPath.substringAfterLast('/')}" else "Restore failed" } }) { Text("Restore") }
                }
            }
        }
    }
    if (confirmEmpty) {
        ConfirmDialog("Empty recycle bin?", "${entries.size} item(s), ${Format.bytes(total)} will be permanently deleted.", "Delete permanently", destructive = true,
            onConfirm = { confirmEmpty = false; scope.launch { msg = "Freed ${Format.bytes(c.trash.purge(c.db.trash().all()))}" } }, onDismiss = { confirmEmpty = false })
    }
}
