package dev.pixelforge.core.device

import android.app.ActivityManager
import android.content.Context
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import dev.pixelforge.core.monitor.CpuInfoReader
import dev.pixelforge.core.util.Format
import dev.pixelforge.core.util.SysFs

enum class Support(val label: String) { SUPPORTED("Supported"), UNSUPPORTED("Not supported"), INACCESSIBLE("Not exposed to apps") }

data class InfoItem(val label: String, val value: String, val support: Support? = null)

data class InfoSection(val title: String, val items: List<InfoItem>)

/** Hardware & software inventory. Each capability is marked supported / unsupported / inaccessible. */
class DeviceInfoRepository(private val context: Context) {
    private val pm = context.packageManager

    private fun feature(name: String): Support = if (pm.hasSystemFeature(name)) Support.SUPPORTED else Support.UNSUPPORTED

    private fun featureVersion(name: String): Int? =
        pm.systemAvailableFeatures.firstOrNull { it.name == name }?.version

    fun sections(vulkanSummary: String?, glRenderer: String?): List<InfoSection> {
        val out = mutableListOf<InfoSection>()
        val topo = CpuInfoReader.topology()
        val isPixel9 = Build.MODEL.equals("Pixel 9", true) || Build.DEVICE.equals("tokay", true)

        out += InfoSection(
            "Device",
            listOf(
                InfoItem("Model", "${Build.MANUFACTURER} ${Build.MODEL}"),
                InfoItem("Codename", "${Build.DEVICE} / ${Build.PRODUCT}"),
                InfoItem("Pixel 9 detected", if (isPixel9) "Yes (tokay)" else "No — running on ${Build.MODEL}"),
                InfoItem("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}${if (Build.VERSION.SDK_INT >= 36) ", full ${Build.VERSION.SDK_INT_FULL}" else ""})"),
                InfoItem("Security patch", Build.VERSION.SECURITY_PATCH),
                InfoItem("Build", Build.DISPLAY),
                InfoItem("Kernel", System.getProperty("os.version") ?: "?"),
                InfoItem("Bootloader", Build.BOOTLOADER),
                InfoItem("Baseband", Build.getRadioVersion() ?: "—"),
                InfoItem("Uptime", Format.duration(SystemClock.elapsedRealtime())),
                InfoItem("Runtime", "ART ${System.getProperty("java.vm.version") ?: ""}"),
            ),
        )

        out += InfoSection(
            "Processor",
            buildList {
                add(InfoItem("SoC", "${topo.socManufacturer} ${topo.socModel}${if (topo.isTensorG4) " (Google Tensor G4)" else ""}"))
                add(InfoItem("Architecture", topo.abis.joinToString()))
                add(InfoItem("Cores", "${topo.coreCount} (${topo.clusters.joinToString(" + ") { "${it.cores.size}" }})"))
                topo.clusters.reversed().forEach { c ->
                    add(InfoItem(c.name, "cpu${c.cores.first()}–${c.cores.last()} · ${Format.mhz(c.minKhz)} – ${Format.mhz(c.maxKhz)}"))
                }
                add(InfoItem("Governor", topo.cores.mapNotNull { it.governor }.distinct().joinToString().ifEmpty { "Not readable" }))
                add(InfoItem("ISA features", topo.features.filter { it in setOf("asimd", "sve", "sve2", "i8mm", "bf16", "dotprod", "crc32", "aes", "sha2", "sha3", "atomics", "fphp", "mte") }.joinToString().ifEmpty { "—" }))
            },
        )

        val glVer = (context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion)
        val vkLevel = featureVersion(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL)
        val vkVersion = featureVersion(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)
        out += InfoSection(
            "Graphics",
            listOf(
                InfoItem("GPU", glRenderer ?: "Open the GPU lab to probe"),
                InfoItem("OpenGL ES", "${glVer shr 16}.${glVer and 0xFFFF}", Support.SUPPORTED),
                InfoItem("Vulkan", vkVersion?.let { "${it shr 22}.${(it shr 12) and 0x3FF}" + (vkLevel?.let { l -> " (hardware level $l)" } ?: "") } ?: "Not declared",
                    if (vkVersion != null) Support.SUPPORTED else Support.UNSUPPORTED),
                InfoItem("Vulkan dEQP level", featureVersion(PackageManager.FEATURE_VULKAN_DEQP_LEVEL)?.let { decodeDate(it) } ?: "—"),
                InfoItem("Vulkan driver", vulkanSummary ?: "Open the GPU lab to probe"),
                InfoItem("Android Dynamic Performance Framework", "PerformanceHintManager available", Support.SUPPORTED),
            ),
        )

        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stats = runCatching {
            context.getSystemService(android.app.usage.StorageStatsManager::class.java).getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT)
        }.getOrNull()
        val zram = SysFs.readLong("/sys/block/zram0/disksize")
        out += InfoSection(
            "Memory & storage",
            listOf(
                InfoItem("RAM (visible to Android)", Format.bytes(mi.totalMem)),
                InfoItem("zRAM swap", zram?.let { Format.bytes(it) } ?: "Not readable"),
                InfoItem("Internal storage", stats?.let { Format.bytes(it) } ?: "—"),
                InfoItem("Low-RAM device", if (am.isLowRamDevice) "Yes" else "No"),
                InfoItem("Memory class (per app)", "${am.memoryClass} MB / large ${am.largeMemoryClass} MB"),
            ),
        )

        val dm = context.getSystemService(DisplayManager::class.java)
        val displays = dm.displays
        out += InfoSection(
            "Displays",
            displays.flatMap { d -> displayItems(d) } + InfoItem(
                "External display (presentation)",
                if (dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty()) "Connected" else "None connected",
            ),
        )

        val wifi = context.getSystemService(WifiManager::class.java)
        out += InfoSection(
            "Connectivity",
            listOf(
                InfoItem("Wi-Fi 6E (6 GHz)", "", if (wifi.is6GHzBandSupported) Support.SUPPORTED else Support.UNSUPPORTED),
                InfoItem("Wi-Fi 7 (802.11be)", "", if (wifi.isWifiStandardSupported(android.net.wifi.ScanResult.WIFI_STANDARD_11BE)) Support.SUPPORTED else Support.UNSUPPORTED),
                InfoItem("Wi-Fi Aware", "", feature(PackageManager.FEATURE_WIFI_AWARE)),
                InfoItem("Wi-Fi RTT", "", feature(PackageManager.FEATURE_WIFI_RTT)),
                InfoItem("Bluetooth LE", "", feature(PackageManager.FEATURE_BLUETOOTH_LE)),
                InfoItem("NFC", "", feature(PackageManager.FEATURE_NFC)),
                InfoItem("UWB", "", feature(PackageManager.FEATURE_UWB)),
                InfoItem("Telephony", "", feature(PackageManager.FEATURE_TELEPHONY)),
                InfoItem("eSIM (eUICC)", "", feature(PackageManager.FEATURE_TELEPHONY_EUICC)),
                InfoItem("USB host (OTG)", "", feature(PackageManager.FEATURE_USB_HOST)),
                InfoItem("USB accessory", "", feature(PackageManager.FEATURE_USB_ACCESSORY)),
                InfoItem("USB devices attached", context.getSystemService(UsbManager::class.java).deviceList.size.toString()),
                InfoItem("Satellite SOS", "", Support.INACCESSIBLE),
            ),
        )

        out += InfoSection(
            "Platform features",
            listOf(
                InfoItem("Freeform windows / desktop", "", feature(PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT)),
                InfoItem("Picture-in-picture", "", feature(PackageManager.FEATURE_PICTURE_IN_PICTURE)),
                InfoItem("StrongBox Keystore", "", feature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)),
                InfoItem("Fingerprint", "", feature(PackageManager.FEATURE_FINGERPRINT)),
                InfoItem("Face unlock (biometric)", "", feature(PackageManager.FEATURE_FACE)),
                InfoItem("Camera (back / front)", "", feature(PackageManager.FEATURE_CAMERA_ANY)),
                InfoItem("HDR video capture / display", "", if (displays.firstOrNull()?.isHdr == true) Support.SUPPORTED else Support.UNSUPPORTED),
                InfoItem("Sustained performance mode", "", if (context.getSystemService(android.os.PowerManager::class.java).isSustainedPerformanceModeSupported) Support.SUPPORTED else Support.UNSUPPORTED),
                InfoItem("Thermal headroom API", "", Support.SUPPORTED),
                InfoItem("CPU frequency / governor control", "Requires root — not exposed", Support.INACCESSIBLE),
                InfoItem("GPU frequency control", "Requires root — not exposed", Support.INACCESSIBLE),
                InfoItem("Per-app battery (mAh)", "Needs Shizuku/ADB (dumpsys batterystats)", Support.INACCESSIBLE),
            ),
        )

        val sm = context.getSystemService(SensorManager::class.java)
        out += InfoSection(
            "Sensors (${sm.getSensorList(Sensor.TYPE_ALL).size})",
            sm.getSensorList(Sensor.TYPE_ALL).map { s ->
                InfoItem(s.name, "${s.vendor} · ${s.stringType.substringAfterLast('.')}${if (s.isWakeUpSensor) " · wake-up" else ""}")
            },
        )
        return out
    }

    private fun displayItems(d: Display): List<InfoItem> {
        val mode = d.mode
        val modes = d.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted()
        val hdr = d.hdrCapabilities?.supportedHdrTypes?.map {
            when (it) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> "Type $it" }
        }.orEmpty()
        val name = if (d.displayId == Display.DEFAULT_DISPLAY) "Built-in" else "Display ${d.displayId}"
        return listOf(
            InfoItem("$name resolution", "${mode.physicalWidth} × ${mode.physicalHeight}"),
            InfoItem("$name refresh modes", modes.joinToString(" / ") { "$it Hz" } + " (current ${d.refreshRate.toInt()} Hz)"),
            InfoItem("$name HDR", hdr.joinToString().ifEmpty { "None" }),
            InfoItem("$name wide colour gamut", if (d.isWideColorGamut) "Yes" else "No"),
        )
    }

    private fun decodeDate(v: Int): String {
        val y = v shr 16; val m = (v shr 8) and 0xFF; val d = v and 0xFF
        return if (y > 2000) "%04d-%02d-%02d".format(y, m, d) else v.toString()
    }

    @Suppress("unused")
    private fun FeatureInfo.describe() = "$name v$version"
}
