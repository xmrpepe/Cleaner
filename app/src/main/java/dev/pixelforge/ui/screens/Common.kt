package dev.pixelforge.ui.screens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pixelforge.core.monitor.SystemSnapshot
import dev.pixelforge.core.settings.AppSettings
import dev.pixelforge.core.shizuku.CapabilityState
import dev.pixelforge.ui.components.ScreenHeader
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.theme.Pf

/** Standard scrolling screen: header + lazy content constrained to a readable width. */
@Composable
fun PfScreen(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    state: LazyListState = rememberLazyListState(),
    maxWidth: Dp = 1280.dp,
    content: LazyListScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = maxWidth).fillMaxSize()) {
            ScreenHeader(title, subtitle, onBack, actions)
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = Pf.dims.pad, end = Pf.dims.pad, top = 4.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(Pf.dims.gap),
                content = content,
            )
        }
    }
}

/** Responsive grid for tiles: picks the column count from available width. */
@Composable
fun TileGrid(count: Int, minTileWidth: Dp = 160.dp, content: @Composable (index: Int, modifier: Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = Pf.dims.gap
        val cols = ((maxWidth + gap) / (minTileWidth + gap)).toInt().coerceIn(1, 6)
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            for (rowStart in 0 until count step cols) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth()) {
                    for (c in 0 until cols) {
                        val i = rowStart + c
                        if (i < count) content(i, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun rememberSnapshot(): SystemSnapshot? {
    val c = LocalContainer.current
    val s by c.monitor.snapshots.collectAsStateWithLifecycle()
    return s
}

@Composable
fun rememberHistory(): List<SystemSnapshot> {
    val c = LocalContainer.current
    val h by c.monitor.history.collectAsStateWithLifecycle()
    return h
}

@Composable
fun rememberCaps(): CapabilityState {
    val c = LocalContainer.current
    val s by c.capabilities.state.collectAsStateWithLifecycle()
    return s
}

@Composable
fun rememberSettings(): AppSettings {
    val c = LocalContainer.current
    val s by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    return s
}

fun Context.launch(intent: Intent, fallback: Intent? = null) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        if (fallback != null) launch(fallback) else Toast.makeText(this, "Not available on this device", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        if (fallback != null) launch(fallback) else Toast.makeText(this, "Not permitted on this device", Toast.LENGTH_SHORT).show()
    }
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
