package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf

@Composable
fun MoreScreen(nav: Navigator) {
    val caps = rememberCaps()
    PfScreen("Control centre", "Every PixelForge tool") {
        item { SectionLabel("Tools") }
        item {
            val items = Dest.moreItems
            TileGrid(items.size, minTileWidth = 160.dp) { i, m ->
                val d = items[i]
                PfCard(m, onClick = { nav.go(d) }, contentPadding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(d.icon, null, tint = Pf.colors.accent, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(d.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(d.description, style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item { SectionLabel("Access level") }
        item {
            PfCard {
                Text(
                    when {
                        caps.shizukuReady -> "Advanced: Shizuku connected (ADB shell privileges, not root)."
                        caps.writeSecureSettings || caps.dump -> "Extended: ADB-granted permissions active."
                        else -> "Standard: all core features run without root or ADB."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
