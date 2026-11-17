package dev.pixelforge.core.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TelemetryDao {
    @Insert suspend fun insert(sample: TelemetrySample): Long

    @Query("SELECT * FROM telemetry WHERE time >= :since ORDER BY time ASC")
    fun observeSince(since: Long): Flow<List<TelemetrySample>>

    @Query("SELECT * FROM telemetry WHERE time >= :since ORDER BY time ASC")
    suspend fun since(since: Long): List<TelemetrySample>

    @Query("SELECT * FROM telemetry ORDER BY time DESC LIMIT 1")
    suspend fun latest(): TelemetrySample?

    @Query("DELETE FROM telemetry WHERE time < :before")
    suspend fun deleteBefore(before: Long): Int
}

@Dao
interface ActionLogDao {
    @Insert suspend fun insert(entry: ActionLogEntry): Long

    @Query("SELECT * FROM action_log ORDER BY time DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<ActionLogEntry>>

    @Query("SELECT * FROM action_log ORDER BY time DESC")
    suspend fun all(): List<ActionLogEntry>

    @Query("DELETE FROM action_log WHERE time < :before")
    suspend fun deleteBefore(before: Long): Int

    @Query("DELETE FROM action_log")
    suspend fun clear()
}

@Dao
interface TrashDao {
    @Insert suspend fun insert(entry: TrashEntry): Long

    @Delete suspend fun delete(entry: TrashEntry)

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    fun observeAll(): Flow<List<TrashEntry>>

    @Query("SELECT * FROM trash ORDER BY deletedAt DESC")
    suspend fun all(): List<TrashEntry>

    @Query("SELECT * FROM trash WHERE deletedAt < :before")
    suspend fun olderThan(before: Long): List<TrashEntry>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM trash")
    fun observeTotalBytes(): Flow<Long>
}

@Dao
interface BenchmarkDao {
    @Insert suspend fun insert(run: BenchmarkRun): Long

    @Query("SELECT * FROM benchmark_runs ORDER BY time DESC")
    fun observeAll(): Flow<List<BenchmarkRun>>

    @Query("SELECT * FROM benchmark_runs ORDER BY time DESC")
    suspend fun all(): List<BenchmarkRun>

    @Query("SELECT * FROM benchmark_runs WHERE testId = :testId AND testVersion = :version ORDER BY time DESC LIMIT :limit")
    suspend fun history(testId: String, version: Int, limit: Int = 20): List<BenchmarkRun>

    @Query("DELETE FROM benchmark_runs")
    suspend fun clear()
}

@Dao
interface OptimizationReportDao {
    @Insert suspend fun insert(report: OptimizationReport): Long

    @Query("SELECT * FROM optimization_reports ORDER BY time DESC")
    fun observeAll(): Flow<List<OptimizationReport>>

    @Query("SELECT * FROM optimization_reports WHERE id = :id")
    suspend fun byId(id: Long): OptimizationReport?
}

@Dao
interface ReversibleChangeDao {
    @Insert suspend fun insert(change: ReversibleChange): Long

    @Update suspend fun update(change: ReversibleChange)

    @Query("SELECT * FROM reversible_changes ORDER BY time DESC")
    fun observeAll(): Flow<List<ReversibleChange>>

    @Query("SELECT * FROM reversible_changes WHERE reverted = 0 ORDER BY time DESC")
    suspend fun active(): List<ReversibleChange>

    @Query("SELECT * FROM reversible_changes WHERE reverted = 0 AND source = :source ORDER BY time DESC")
    suspend fun activeFromSource(source: String): List<ReversibleChange>

    /** The earliest unreverted change for a target/key holds the original value. */
    @Query("SELECT * FROM reversible_changes WHERE reverted = 0 AND kind = :kind AND target = :target AND `key` = :key ORDER BY time ASC LIMIT 1")
    suspend fun earliestActive(kind: String, target: String, key: String): ReversibleChange?

    @Query("UPDATE reversible_changes SET reverted = 1 WHERE kind = :kind AND target = :target AND `key` = :key")
    suspend fun markReverted(kind: String, target: String, key: String)
}

@Dao
interface FileBookmarkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(b: FileBookmark)

    @Query("DELETE FROM file_bookmarks WHERE location = :location AND kind = :kind")
    suspend fun remove(location: String, kind: String)

    @Query("SELECT * FROM file_bookmarks WHERE kind = :kind ORDER BY time DESC LIMIT :limit")
    fun observe(kind: String, limit: Int = 50): Flow<List<FileBookmark>>

    @Query("DELETE FROM file_bookmarks WHERE kind = :kind AND location NOT IN (SELECT location FROM file_bookmarks WHERE kind = :kind ORDER BY time DESC LIMIT :keep)")
    suspend fun trim(kind: String, keep: Int)
}
