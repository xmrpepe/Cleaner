package dev.pixelforge.core.network

import android.annotation.SuppressLint
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class NetworkStatus(
    val connected: Boolean,
    val transport: String,
    val validated: Boolean,
    val metered: Boolean,
    val vpn: Boolean,
    val downKbps: Int?,
    val upKbps: Int?,
    val interfaceName: String?,
    val addresses: List<String>,
    val dnsServers: List<String>,
    val privateDns: String?,
    val wifi: WifiDetails?,
    val cellular: CellDetails?,
)

data class WifiDetails(val ssid: String?, val rssi: Int, val linkMbps: Int, val txMbps: Int, val rxMbps: Int, val frequencyMhz: Int, val standard: String, val bssidHidden: Boolean)

data class CellDetails(val operator: String?, val networkType: String?, val signalLevel: Int?, val signalDbm: Int?)

data class LatencyResult(val target: String, val samplesMs: List<Double>, val lost: Int) {
    val sent: Int get() = samplesMs.size + lost
    val avg: Double? get() = samplesMs.takeIf { it.isNotEmpty() }?.average()
    val min: Double? get() = samplesMs.minOrNull()
    val max: Double? get() = samplesMs.maxOrNull()
    val jitter: Double?
        get() = if (samplesMs.size < 2) null else samplesMs.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
    val lossPct: Double get() = if (sent == 0) 0.0 else lost * 100.0 / sent
}

data class DnsResult(val host: String, val ms: Double?, val addresses: List<String>, val error: String?)

data class SpeedResult(val downloadMbps: Double?, val uploadMbps: Double?, val bytesDown: Long, val bytesUp: Long, val error: String?)

data class InterfaceInfo(val name: String, val up: Boolean, val mtu: Int, val addresses: List<String>)

data class DataUsage(val wifiBytes: Long, val mobileBytes: Long, val perApp: List<Pair<Int, Long>>)

class NetworkDiagnostics(private val context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    @SuppressLint("MissingPermission")
    fun status(): NetworkStatus {
        val net = cm.activeNetwork
        val caps = net?.let { cm.getNetworkCapabilities(it) }
        val lp: LinkProperties? = net?.let { cm.getLinkProperties(it) }
        val transport = when {
            caps == null -> "None"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_USB) -> "USB"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "Other"
        }
        val wifi = (caps?.transportInfo as? WifiInfo)?.let { wi ->
            WifiDetails(
                ssid = wi.ssid?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID.removeSurrounding("\"") || it == "<unknown ssid>" },
                rssi = wi.rssi,
                linkMbps = wi.linkSpeed,
                txMbps = wi.txLinkSpeedMbps,
                rxMbps = wi.rxLinkSpeedMbps,
                frequencyMhz = wi.frequency,
                standard = wifiStandard(wi.wifiStandard),
                bssidHidden = wi.bssid == null || wi.bssid == "02:00:00:00:00:00",
            )
        }
        val cell = if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) cellDetails() else null
        return NetworkStatus(
            connected = caps != null,
            transport = transport,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            metered = cm.isActiveNetworkMetered,
            vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
            downKbps = caps?.linkDownstreamBandwidthKbps,
            upKbps = caps?.linkUpstreamBandwidthKbps,
            interfaceName = lp?.interfaceName,
            addresses = lp?.linkAddresses?.map { it.toString() } ?: emptyList(),
            dnsServers = lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList(),
            privateDns = lp?.let { if (it.isPrivateDnsActive) (it.privateDnsServerName ?: "Automatic") else "Off" },
            wifi = wifi,
            cellular = cell,
        )
    }

    @SuppressLint("MissingPermission")
    private fun cellDetails(): CellDetails? = try {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val ss = tm.signalStrength
        val type = try { networkTypeName(tm.dataNetworkType) } catch (_: SecurityException) { null }
        CellDetails(
            operator = tm.networkOperatorName,
            networkType = type,
            signalLevel = ss?.level,
            signalDbm = ss?.cellSignalStrengths?.firstOrNull()?.dbm?.takeIf { it != Int.MAX_VALUE },
        )
    } catch (_: Throwable) {
        null
    }

    fun interfaces(): List<InterfaceInfo> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
            InterfaceInfo(ni.name, ni.isUp, runCatching { ni.mtu }.getOrDefault(-1), ni.inetAddresses.toList().mapNotNull { it.hostAddress })
        }.sortedByDescending { it.up }
    } catch (_: Throwable) {
        emptyList()
    }

    /** TCP connect latency (works without ICMP privileges). Each failed connect counts as loss. */
    suspend fun tcpLatency(host: String, port: Int = 443, count: Int = 10, timeoutMs: Int = 2000): LatencyResult = withContext(Dispatchers.IO) {
        val samples = ArrayList<Double>()
        var lost = 0
        val addr = try { InetAddress.getByName(host) } catch (_: Throwable) { null }
        repeat(count) {
            coroutineContext.ensureActive()
            if (addr == null) { lost++; return@repeat }
            val t0 = System.nanoTime()
            try {
                Socket().use { s -> s.connect(InetSocketAddress(addr, port), timeoutMs) }
                samples += (System.nanoTime() - t0) / 1e6
            } catch (_: Throwable) {
                lost++
            }
            Thread.sleep(150)
        }
        LatencyResult("$host:$port", samples, lost)
    }

    /** ICMP ping using the platform `ping` binary (available to apps); null if unavailable. */
    suspend fun icmpPing(host: String, count: Int = 20): LatencyResult? = withContext(Dispatchers.IO) {
        if (!host.matches(Regex("^[A-Za-z0-9.:-]+$"))) return@withContext null
        val r = dev.pixelforge.core.util.LocalShell.run("/system/bin/ping", "-c", count.toString(), "-i", "0.2", "-W", "2", host, timeoutMs = (count * 2500L) + 3000)
        if (!r.output.contains("bytes from") && !r.output.contains("packet loss")) return@withContext null
        val times = Regex("time=([0-9.]+) ms").findAll(r.output).mapNotNull { it.groupValues[1].toDoubleOrNull() }.toList()
        val transmitted = Regex("(\\d+) packets transmitted").find(r.output)?.groupValues?.get(1)?.toIntOrNull() ?: count
        LatencyResult("ICMP $host", times, (transmitted - times.size).coerceAtLeast(0))
    }

    /** Measures uncached resolution by querying a random sub-domain plus cached lookups. */
    suspend fun dnsTest(hosts: List<String> = listOf("google.com", "cloudflare.com", "wikipedia.org", "github.com")): List<DnsResult> = withContext(Dispatchers.IO) {
        val targets = hosts + "pf-${UUID.randomUUID().toString().take(8)}.example.com"
        targets.map { h ->
            val t0 = System.nanoTime()
            try {
                val addrs = InetAddress.getAllByName(h).mapNotNull { it.hostAddress }
                DnsResult(h, (System.nanoTime() - t0) / 1e6, addrs, null)
            } catch (t: Throwable) {
                DnsResult(h, (System.nanoTime() - t0) / 1e6, emptyList(), if (h.startsWith("pf-")) "NXDOMAIN (expected — measures uncached resolver round trip)" else t.javaClass.simpleName)
            }
        }
    }

    /**
     * Opt-in speed test against Cloudflare's public speed endpoints. Uses real data.
     */
    suspend fun speedTest(downloadBytes: Long = 25_000_000, uploadBytes: Long = 10_000_000, onProgress: (String, Double) -> Unit): SpeedResult = withContext(Dispatchers.IO) {
        var down: Double? = null
        var up: Double? = null
        var bytesDown = 0L
        var bytesUp = 0L
        var error: String? = null
        try {
            val conn = URL("https://speed.cloudflare.com/__down?bytes=$downloadBytes").openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            val t0 = System.nanoTime()
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buf)
                    if (n <= 0) break
                    bytesDown += n
                    val secs = (System.nanoTime() - t0) / 1e9
                    if (secs > 0.2) onProgress("download", bytesDown * 8 / secs / 1e6)
                    if (secs > 15) break
                }
            }
            down = bytesDown * 8 / ((System.nanoTime() - t0) / 1e9) / 1e6
            conn.disconnect()

            val upConn = URL("https://speed.cloudflare.com/__up").openConnection() as HttpURLConnection
            upConn.requestMethod = "POST"
            upConn.doOutput = true
            upConn.connectTimeout = 8000
            upConn.readTimeout = 20000
            upConn.setFixedLengthStreamingMode(uploadBytes)
            upConn.setRequestProperty("Content-Type", "application/octet-stream")
            val chunk = ByteArray(64 * 1024) { (it * 31).toByte() }
            val u0 = System.nanoTime()
            upConn.outputStream.use { out ->
                while (bytesUp < uploadBytes) {
                    coroutineContext.ensureActive()
                    val n = minOf(chunk.size.toLong(), uploadBytes - bytesUp).toInt()
                    out.write(chunk, 0, n)
                    bytesUp += n
                    val secs = (System.nanoTime() - u0) / 1e9
                    if (secs > 0.2) onProgress("upload", bytesUp * 8 / secs / 1e6)
                }
            }
            upConn.responseCode
            up = bytesUp * 8 / ((System.nanoTime() - u0) / 1e9) / 1e6
            upConn.disconnect()
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
        }
        SpeedResult(down, up, bytesDown, bytesUp, error)
    }

    /** Device-wide and per-UID data usage for the current month (needs Usage Access). */
    fun dataUsage(): DataUsage? = try {
        val nsm = context.getSystemService(NetworkStatsManager::class.java)
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0)
        }
        val start = cal.timeInMillis
        fun device(type: Int): Long = runCatching {
            nsm.querySummaryForDevice(type, null, start, now).let { it.rxBytes + it.txBytes }
        }.getOrDefault(0L)
        val perUid = HashMap<Int, Long>()
        listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE).forEach { type ->
            runCatching {
                nsm.querySummary(type, null, start, now).use { stats ->
                    val b = NetworkStats.Bucket()
                    while (stats.hasNextBucket()) {
                        stats.getNextBucket(b)
                        perUid[b.uid] = (perUid[b.uid] ?: 0L) + b.rxBytes + b.txBytes
                    }
                }
            }
        }
        DataUsage(device(ConnectivityManager.TYPE_WIFI), device(ConnectivityManager.TYPE_MOBILE), perUid.entries.sortedByDescending { it.value }.take(30).map { it.key to it.value })
    } catch (_: Throwable) {
        null
    }

    companion object {
        fun wifiStandard(s: Int): String = when (s) {
            1 -> "Legacy (a/b/g)"; 4 -> "Wi-Fi 4 (802.11n)"; 5 -> "Wi-Fi 5 (802.11ac)"
            6 -> "Wi-Fi 6/6E (802.11ax)"; 7 -> "802.11ad"; 8 -> "Wi-Fi 7 (802.11be)"
            else -> "Unknown"
        }

        fun networkTypeName(t: Int): String = when (t) {
            TelephonyManager.NETWORK_TYPE_NR -> "5G NR"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_IWLAN -> "IWLAN"
            TelephonyManager.NETWORK_TYPE_UNKNOWN -> "Unknown"
            else -> "Type $t"
        }
    }
}
