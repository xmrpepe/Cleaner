package dev.pixelforge.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.apps.AppEntry
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class AppSort(val label: String) { NAME("Name"), SIZE("Size"), RECENT("Last used"), INSTALLED("Installed"), UPDATED("Updated"), PERMS("Permissions") }
enum class AppFilter(val label: String) { USER("User"), SYSTEM("System"), ALL("All"), DISABLED("Disabled"), GAMES("Games"), UNUSED("Unused 30d+") }

@Composable
fun AppsScreen(nav: Navigator) {
    val c = LocalContainer.current
    val apps by c.apps.apps.collectAsStateWithLifecycle()
    val loading by c.apps.loading.collectAsStateWithLifecycle()
    val caps = rememberCaps()
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(AppSort.NAME) }
    var filter by rememberSaveable { mutableStateOf(AppFilter.USER) }
    LaunchedEffect(Unit) { c.apps.refresh() }

    val now = System.currentTimeMillis()
    val shown = remember(apps, query, sort, filter) {
        apps.asSequence()
            .filter {
                when (filter) {
                    AppFilter.USER -> !it.isSystem || it.isUpdatedSystem
                    AppFilter.SYSTEM -> it.isSystem
                    AppFilter.ALL -> true
                    AppFilter.DISABLED -> !it.enabled
                    AppFilter.GAMES -> it.isGame
                    AppFilter.UNUSED -> !it.isSystem && (it.lastUsed ?: 0) < now - 30L * 86_400_000L
                }
            }
            .filter { query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true) }
            .let { s ->
                when (sort) {
                    AppSort.NAME -> s.sortedBy { it.label.lowercase() }
                    AppSort.SIZE -> s.sortedByDescending { it.sizes?.total ?: -1 }
                    AppSort.RECENT -> s.sortedByDescending { it.lastUsed ?: 0 }
                    AppSort.INSTALLED -> s.sortedByDescending { it.firstInstall }
                    AppSort.UPDATED -> s.sortedByDescending { it.lastUpdate }
                    AppSort.PERMS -> s.sortedByDescending { it.grantedDangerous.size }
                }
            }.toList()
    }

    PfScreen("Apps", if (loading) "Loading…" else "${shown.size} of ${apps.size} packages", onBack = nav::back, actions = {
        OutlinedButton(onClick = { ctx.launch(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)) }) { Text("System list") }
    }) {
        if (!caps.usageAccess) {
            item {
                NoticeCard(
                    "Usage Access unlocks sizes & usage", "Grant Usage Access to see app/data/cache sizes and when each app was last used. Data stays on the device.",
                    actionLabel = "Grant", onAction = { ctx.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
                )
            }
        }
        item {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search name or package") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true)
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppFilter.entries.forEach { f -> FilterChip(filter == f, { filter = f }, { Text(f.label) }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AppSort.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text("↓ ${s.label}") }) }
                }
            }
        }
        items(shown, key = { it.packageName }) { a -> AppRow(a, sort) { nav.app(a.packageName) } }
    }
}

@Composable
fun AppIcon(pkg: String, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val c = LocalContainer.current
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState<android.graphics.Bitmap?>(null, pkg) { value = withContext(Dispatchers.IO) { c.apps.icon(pkg, px) } }
    Box(Modifier.size(size).clip(RoundedCornerShape(10.dp))) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(size)) }
    }
}

@Composable
private fun AppRow(a: AppEntry, sort: AppSort, onClick: () -> Unit) {
    PfCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(a.packageName)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when (sort) {
                        AppSort.SIZE -> a.sizes?.let { "${Format.bytes(it.total)} · cache ${Format.bytes(it.cacheBytes)}" } ?: a.packageName
                        AppSort.RECENT -> "Used ${Format.ago(a.lastUsed ?: 0)}"
                        AppSort.INSTALLED -> "Installed ${Format.date(a.firstInstall)}"
                        AppSort.UPDATED -> "Updated ${Format.date(a.lastUpdate)}"
                        AppSort.PERMS -> "${a.grantedDangerous.size} sensitive permission(s) granted"
                        AppSort.NAME -> "${a.versionName ?: ""} · ${a.packageName}"
                    },
                    style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (!a.enabled) Pill("Disabled", Pf.colors.warning)
            else if (a.batteryUnrestricted && !a.isSystem) Pill("Unrestricted", Pf.colors.cyan)
        }
    }
    Spacer(Modifier.height(0.dp))
}
