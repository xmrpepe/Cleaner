package dev.pixelforge.core.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Periodic low-cost system sample recorded by the telemetry worker (and live monitor). */
@Entity(tableName = "telemetry", indices = [Index("time")])
data class TelemetrySample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val batteryLevel: Int,
    val batteryTempC: Float,
    val charging: Boolean,
    val currentUa: Long?,
    val voltageMv: Int?,
    val availRamBytes: Long,
    val totalRamBytes: Long,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val thermalStatus: Int,
    val thermalHeadroom: Float?,
    val source: String,
)

/** Audit trail of every action PixelForge performs (cleaning, privileged commands, settings). */
@Entity(tableName = "action_log", indices = [Index("time")])
data class ActionLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val category: String,
    val title: String,
    val detail: String,
    val tier: String,
    val success: Boolean,
)

/** File moved into the PixelForge recycle bin; restorable until purged. */
@Entity(tableName = "trash", indices = [Index("deletedAt")])
data class TrashEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalPath: String,
    val trashPath: String,
    val sizeBytes: Long,
    val isDirectory: Boolean,
    val deletedAt: Long,
)

@Entity(tableName = "benchmark_runs", indices = [Index("time"), Index("testId")])
data class BenchmarkRun(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val testId: String,
    val testVersion: Int,
    val score: Double,
    val durationMs: Long,
    /** JSON array of {name, value, unit}. */
    val metricsJson: String,
    /** JSON object with thermal/battery conditions before and after. */
    val conditionsJson: String,
    val deviceModel: String,
)

@Entity(tableName = "optimization_reports", indices = [Index("time")])
data class OptimizationReport(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val summary: String,
    /** JSON array of executed actions with outcome. */
    val actionsJson: String,
    /** JSON object: metric -> {before, after, measured} */
    val metricsJson: String,
)

/**
 * A reversible change to system state (settings value, package enable state, app-op, standby
 * bucket, game mode). [previousValue] is what we restore.
 */
@Entity(tableName = "reversible_changes", indices = [Index("time")])
data class ReversibleChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val kind: String,
    val target: String,
    val key: String,
    val previousValue: String?,
    val newValue: String,
    val label: String,
    val source: String,
    val reverted: Boolean = false,
)

@Entity(tableName = "file_bookmarks")
data class FileBookmark(
    @PrimaryKey val location: String,
    val kind: String,
    val label: String,
    val time: Long,
)
