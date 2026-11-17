package dev.pixelforge.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TelemetrySample::class,
        ActionLogEntry::class,
        TrashEntry::class,
        BenchmarkRun::class,
        OptimizationReport::class,
        ReversibleChange::class,
        FileBookmark::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class PixelForgeDatabase : RoomDatabase() {
    abstract fun telemetry(): TelemetryDao
    abstract fun actionLog(): ActionLogDao
    abstract fun trash(): TrashDao
    abstract fun benchmarks(): BenchmarkDao
    abstract fun reports(): OptimizationReportDao
    abstract fun changes(): ReversibleChangeDao
    abstract fun bookmarks(): FileBookmarkDao

    companion object {
        fun create(context: Context): PixelForgeDatabase =
            Room.databaseBuilder(context, PixelForgeDatabase::class.java, "pixelforge.db").build()
    }
}
