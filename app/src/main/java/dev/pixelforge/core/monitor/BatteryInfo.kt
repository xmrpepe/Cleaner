package dev.pixelforge.core.monitor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager

data class BatterySample(
    val levelPct: Int,
    val status: Int,
    val plugged: Int,
    val health: Int,
    val temperatureC: Float,
    val voltageMv: Int,
    val technology: String?,
    /** Instantaneous current in µA (positive = charging on Pixel). Null when unsupported. */
    val currentNowUa: Long?,
    val currentAvgUa: Long?,
    /** Remaining charge in µAh. */
    val chargeCounterUah: Long?,
    val cycleCount: Int?,
    val chargeTimeRemainingMs: Long?,
    /** State of health %, only when the system exposes it to this app (BATTERY_STATS granted). */
    val stateOfHealthPct: Int?,
    val powerSaveMode: Boolean,
) {
    val isCharging: Boolean
        get() = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    val chargingSource: String
        get() = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC adapter"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "Dock"
            0 -> "Unplugged"
            else -> "Other ($plugged)"
        }

    val statusLabel: String
        get() = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
            else -> "Unknown"
        }

    val healthLabel: String
        get() = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

    /** Power in watts derived from measured current and voltage (null if current unsupported). */
    val powerW: Double?
        get() = currentNowUa?.let { it / 1_000_000.0 * (voltageMv / 1000.0) }

    /**
     * Estimated full-charge capacity (mAh) = remaining charge / level. This is an estimate based
     * on the fuel gauge and becomes more accurate near higher charge levels.
     */
    val estimatedFullCapacityMah: Double?
        get() = chargeCounterUah?.takeIf { it > 0 && levelPct >= 15 }?.let { it / 1000.0 / (levelPct / 100.0) }
}

class BatteryReader(private val context: Context) {
    private val bm = context.getSystemService(BatteryManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)

    fun sample(): BatterySample {
        val i: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        return BatterySample(
            levelPct = pct,
            status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1,
            plugged = i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0,
            health = i?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1,
            temperatureC = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f,
            voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            technology = i?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY),
            currentNowUa = longProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            currentAvgUa = longProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE),
            chargeCounterUah = longProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            cycleCount = i?.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1)?.takeIf { it >= 0 },
            chargeTimeRemainingMs = bm.computeChargeTimeRemaining().takeIf { it > 0 },
            stateOfHealthPct = stateOfHealth(),
            powerSaveMode = pm.isPowerSaveMode,
        )
    }

    private fun longProp(id: Int): Long? {
        val v = bm.getLongProperty(id)
        return if (v == Long.MIN_VALUE || v == 0L && id != BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) null else v
    }

    /**
     * BATTERY_PROPERTY_STATE_OF_HEALTH (value 10) is a system API guarded by BATTERY_STATS,
     * which can be granted through ADB/Shizuku. Only values in 1..100 are accepted.
     */
    private fun stateOfHealth(): Int? {
        if (context.checkSelfPermission(PERMISSION_BATTERY_STATS) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            bm.getIntProperty(PROPERTY_STATE_OF_HEALTH).takeIf { it in 1..100 }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val PROPERTY_STATE_OF_HEALTH = 10
        const val PERMISSION_BATTERY_STATS = Manifest.permission.BATTERY_STATS
    }
}
