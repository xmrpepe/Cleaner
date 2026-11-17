package dev.pixelforge.core.automation

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.pixelforge.PixelForgeApp
import dev.pixelforge.core.data.TelemetrySample
import dev.pixelforge.core.storage.JunkCategory
import dev.pixelforge.core.storage.ScanOptions
import dev.pixelforge.core.util.Format
import java.util.concurrent.TimeUnit

/**
 * Lightweight periodic sample (battery, RAM, storage, thermal) + automation rules. Runs via
 * WorkManager at ≥15 min intervals, respecting Doze and App Standby — no persistent service.
 */
class TelemetryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PixelForgeApp).container
        val cfg = c.settings.current()
        if (!cfg.telemetryEnabled) return Result.success()
        val b = c.monitor.batteryReader.sample()
        val am = applicationContext.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val st = c.monitor.readStorage()
        val pm = applicationContext.getSystemService(PowerManager::class.java)
        val status = pm.currentThermalStatus
        c.db.telemetry().insert(
            TelemetrySample(
                time = System.currentTimeMillis(), batteryLevel = b.levelPct, batteryTempC = b.temperatureC,
                charging = b.isCharging, currentUa = b.currentNowUa, voltageMv = b.voltageMv,
                availRamBytes = mi.availMem, totalRamBytes = mi.totalMem,
                storageFreeBytes = st.freeBytes, storageTotalBytes = st.totalBytes,
                thermalStatus = status, thermalHeadroom = pm.getThermalHeadroom(0).takeUnless { it.isNaN() }, source = "worker",
            ),
        )
        c.db.telemetry().deleteBefore(System.currentTimeMillis() - cfg.dataRetentionDays * 86_400_000L)
        c.db.actionLog().deleteBefore(System.currentTimeMillis() - 180L * 86_400_000L)

        val freePct = if (st.totalBytes > 0) st.freeBytes * 100 / st.totalBytes else 100
        if (cfg.lowStorageAlert && freePct < cfg.lowStorageThresholdPct) {
            Notifier.notify(applicationContext, 101, Notifier.CHANNEL_ALERTS, "Storage almost full",
                "Only ${Format.bytes(st.freeBytes)} ($freePct%) free. Open PixelForge to review cleanup options.", "cleaner")
        }
        if (cfg.highTempAlert && (b.temperatureC >= cfg.highTempThresholdC || status >= PowerManager.THERMAL_STATUS_MODERATE)) {
            Notifier.notify(applicationContext, 102, Notifier.CHANNEL_ALERTS, "Device running hot",
                "Battery ${Format.celsius(b.temperatureC)}, thermal status ${dev.pixelforge.core.monitor.ThermalSample.statusLabel(status)}. " +
                    "Pause demanding apps and avoid charging under load until it cools.", "thermal")
        }
        if (cfg.lowBatteryAdvice && !b.isCharging && b.levelPct <= cfg.lowBatteryThresholdPct && !pm.isPowerSaveMode) {
            Notifier.notify(applicationContext, 103, Notifier.CHANNEL_ALERTS, "Battery at ${b.levelPct}%",
                "Battery Saver is off. Turning it on restricts background activity and extends endurance.", "battery")
        }
        return Result.success()
    }
}

/** Daily maintenance: purges expired recycle-bin items and reports reclaimable space. Never deletes user files. */
class MaintenanceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as PixelForgeApp).container
        val cfg = c.settings.current()
        if (!cfg.autoDailyMaintenance) return Result.success()
        c.trash.purgeExpired(cfg.recycleRetentionDays)
        c.capabilities.refresh()
        if (!c.capabilities.current().allFilesAccess) return Result.success()
        val result = c.scanner.scan(
            options = ScanOptions(false, false, false, cfg.largeFileThresholdMb * 1024L * 1024L, cfg.oldDownloadDays, cfg.oldScreenshotDays, cfg.scanExclusions),
            onProgress = {},
        )
        val safe = listOf(JunkCategory.TEMP_FILES, JunkCategory.OBSOLETE_APKS).sumOf { cat -> result.categories[cat].orEmpty().filter { it.selectedByDefault }.sumOf { it.size } }
        if (safe > 50L * 1024 * 1024) {
            Notifier.notify(applicationContext, 201, Notifier.CHANNEL_MAINTENANCE, "${Format.bytes(safe)} can be cleaned safely",
                "Temporary files and obsolete APKs found during daily analysis. Nothing was deleted — review in PixelForge.", "cleaner")
        }
        return Result.success()
    }
}

object Scheduler {
    private const val TELEMETRY = "pf_telemetry"
    private const val MAINTENANCE = "pf_maintenance"

    fun schedule(context: Context, telemetryEnabled: Boolean, intervalMin: Int, daily: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (telemetryEnabled) {
            val req = PeriodicWorkRequestBuilder<TelemetryWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES).build()
            wm.enqueueUniquePeriodicWork(TELEMETRY, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(TELEMETRY)
        }
        if (daily) {
            val req = PeriodicWorkRequestBuilder<MaintenanceWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresDeviceIdle(true).build())
                .build()
            wm.enqueueUniquePeriodicWork(MAINTENANCE, ExistingPeriodicWorkPolicy.UPDATE, req)
        } else {
            wm.cancelUniqueWork(MAINTENANCE)
        }
    }
}
