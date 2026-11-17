package dev.pixelforge

import android.app.Application
import dev.pixelforge.core.automation.Notifier
import dev.pixelforge.core.automation.Scheduler
import dev.pixelforge.di.AppContainer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PixelForgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        container.shizuku.start()
        container.displays.start()
        container.capabilities.refresh()

        // Keep Shizuku enablement and background schedules in sync with settings.
        container.appScope.launch {
            container.settings.settings
                .map { Triple(it.shizukuEnabled, Triple(it.telemetryEnabled, it.telemetryIntervalMin, it.autoDailyMaintenance), Unit) }
                .distinctUntilChanged()
                .collect { (shizukuOn, sched, _) ->
                    container.shizuku.enabled = shizukuOn
                    container.capabilities.refresh()
                    Scheduler.schedule(this@PixelForgeApp, sched.first, sched.second, sched.third)
                }
        }
        container.appScope.launch {
            container.shizuku.state.collect { container.capabilities.refresh() }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (::container.isInitialized) container.monitor.recordTrimMemory(level)
    }
}
