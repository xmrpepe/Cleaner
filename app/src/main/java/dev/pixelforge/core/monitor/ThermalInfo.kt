package dev.pixelforge.core.monitor

import android.content.Context
import android.os.PowerManager

data class SensorTemperature(val name: String, val type: Int, val celsius: Float) {
    val typeLabel: String
        get() = when (type) {
            0 -> "CPU"; 1 -> "GPU"; 2 -> "Battery"; 3 -> "Skin"; 4 -> "USB port"; 5 -> "Power amp"
            6 -> "BCL voltage"; 7 -> "BCL current"; 8 -> "BCL %"; 9 -> "NPU"; 10 -> "TPU"
            11 -> "Display"; 12 -> "Modem"; 13 -> "SoC"; 14 -> "WiFi"; 15 -> "Camera"
            16 -> "Flashlight"; 17 -> "Speaker"; 18 -> "Ambient"; 19 -> "POGO"
            else -> "Type $type"
        }
}

data class ThermalSample(
    val status: Int,
    /** Forecast headroom: 1.0 == severe throttling threshold. Null when unsupported/rate-limited. */
    val headroom: Float?,
    val headroomForecast10s: Float?,
    /** Per-sensor temperatures from the thermal HAL; only available via Shizuku/DUMP. */
    val sensors: List<SensorTemperature>,
) {
    val statusLabel: String get() = statusLabel(status)

    companion object {
        fun statusLabel(status: Int): String = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Light"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutdown"
            else -> "Unknown"
        }
    }
}

class ThermalReader(context: Context) {
    private val pm = context.getSystemService(PowerManager::class.java)
    private var lastHeadroomAt = 0L
    private var lastHeadroom: Float? = null
    private var lastForecast: Float? = null

    fun sample(sensors: List<SensorTemperature> = emptyList()): ThermalSample {
        // getThermalHeadroom is rate-limited by the platform (~1 call/s); cache for 2 s.
        val now = System.currentTimeMillis()
        if (now - lastHeadroomAt >= 2000) {
            lastHeadroom = pm.getThermalHeadroom(0).takeUnless { it.isNaN() }
            lastForecast = pm.getThermalHeadroom(10).takeUnless { it.isNaN() }
            lastHeadroomAt = now
        }
        return ThermalSample(pm.currentThermalStatus, lastHeadroom, lastForecast, sensors)
    }

    companion object {
        private val tempRegex = Regex("""Temperature\{mValue=([-0-9.]+), mType=(-?\d+), mName=([^,]+), mStatus=(-?\d+)\}""")

        /** Parses the "Current temperatures from HAL" block of `dumpsys thermalservice`. */
        fun parseThermalService(dump: String): List<SensorTemperature> {
            val section = dump.substringAfter("Current temperatures from HAL:", "")
                .substringBefore("Current cooling devices", "")
                .ifEmpty { dump }
            return tempRegex.findAll(section).mapNotNull { m ->
                val v = m.groupValues[1].toFloatOrNull() ?: return@mapNotNull null
                SensorTemperature(m.groupValues[3].trim(), m.groupValues[2].toInt(), v)
            }.distinctBy { it.name }.toList()
        }
    }
}
