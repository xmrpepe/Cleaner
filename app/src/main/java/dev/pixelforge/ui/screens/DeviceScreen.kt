package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pixelforge.core.device.InfoSection
import dev.pixelforge.core.device.Support
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DeviceScreen(nav: Navigator) {
    val c = LocalContainer.current
    val sections by produceState<List<InfoSection>?>(null) {
        val (vk, gl) = GpuProbeCache.load()
        value = withContext(Dispatchers.Default) {
            c.deviceInfo.sections(
                vk.takeIf { it.optBoolean("available") }?.let { "${it.optString("driverName")} ${it.optString("driverInfo")} · Vulkan ${it.optString("apiVersion")}".trim() },
                gl?.renderer,
            )
        }
    }
    PfScreen("Device diagnostics", "Hardware & capability inventory", onBack = nav::back) {
        val s = sections
        if (s == null) {
            item { Text("Collecting…", color = Pf.colors.muted) }
            return@PfScreen
        }
        s.forEach { sec ->
            item { SectionLabel(sec.title) }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    sec.items.forEach { i ->
                        if (i.support != null) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(i.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                if (i.value.isNotEmpty()) Text(i.value + "  ", style = MaterialTheme.typography.bodySmall, color = Pf.colors.muted)
                                Pill(
                                    i.support.label,
                                    when (i.support) { Support.SUPPORTED -> Pf.colors.success; Support.UNSUPPORTED -> Pf.colors.muted; Support.INACCESSIBLE -> Pf.colors.warning },
                                )
                            }
                        } else {
                            KeyValue(i.label, i.value)
                        }
                    }
                }
            }
        }
    }
}
