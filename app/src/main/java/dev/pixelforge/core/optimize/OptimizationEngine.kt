package dev.pixelforge.core.optimize

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.Process
import android.os.storage.StorageManager
import android.provider.Settings
import dev.pixelforge.core.apps.AppRepository
import dev.pixelforge.core.data.ActionLogger
import dev.pixelforge.core.data.OptimizationReport
import dev.pixelforge.core.data.OptimizationReportDao
import dev.pixelforge.core.data.TelemetryDao
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.data.TrashDao
import dev.pixelforge.core.monitor.LiveMonitor
import dev.pixelforge.core.monitor.SystemSnapshot
import dev.pixelforge.core.settings.SettingsRepository
import dev.pixelforge.core.shizuku.Capabilities
import dev.pixelforge.core.shizuku.PrivilegedExec
import dev.pixelforge.core.storage.JunkCategory
import dev.pixelforge.core.storage.ScanOptions
import dev.pixelforge.core.storage.ScanProgress
import dev.pixelforge.core.storage.ScanResult
import dev.pixelforge.core.storage.StorageScanner
import dev.pixelforge.core.storage.TrashManager
import dev.pixelforge.core.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class Severity { INFO, LOW, MEDIUM, HIGH }

sealed class OptAction {
    data class CleanFiles(val category: JunkCategory, val paths: List<String>, val bytes: Long) : OptAction()
    data object ClearOwnCache : OptAction()
    data object TrimAllAppCaches : OptAction()
    data object BackgroundDexopt : OptAction()
    data object EmptyExpiredTrash : OptAction()
}

data class Recommendation(
    val id: String,
    val title: String,
    val detail: String,
    val benefit: String,
    /** True when [benefit] is an estimate rather than a measured quantity. */
    val estimated: Boolean,
    val tier: Tier,
    val severity: Severity,
    val action: OptAction? = null,
    val settingsIntent: Intent? = null,
    val defaultSelected: Boolean = action != null && tier == Tier.SAFE,
)

data class Analysis(
    val snapshot: SystemSnapshot,
    val scan: ScanResult?,
    val recommendations: List<Recommendation>,
    val healthScore: Int,
    val scoreBreakdown: List<Pair<String, Int>>,
)

data class MeasuredMetric(val name: String, val before: String, val after: String, val measured: Boolean, val note: String? = null)

data class ExecutionReport(val id: Long, val results: List<Pair<Recommendation, String>>, val metrics: List<MeasuredMetric>, val failures: Int)

/**
 * Rules-based optimisation engine. It only recommends actions it can actually perform (or a
 * system screen where the user can), labels estimates as estimates, and measures before/after.
 */
class OptimizationEngine(
    private val context: Context,
    private val monitor: LiveMonitor,
    private val scanner: StorageScanner,
    private val trash: TrashManager,
    private val trashDao: TrashDao,
    private val apps: AppRepository,
    private val capabilities: Capabilities,
    private val exec: PrivilegedExec,
    private val settings: SettingsRepository,
    private val telemetry: TelemetryDao,
    private val reports: OptimizationReportDao,
    private val logger: ActionLogger,
) {
    suspend fun analyze(onProgress: (ScanProgress) -> Unit): Analysis = withContext(Dispatchers.Default) {
        val cfg = settings.current()
        capabilities.refresh()
        val caps = capabilities.current()
        val snap = monitor.sample()
        val recs = mutableListOf<Recommendation>()

        val scan = if (caps.allFilesAccess) {
            scanner.scan(
                options = ScanOptions(
                    deep = false, findDuplicates = false, findSimilarImages = false,
                    largeFileBytes = cfg.largeFileThresholdMb * 1024L * 1024L,
                    oldDownloadDays = cfg.oldDownloadDays, oldScreenshotDays = cfg.oldScreenshotDays,
                    exclusions = cfg.scanExclusions,
                ),
                onProgress = onProgress,
            )
        } else {
            recs += Recommendation(
                "grant_storage", "Allow file access to analyse storage",
                "PixelForge needs “All files access” to find temporary files, obsolete APKs and empty folders in shared storage.",
                "Enables storage cleaning", true, Tier.SAFE, Severity.MEDIUM,
                settingsIntent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
            )
            null
        }

        // --- Storage rules.
        scan?.let { s ->
            listOf(JunkCategory.TEMP_FILES, JunkCategory.OBSOLETE_APKS, JunkCategory.EMPTY_FOLDERS).forEach { c ->
                val items = s.categories[c].orEmpty().filter { it.selectedByDefault }
                if (items.isNotEmpty()) {
                    val bytes = items.sumOf { it.size }
                    recs += Recommendation(
                        "clean_${c.name.lowercase()}", "Remove ${c.label.lowercase()} (${items.size})", c.description,
                        if (bytes > 0) "Frees ${Format.bytes(bytes)} (measured file sizes)" else "Tidies ${items.size} folder(s)",
                        false, Tier.SAFE, if (bytes > 500L shl 20) Severity.MEDIUM else Severity.LOW,
                        action = OptAction.CleanFiles(c, items.map { it.path }, bytes),
                    )
                }
            }
            val large = s.categories[JunkCategory.LARGE_FILES].orEmpty()
            val oldDl = s.categories[JunkCategory.OLD_DOWNLOADS].orEmpty()
            if (oldDl.isNotEmpty()) {
                recs += Recommendation(
                    "review_downloads", "Review ${oldDl.size} old downloads", "Files in Download/ untouched for ${cfg.oldDownloadDays}+ days. Review them in the Cleaner — nothing is deleted automatically.",
                    "Up to ${Format.bytes(oldDl.sumOf { it.size })}", false, Tier.SAFE, Severity.LOW,
                )
            }
            if (large.isNotEmpty()) {
                recs += Recommendation(
                    "review_large", "Review ${large.size} large files", "Files over ${cfg.largeFileThresholdMb} MB. Review before deleting.",
                    "Up to ${Format.bytes(large.sumOf { it.size })}", false, Tier.SAFE, Severity.INFO,
                )
            }
        }

        val freeFrac = if (snap.storage.totalBytes > 0) snap.storage.freeBytes.toDouble() / snap.storage.totalBytes else 1.0
        if (freeFrac < 0.10) {
            recs += Recommendation(
                "low_storage", "Storage is ${Format.percent(1 - freeFrac)} full",
                "Below ~10 % free space, f2fs garbage collection and app installs slow down, and Android may stop background downloads.",
                "Restores write performance headroom", true, Tier.SAFE, Severity.HIGH,
                settingsIntent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            )
        }

        // --- Caches.
        val ownCache = dirSize(context.cacheDir) + (context.externalCacheDir?.let(::dirSize) ?: 0)
        if (ownCache > 1L shl 20) {
            recs += Recommendation("own_cache", "Clear PixelForge's own cache", "Thumbnails and temporary benchmark data created by PixelForge.", "Frees ${Format.bytes(ownCache)} (measured)", false, Tier.SAFE, Severity.LOW, action = OptAction.ClearOwnCache)
        }
        val totalAppCache = if (caps.usageAccess) totalAppCacheBytes() else null
        if (caps.shizukuReady) {
            recs += Recommendation(
                "trim_caches", "Trim all app caches",
                "Runs `pm trim-caches`, the same mechanism Android uses when storage runs low. Apps rebuild caches as needed, so the first launch afterwards may be slightly slower.",
                totalAppCache?.let { "Up to ${Format.bytes(it)} of cache (measured total; actual freed is measured after)" } ?: "Freed space is measured after running",
                totalAppCache == null, Tier.ADVANCED, Severity.LOW, action = OptAction.TrimAllAppCaches, defaultSelected = false,
            )
            recs += Recommendation(
                "dexopt", "Run pending ART optimisation",
                "Triggers Android's background dex-optimisation job now (`cmd package bg-dexopt-job`) so recently updated apps get their profile-guided compilation without waiting for idle maintenance. Takes minutes and uses battery.",
                "Faster launches for recently updated apps (estimate)", true, Tier.ADVANCED, Severity.INFO, action = OptAction.BackgroundDexopt, defaultSelected = false,
            )
        } else {
            recs += Recommendation(
                "app_cache_unavailable", "System-wide cache cleaning is unavailable",
                "Android does not allow ordinary apps to delete other apps' caches. Connect Shizuku (Advanced) to use `pm trim-caches`, or clear individual apps from their storage settings.",
                "—", true, Tier.UNAVAILABLE, Severity.INFO,
            )
        }

        val expired = trashDao.olderThan(System.currentTimeMillis() - cfg.recycleRetentionDays * 86_400_000L)
        if (expired.isNotEmpty()) {
            recs += Recommendation("trash", "Empty expired recycle-bin items", "${expired.size} item(s) older than ${cfg.recycleRetentionDays} days.", "Frees ${Format.bytes(expired.sumOf { it.sizeBytes })} (measured)", false, Tier.SAFE, Severity.LOW, action = OptAction.EmptyExpiredTrash)
        }

        // --- Memory.
        val mem = snap.memory
        if (mem.pressureLevel >= 2) {
            recs += Recommendation(
                "memory_pressure", "High memory pressure",
                "Available memory is close to Android's low-memory-killer threshold. Closing heavy apps you are not using (from Recents) helps more than any “RAM booster”: force-killing apps makes them relaunch and costs more CPU and battery.",
                "Smoother multitasking (estimate)", true, Tier.SAFE, Severity.MEDIUM,
            )
        }

        // --- Thermal & battery.
        if (snap.thermal.status >= PowerManager.THERMAL_STATUS_MODERATE) {
            recs += Recommendation(
                "thermal", "Device is under thermal pressure (${snap.thermal.statusLabel})",
                "The SoC is being throttled to protect hardware. Pause games/camera/charging-intensive use, remove the case while charging and keep the phone out of direct sun. PixelForge never bypasses thermal limits.",
                "Restores sustained performance once cooled", true, Tier.SAFE, Severity.HIGH,
            )
        }
        val bat = snap.battery
        if (!bat.isCharging && bat.levelPct <= cfg.lowBatteryThresholdPct && !bat.powerSaveMode) {
            recs += Recommendation(
                "battery_saver", "Battery at ${bat.levelPct}% — turn on Battery Saver",
                "Android's Battery Saver restricts background work and visual effects.", "Longer endurance (estimate)", true, Tier.SAFE, Severity.MEDIUM,
                settingsIntent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            )
        }
        dischargeRatePctPerHour()?.let { rate ->
            if (rate > 12) {
                recs += Recommendation(
                    "drain", "Fast discharge: ${Format.number(rate)} %/h recently",
                    "Measured from PixelForge's battery samples while unplugged. Check Battery usage in Settings for the responsible apps.",
                    "Identify the drain source", false, Tier.SAFE, Severity.MEDIUM,
                    settingsIntent = Intent(Intent.ACTION_POWER_USAGE_SUMMARY),
                )
            }
        }

        // --- Apps (needs Usage Access for usage-based rules).
        if (caps.usageAccess) {
            val list = apps.refresh()
            val now = System.currentTimeMillis()
            val unused = list.filter { !it.isSystem && it.packageName != context.packageName && (it.lastUsed ?: 0) < now - 60L * 86_400_000L && now - it.firstInstall > 60L * 86_400_000L }
            if (unused.isNotEmpty()) {
                recs += Recommendation(
                    "unused_apps", "${unused.size} apps unused for 60+ days",
                    unused.take(6).joinToString { it.label } + if (unused.size > 6) "…" else "" +
                        ". Uninstalling frees storage and removes background work; Android may already have hibernated them.",
                    unused.mapNotNull { it.sizes?.total }.takeIf { it.isNotEmpty() }?.let { "Up to ${Format.bytes(it.sum())} (measured app sizes)" } ?: "Frees storage",
                    false, Tier.SAFE, Severity.LOW,
                )
            }
            val unrestricted = list.filter { it.batteryUnrestricted && !it.isSystem && (it.lastUsed ?: 0) < now - 14L * 86_400_000L }
            if (unrestricted.isNotEmpty()) {
                recs += Recommendation(
                    "unrestricted_battery", "${unrestricted.size} rarely-used apps are exempt from battery optimisation",
                    unrestricted.take(5).joinToString { it.label } + ". These can run in the background without Doze limits.",
                    "Less background drain (estimate)", true, Tier.SAFE, Severity.LOW,
                    settingsIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                )
            }
        } else {
            recs += Recommendation(
                "usage_access", "Grant Usage Access for app insights",
                "Needed to see app sizes, last use and data usage. Data never leaves the device.", "Enables app & battery analysis", true, Tier.SAFE, Severity.INFO,
                settingsIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            )
        }

        // --- Security.
        runCatching { ChronoUnit.DAYS.between(LocalDate.parse(android.os.Build.VERSION.SECURITY_PATCH), LocalDate.now()) }.getOrNull()?.let { age ->
            if (age > 60) recs += Recommendation(
                "patch", "Security patch is $age days old", "Install the latest system update for security and performance fixes.",
                "Security & bug fixes", true, Tier.SAFE, Severity.MEDIUM, settingsIntent = Intent("android.settings.SYSTEM_UPDATE_SETTINGS"),
            )
        }

        val (score, breakdown) = healthScore(snap)
        Analysis(snap, scan, recs.sortedByDescending { it.severity.ordinal }, score, breakdown)
    }

    /**
     * Transparent device-health index (0–100). It is a summary of measured conditions, not a
     * performance claim: storage headroom 30, memory headroom 25, thermal 25, battery 20.
     */
    fun healthScore(s: SystemSnapshot): Pair<Int, List<Pair<String, Int>>> {
        val free = if (s.storage.totalBytes > 0) s.storage.freeBytes.toDouble() / s.storage.totalBytes else 1.0
        val storage = (30 * (free / 0.25).coerceAtMost(1.0)).toInt()
        val memory = when (s.memory.pressureLevel) { 0 -> 25; 1 -> 18; 2 -> 10; else -> 3 }
        val thermal = when (s.thermal.status) { 0 -> 25; 1 -> 20; 2 -> 12; 3 -> 5; else -> 0 }
        val tempPenalty = if (s.battery.temperatureC > 40) 6 else 0
        val battery = (if (s.battery.isCharging) 20 else (8 + 12 * s.battery.levelPct / 100)) - tempPenalty
        val parts = listOf("Storage headroom" to storage, "Memory headroom" to memory, "Thermal" to thermal, "Battery" to battery.coerceAtLeast(0))
        return parts.sumOf { it.second }.coerceIn(0, 100) to parts
    }

    suspend fun execute(selected: List<Recommendation>): ExecutionReport = withContext(Dispatchers.IO) {
        val cfg = settings.current()
        val caps = capabilities.current()
        val before = monitor.sample()
        val cacheBefore = if (caps.usageAccess) totalAppCacheBytes() else null
        val results = mutableListOf<Pair<Recommendation, String>>()
        var failures = 0

        for (r in selected) {
            val action = r.action ?: continue
            val msg = try {
                when (action) {
                    is OptAction.CleanFiles -> {
                        val o = trash.delete(action.paths, useTrash = cfg.useRecycleBin, reason = "Smart Optimize: ${action.category.label}")
                        if (o.failed.isNotEmpty()) failures++
                        "${o.succeeded.size} removed" + (if (o.bytesTrashed > 0) " (${Format.bytes(o.bytesTrashed)} in recycle bin)" else "") + if (o.failed.isNotEmpty()) ", ${o.failed.size} failed" else ""
                    }
                    OptAction.ClearOwnCache -> {
                        val size = dirSize(context.cacheDir)
                        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                        context.externalCacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                        logger.log("cleaning", "Cleared PixelForge cache", Format.bytes(size), Tier.SAFE, true)
                        "Cleared ${Format.bytes(size)}"
                    }
                    OptAction.TrimAllAppCaches -> {
                        val res = exec.shell("pm trim-caches 999G", "Trim all app caches", timeoutMs = 120_000)
                        if (res?.ok != true) { failures++; "Failed: ${res?.output?.trim() ?: "Shizuku unavailable"}" } else "Done"
                    }
                    OptAction.BackgroundDexopt -> {
                        val res = exec.shell("cmd package bg-dexopt-job", "Run background dexopt job", timeoutMs = 600_000)
                        if (res?.ok != true) { failures++; "Failed: ${res?.output?.trim()?.take(200) ?: "Shizuku unavailable"}" } else "Completed"
                    }
                    OptAction.EmptyExpiredTrash -> {
                        val freed = trash.purgeExpired(cfg.recycleRetentionDays)
                        "Freed ${Format.bytes(freed)}"
                    }
                }
            } catch (t: Throwable) {
                failures++
                "Error: ${t.message}"
            }
            results += r to msg
        }

        // Measure after a short settle period so caches/stat refresh.
        kotlinx.coroutines.delay(1500)
        val after = monitor.sample()
        val freshStorage = monitor.readStorage()
        val cacheAfter = if (caps.usageAccess) totalAppCacheBytes() else null
        val metrics = buildList {
            add(MeasuredMetric("Free storage", Format.bytes(before.storage.freeBytes), Format.bytes(freshStorage.freeBytes), true,
                "Δ ${Format.bytes(freshStorage.freeBytes - before.storage.freeBytes)}"))
            if (cacheBefore != null && cacheAfter != null) add(MeasuredMetric("App caches (total)", Format.bytes(cacheBefore), Format.bytes(cacheAfter), true))
            add(MeasuredMetric("Available RAM", Format.bytes(before.memory.availableBytes), Format.bytes(after.memory.availableBytes), true,
                "Observation only: RAM fluctuates and PixelForge does not kill apps"))
            add(MeasuredMetric("Thermal status", before.thermal.statusLabel, after.thermal.statusLabel, true))
        }
        val summary = "${results.size} action(s), $failures failure(s); free storage ${Format.bytes(before.storage.freeBytes)} → ${Format.bytes(freshStorage.freeBytes)}"
        val id = reports.insert(
            OptimizationReport(
                time = System.currentTimeMillis(), summary = summary,
                actionsJson = JSONArray().apply { results.forEach { (r, m) -> put(JSONObject().put("title", r.title).put("tier", r.tier.name).put("result", m)) } }.toString(),
                metricsJson = JSONArray().apply { metrics.forEach { put(JSONObject().put("name", it.name).put("before", it.before).put("after", it.after).put("measured", it.measured).put("note", it.note)) } }.toString(),
            ),
        )
        logger.log("optimize", "Smart Optimize", summary, if (selected.any { it.tier == Tier.ADVANCED }) Tier.ADVANCED else Tier.SAFE, failures == 0)
        ExecutionReport(id, results, metrics, failures)
    }

    private fun totalAppCacheBytes(): Long? = try {
        val ssm = context.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        apps.apps.value.ifEmpty { null }?.sumOf { a ->
            runCatching { ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, a.packageName, user).cacheBytes }.getOrDefault(0L)
        } ?: context.packageManager.getInstalledApplications(0).sumOf { ai ->
            runCatching { ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, user).cacheBytes }.getOrDefault(0L)
        }
    } catch (_: Throwable) {
        null
    }

    private suspend fun dischargeRatePctPerHour(): Double? {
        val samples = telemetry.since(System.currentTimeMillis() - 6 * 3_600_000L).filter { !it.charging }
        if (samples.size < 3) return null
        val first = samples.first()
        val last = samples.last()
        val hours = (last.time - first.time) / 3_600_000.0
        if (hours < 1.0) return null
        val drop = first.batteryLevel - last.batteryLevel
        return if (drop > 0) drop / hours else null
    }

    suspend fun latestReport() = reports.observeAll().first().firstOrNull()

    companion object {
        fun dirSize(dir: File): Long = dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
