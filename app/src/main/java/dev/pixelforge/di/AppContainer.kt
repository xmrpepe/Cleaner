package dev.pixelforge.di

import android.content.Context
import dev.pixelforge.core.apps.AppActions
import dev.pixelforge.core.apps.AppRepository
import dev.pixelforge.core.bench.BenchmarkRunner
import dev.pixelforge.core.data.ActionLogger
import dev.pixelforge.core.data.PixelForgeDatabase
import dev.pixelforge.core.device.DeviceInfoRepository
import dev.pixelforge.core.display.ExternalDisplays
import dev.pixelforge.core.monitor.LiveMonitor
import dev.pixelforge.core.network.NetworkDiagnostics
import dev.pixelforge.core.optimize.ChangeReverter
import dev.pixelforge.core.optimize.OptimizationEngine
import dev.pixelforge.core.optimize.ProfileEngine
import dev.pixelforge.core.privacy.PrivacyAnalyzer
import dev.pixelforge.core.settings.SettingsRepository
import dev.pixelforge.core.shizuku.Capabilities
import dev.pixelforge.core.shizuku.PrivilegedExec
import dev.pixelforge.core.shizuku.ShizukuManager
import dev.pixelforge.core.storage.StorageScanner
import dev.pixelforge.core.storage.TrashManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

/** Manual dependency container — no reflection, no annotation processing, instant startup. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db: PixelForgeDatabase by lazy { PixelForgeDatabase.create(appContext) }
    val settings = SettingsRepository(appContext)
    val logger by lazy { ActionLogger(db.actionLog()) }
    val shizuku = ShizukuManager(appContext)
    val capabilities = Capabilities(appContext, shizuku)
    val privileged by lazy { PrivilegedExec(appContext, shizuku, capabilities, logger) }
    val monitor by lazy { LiveMonitor(appContext, appScope, settings, shizuku, capabilities, privileged) }
    val scanner by lazy { StorageScanner(appContext) }
    val trash by lazy { TrashManager(appContext, db.trash(), logger) }
    val apps by lazy { AppRepository(appContext) }
    val appActions by lazy { AppActions(appContext, privileged, db.changes()) }
    val reverter by lazy { ChangeReverter(privileged, db.changes()) }
    val privacy by lazy { PrivacyAnalyzer(appContext) }
    val network by lazy { NetworkDiagnostics(appContext) }
    val deviceInfo by lazy { DeviceInfoRepository(appContext) }
    val benchmarks by lazy { BenchmarkRunner(appContext, db.benchmarks(), settings, privileged) }
    val profiles by lazy { ProfileEngine(appContext, privileged, capabilities, db.changes(), settings, apps, appActions, logger) }
    val optimizer by lazy {
        OptimizationEngine(appContext, monitor, scanner, trash, db.trash(), apps, capabilities, privileged, settings, db.telemetry(), db.reports(), logger)
    }
    val files by lazy { dev.pixelforge.core.files.FileManager(appContext, trash, logger) }
    val displays = ExternalDisplays(appContext)
}
