package dev.pixelforge.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.pixelforge.core.network.DataUsage
import dev.pixelforge.core.network.DnsResult
import dev.pixelforge.core.network.LatencyResult
import dev.pixelforge.core.network.NetworkStatus
import dev.pixelforge.core.network.SpeedResult
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.BigReadout
import dev.pixelforge.ui.components.ChartSeries
import dev.pixelforge.ui.components.ConfirmDialog
import dev.pixelforge.ui.components.KeyValue
import dev.pixelforge.ui.components.LineChart
import dev.pixelforge.ui.components.NoticeCard
import dev.pixelforge.ui.components.PfCard
import dev.pixelforge.ui.components.SectionLabel
import dev.pixelforge.ui.navigation.LocalContainer
import dev.pixelforge.ui.navigation.Navigator
import dev.pixelforge.ui.theme.Pf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NetworkScreen(nav: Navigator) {
    val c = LocalContainer.current
    val ctx = LocalContext.current
    val caps = rememberCaps()
    val settings = rememberSettings()
    val scope = rememberCoroutineScope()
    val status by produceState<NetworkStatus?>(null) {
        while (true) { value = withContext(Dispatchers.IO) { c.network.status() }; delay(3000) }
    }
    val rssi = remember { mutableStateListOf<Float>() }
    LaunchedEffect(status) { status?.wifi?.rssi?.let { rssi += it.toFloat(); if (rssi.size > 60) rssi.removeAt(0) } }
    var latency by remember { mutableStateOf<List<LatencyResult>?>(null) }
    var dns by remember { mutableStateOf<List<DnsResult>?>(null) }
    var speed by remember { mutableStateOf<SpeedResult?>(null) }
    var speedLive by remember { mutableStateOf<Pair<String, Double>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var askSpeed by remember { mutableStateOf(false) }
    val usage by produceState<DataUsage?>(null, caps.usageAccess) { if (caps.usageAccess) value = withContext(Dispatchers.IO) { c.network.dataUsage() } }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { c.capabilities.refresh() }

    fun runSpeed() {
        busy = "Speed test"
        scope.launch {
            speed = c.network.speedTest { phase, mbps -> speedLive = phase to mbps }
            speedLive = null; busy = null
        }
    }

    PfScreen("Network", "Connectivity, latency, DNS and opt-in throughput tests", onBack = nav::back) {
        val s = status ?: return@PfScreen
        item {
            PfCard(Modifier.fillMaxWidth()) {
                Text(if (s.connected) "${s.transport} · ${if (s.validated) "internet validated" else "no validated internet"}" else "Offline", style = MaterialTheme.typography.titleMedium)
                KeyValue("Metered", if (s.metered) "Yes" else "No")
                KeyValue("VPN", if (s.vpn) "Active" else "No")
                KeyValue("Bandwidth estimate", "${s.downKbps?.let { it / 1000 } ?: "?"} ↓ / ${s.upKbps?.let { it / 1000 } ?: "?"} ↑ Mbps (system estimate)")
                KeyValue("Interface", s.interfaceName ?: "—")
                KeyValue("Addresses", s.addresses.joinToString("\n").ifEmpty { "—" })
                KeyValue("DNS servers", s.dnsServers.joinToString("\n").ifEmpty { "—" })
                KeyValue("Private DNS", s.privateDns ?: "—")
            }
        }
        s.wifi?.let { w ->
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Wi-Fi", style = MaterialTheme.typography.titleSmall)
                    KeyValue("SSID", w.ssid ?: if (caps.fineLocation) "Hidden" else "Needs location permission")
                    KeyValue("Signal", "${w.rssi} dBm")
                    KeyValue("Link speed", "${w.linkMbps} Mbps (tx ${w.txMbps} / rx ${w.rxMbps})")
                    KeyValue("Band", "${w.frequencyMhz} MHz · ${if (w.frequencyMhz > 5900) "6 GHz" else if (w.frequencyMhz > 4900) "5 GHz" else "2.4 GHz"}")
                    KeyValue("Standard", w.standard)
                    if (rssi.size > 2) LineChart(listOf(ChartSeries("RSSI dBm", rssi.toList(), Pf.colors.cyan)), minValue = -95f, maxValue = -30f, height = 90.dp)
                    if (!caps.fineLocation) OutlinedButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)) }) { Text("Allow location for SSID") }
                }
            }
        }
        s.cellular?.let { cell ->
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Mobile", style = MaterialTheme.typography.titleSmall)
                    KeyValue("Operator", cell.operator ?: "—")
                    KeyValue("Technology", cell.networkType ?: if (caps.phoneState) "—" else "Needs phone permission")
                    KeyValue("Signal", listOfNotNull(cell.signalLevel?.let { "level $it/4" }, cell.signalDbm?.let { "$it dBm" }).joinToString(" · ").ifEmpty { "—" })
                    if (!caps.phoneState) OutlinedButton(onClick = { permLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) }) { Text("Allow phone state") }
                }
            }
        }
        item { SectionLabel("Diagnostics") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = busy == null, onClick = {
                    busy = "Latency"
                    scope.launch {
                        latency = listOfNotNull(
                            c.network.tcpLatency("1.1.1.1"), c.network.tcpLatency("8.8.8.8"), c.network.tcpLatency("www.google.com"),
                            c.network.icmpPing("1.1.1.1"),
                        )
                        busy = null
                    }
                }) { Text("Latency & loss") }
                OutlinedButton(enabled = busy == null, onClick = { busy = "DNS"; scope.launch { dns = c.network.dnsTest(); busy = null } }) { Text("DNS") }
                OutlinedButton(enabled = busy == null, onClick = { if (settings.speedTestConsent) runSpeed() else askSpeed = true }) { Text("Speed test") }
            }
        }
        busy?.let { b -> item { Text("Running $b…" + (speedLive?.let { " ${it.first} ${"%.1f".format(it.second)} Mbps" } ?: ""), color = Pf.colors.accent) } }
        latency?.let { l ->
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("Latency", style = MaterialTheme.typography.titleSmall)
                    l.forEach { r ->
                        KeyValue(r.target, r.avg?.let { "avg ${"%.1f".format(it)} ms · min ${"%.1f".format(r.min)} · max ${"%.1f".format(r.max)} · jitter ${"%.1f".format(r.jitter ?: 0.0)} · loss ${"%.0f".format(r.lossPct)}%" } ?: "unreachable")
                    }
                    Text("TCP connect time to port 443 (works without root); ICMP via the system ping tool when available. Loss is estimated from failed attempts.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                }
            }
        }
        dns?.let { d ->
            item {
                PfCard(Modifier.fillMaxWidth()) {
                    Text("DNS resolution", style = MaterialTheme.typography.titleSmall)
                    d.forEach { r -> KeyValue(r.host, "${"%.1f".format(r.ms ?: 0.0)} ms · ${r.error ?: r.addresses.take(2).joinToString()}") }
                    Text("First lookups may hit the resolver cache; the random .example.com name forces an uncached round trip.", style = MaterialTheme.typography.labelSmall, color = Pf.colors.muted)
                }
            }
        }
        speed?.let { r ->
            item {
                PfCard(Modifier.fillMaxWidth(), highlight = true) {
                    Text("Throughput (Cloudflare speed endpoints)", style = MaterialTheme.typography.titleSmall)
                    Row {
                        BigReadout(r.downloadMbps?.let { "%.0f".format(it) } ?: "—", "Mbps ↓", Modifier.weight(1f))
                        BigReadout(r.uploadMbps?.let { "%.0f".format(it) } ?: "—", "Mbps ↑", Modifier.weight(1f))
                    }
                    KeyValue("Data used", "${Format.bytes(r.bytesDown)} ↓ · ${Format.bytes(r.bytesUp)} ↑")
                    r.error?.let { KeyValue("Error", it, valueColor = Pf.colors.danger) }
                }
            }
        }
        item { SectionLabel("Data usage this month") }
        item {
            PfCard(Modifier.fillMaxWidth()) {
                val u = usage
                if (!caps.usageAccess) Text("Requires Usage Access.", color = Pf.colors.muted)
                else if (u == null) Text("Loading…", color = Pf.colors.muted)
                else {
                    KeyValue("Wi-Fi", Format.bytes(u.wifiBytes))
                    KeyValue("Mobile", Format.bytes(u.mobileBytes))
                    Spacer(Modifier.height(6.dp))
                    val pm = ctx.packageManager
                    u.perApp.take(12).forEach { (uid, bytes) ->
                        val name = pm.getPackagesForUid(uid)?.firstOrNull()?.let { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
                            ?: when (uid) { -5 -> "Tethering"; -4 -> "Removed apps"; 1000 -> "Android system"; 0 -> "Kernel/root"; else -> "UID $uid" }
                        KeyValue(name, Format.bytes(bytes))
                    }
                }
            }
        }
        item {
            NoticeCard("Radio performance", "Software cannot improve mobile radio performance; PixelForge only measures it. For better reception try Wi-Fi calling, a different band (via your carrier) or another location.", color = Pf.colors.muted)
        }
    }

    if (askSpeed) {
        ConfirmDialog(
            "Run a speed test?", "Downloads up to 25 MB and uploads up to 10 MB to speed.cloudflare.com. On mobile data this counts against your plan.",
            "Run", onConfirm = { askSpeed = false; scope.launch { c.settings.update { it.copy(speedTestConsent = true) } }; runSpeed() }, onDismiss = { askSpeed = false },
        )
    }
}
