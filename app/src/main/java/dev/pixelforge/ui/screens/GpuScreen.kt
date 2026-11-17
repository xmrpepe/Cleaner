package dev.pixelforge.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.pixelforge.core.gpu.GlesInfo
import dev.pixelforge.core.gpu.GlesSession
import dev.pixelforge.core.nativebridge.NativeLab
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.MetricTile
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.Pill
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.navigation.Dest
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

object GpuProbeCache {
    @Volatile var vulkan: JSONObject? = null
    @Volatile var gles: GlesInfo? = null
    private val glDispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "pf-gl-probe") }.asCoroutineDispatcher()

    suspend fun load(): Pair<JSONObject, GlesInfo?> {
        val vk = vulkan ?: withContext(Dispatchers.Default) { NativeLab.vulkanProbe() }.also { vulkan = it }
        val gl = gles ?: withContext(glDispatcher) { GlesSession.create()?.use { it.info() } }.also { gles = it }
        return vk to gl
    }
}

@Composable
fun GpuScreen(nav: Navigator) {
    val snap = rememberSnapshot()
    val data by produceState<Pair<JSONObject, GlesInfo?>?>(null) { value = GpuProbeCache.load() }
    var showAllExt by remember { mutableStateOf(false) }

    PfScreen("GPU & Vulkan", "Capabilities reported by the installed driver", onBack = nav::back) {
        val d = data
        if (d == null) {
            item { PfCard(Modifier.fillMaxWidth()) { Text("Probing Vulkan and OpenGL ES…", color = Pf.colors.muted) } }
            return@PfScreen
        }
        val (vk, gl) = d
        item {
            TileGrid(3, 160.dp) { i, m ->
                when (i) {
                    0 -> MetricTile("GPU", vk.optString("deviceName", gl?.renderer ?: "Unknown"), m, sub = gl?.vendor)
                    1 -> MetricTile("Clock", snap?.gpu?.freqHz?.let { Format.mhz(it / 1000) } ?: "n/a", m, sub = snap?.gpu?.source ?: "Mali sysfs not readable without privileges")
                    else -> MetricTile("Utilisation", snap?.gpu?.utilizationPct?.let { "$it%" } ?: "n/a", m, sub = "Live, when exposed")
                }
            }
        }
        item {
            NoticeCard(
                "Benchmark the GPU", "Run Vulkan and OpenGL ES offscreen benchmarks, frame pacing and sustained tests in the Lab.",
                actionLabel = "Open Lab", onAction = { nav.go(Dest.LAB) },
            )
        }
        item { SectionLabel("Vulkan") }
        if (!vk.optBoolean("available")) {
            item { NoticeCard("Vulkan unavailable", vk.optString("error"), color = Pf.colors.warning) }
        } else {
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    KeyValue("API version", vk.optString("apiVersion"))
                    KeyValue("Instance API", vk.optString("instanceApi"))
                    KeyValue("Driver", listOf(vk.optString("driverName"), vk.optString("driverInfo")).filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "Raw ${vk.optLong("driverVersionRaw")}" })
                    KeyValue("Conformance", vk.optString("conformance", "—"))
                    KeyValue("Device type", vk.optString("deviceType"))
                    KeyValue("Vendor / device ID", "0x%04X / 0x%08X".format(vk.optInt("vendorId"), vk.optLong("deviceId")))
                    KeyValue("Subgroup size", vk.optInt("subgroupSize").takeIf { it > 0 }?.toString() ?: "—")
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Key limits", style = MaterialTheme.typography.titleSmall)
                    vk.optJSONObject("limits")?.let { l -> l.keys().forEach { k -> KeyValue(k, l.get(k).toString()) } }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Features", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    vk.optJSONObject("features")?.let { f ->
                        f.keys().asSequence().toList().chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                pair.forEach { k ->
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Pill(if (f.optBoolean(k)) "YES" else "NO", if (f.optBoolean(k)) Pf.colors.success else Pf.colors.muted)
                                        Spacer(Modifier.width(6.dp))
                                        Text(k, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Texture & attachment formats (optimal tiling)", style = MaterialTheme.typography.titleSmall)
                    val fm = vk.optJSONArray("formats")
                    if (fm != null) for (i in 0 until fm.length()) {
                        val o = fm.getJSONObject(i)
                        val caps = listOf("sampled", "colorAttachment", "blend", "storage", "depthStencil", "linearFilter").filter { o.optBoolean(it) }
                        KeyValue(o.optString("format"), caps.joinToString().ifEmpty { "unsupported" }, valueColor = if (caps.isEmpty()) Pf.colors.muted else androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Memory heaps", style = MaterialTheme.typography.titleSmall)
                    val heaps = vk.optJSONArray("memoryHeaps")
                    if (heaps != null) for (i in 0 until heaps.length()) {
                        val h = heaps.getJSONObject(i)
                        KeyValue("Heap $i${if (h.optBoolean("deviceLocal")) " (device-local)" else ""}", Format.bytes(h.optLong("sizeBytes")))
                    }
                    Text("Mobile GPUs share LPDDR with the CPU (unified memory); heaps describe allocation limits, not dedicated VRAM.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                }
            }
            item {
                val exts = vk.optJSONArray("deviceExtensions")
                val list = exts?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
                PfCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Device extensions (${list.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { showAllExt = !showAllExt }) { Text(if (showAllExt) "Collapse" else "Show all") }
                    }
                    (if (showAllExt) list else list.take(12)).forEach { Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = dev.pixelforge.ui.theme.Mono) }
                }
            }
        }
        item { SectionLabel("OpenGL ES / EGL") }
        if (gl == null) {
            item { NoticeCard("OpenGL ES 3 context unavailable", "Could not create an ES 3 context.", color = Pf.colors.warning) }
        } else {
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    KeyValue("Renderer", gl.renderer)
                    KeyValue("Vendor", gl.vendor)
                    KeyValue("Version", gl.version)
                    KeyValue("GLSL", gl.glslVersion)
                    KeyValue("EGL", "${gl.eglVersion} · ${gl.eglVendor}")
                    gl.limits.forEach { (k, v) -> KeyValue(k.removePrefix("GL_"), v.toString()) }
                    KeyValue("Extensions", "${gl.extensions.size} GL · ${gl.eglExtensions.size} EGL")
                    val notable = listOf("GL_KHR_texture_compression_astc_hdr", "GL_EXT_texture_compression_astc_decode_mode", "GL_EXT_shader_framebuffer_fetch", "GL_OES_EGL_image_external_essl3", "GL_EXT_YUV_target", "GL_EXT_disjoint_timer_query", "GL_EXT_protected_textures")
                    Spacer(Modifier.height(6.dp))
                    notable.forEach { n ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 1.dp)) {
                            Pill(if (n in gl.extensions) "YES" else "NO", if (n in gl.extensions) Pf.colors.success else Pf.colors.muted)
                            Spacer(Modifier.width(6.dp))
                            Text(n, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        item {
            NoticeCard(
                "What PixelForge cannot do", "Ordinary apps cannot overclock the GPU, change its governor or replace the system graphics driver. Android's GPU driver updates arrive through system updates.",
                color = Pf.colors.muted,
            )
        }
    }
}
