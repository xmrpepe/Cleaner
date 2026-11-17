package dev.pixelforge.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pixelforge_settings")

enum class ThemeMode { SYSTEM, DARK, LIGHT, AMOLED }
enum class Accent(val label: String, val argb: Long) {
    FORGE_ORANGE("Forge orange", 0xFFFF6B1A),
    PLASMA_CYAN("Plasma cyan", 0xFF22D3EE),
    COBALT("Cobalt blue", 0xFF4C8DFF),
    SIGNAL_GREEN("Signal green", 0xFF3DDC84),
    ION_VIOLET("Ion violet", 0xFFA78BFA),
}
enum class Density { COMFORTABLE, COMPACT }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accent: Accent = Accent.FORGE_ORANGE,
    val density: Density = Density.COMFORTABLE,
    val animations: Boolean = true,
    val monitorIntervalMs: Long = 1500,
    val adaptiveMonitoring: Boolean = true,
    val benchmarkSeconds: Int = 3,
    val sustainedMinutes: Int = 5,
    val thermalGuard: Boolean = true,
    val useRecycleBin: Boolean = true,
    val recycleRetentionDays: Int = 7,
    val largeFileThresholdMb: Int = 100,
    val oldDownloadDays: Int = 90,
    val oldScreenshotDays: Int = 60,
    val scanExclusions: Set<String> = emptySet(),
    val telemetryEnabled: Boolean = true,
    val telemetryIntervalMin: Int = 30,
    val dataRetentionDays: Int = 30,
    val autoDailyMaintenance: Boolean = true,
    val lowStorageAlert: Boolean = true,
    val lowStorageThresholdPct: Int = 10,
    val highTempAlert: Boolean = true,
    val highTempThresholdC: Float = 42f,
    val lowBatteryAdvice: Boolean = true,
    val lowBatteryThresholdPct: Int = 15,
    val developerDiagnostics: Boolean = false,
    val desktopAutoPresent: Boolean = false,
    val shizukuEnabled: Boolean = true,
    val activeProfile: String = "BALANCED",
    val onboardingDone: Boolean = false,
    val speedTestConsent: Boolean = false,
    val designCapacityMah: Int = 0,
)

class SettingsRepository(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val density = stringPreferencesKey("density")
        val animations = booleanPreferencesKey("animations")
        val monitorInterval = longPreferencesKey("monitor_interval")
        val adaptive = booleanPreferencesKey("adaptive_monitoring")
        val benchSeconds = intPreferencesKey("bench_seconds")
        val sustainedMinutes = intPreferencesKey("sustained_minutes")
        val thermalGuard = booleanPreferencesKey("thermal_guard")
        val recycle = booleanPreferencesKey("recycle_bin")
        val retention = intPreferencesKey("recycle_retention")
        val largeMb = intPreferencesKey("large_file_mb")
        val oldDownloads = intPreferencesKey("old_download_days")
        val oldScreens = intPreferencesKey("old_screenshot_days")
        val exclusions = stringSetPreferencesKey("scan_exclusions")
        val telemetry = booleanPreferencesKey("telemetry")
        val telemetryInterval = intPreferencesKey("telemetry_interval")
        val dataRetention = intPreferencesKey("data_retention")
        val daily = booleanPreferencesKey("daily_maintenance")
        val lowStorage = booleanPreferencesKey("low_storage")
        val lowStoragePct = intPreferencesKey("low_storage_pct")
        val highTemp = booleanPreferencesKey("high_temp")
        val highTempC = floatPreferencesKey("high_temp_c")
        val lowBattery = booleanPreferencesKey("low_battery")
        val lowBatteryPct = intPreferencesKey("low_battery_pct")
        val devDiag = booleanPreferencesKey("dev_diagnostics")
        val desktop = booleanPreferencesKey("desktop_auto_present")
        val shizuku = booleanPreferencesKey("shizuku_enabled")
        val profile = stringPreferencesKey("active_profile")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val speedConsent = booleanPreferencesKey("speed_test_consent")
        val designCapacity = intPreferencesKey("design_capacity_mah")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { readFrom(it, AppSettings()) }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val old = readFrom(p, AppSettings())
            val n = transform(old)
            p[K.theme] = n.themeMode.name
            p[K.accent] = n.accent.name
            p[K.density] = n.density.name
            p[K.animations] = n.animations
            p[K.monitorInterval] = n.monitorIntervalMs
            p[K.adaptive] = n.adaptiveMonitoring
            p[K.benchSeconds] = n.benchmarkSeconds
            p[K.sustainedMinutes] = n.sustainedMinutes
            p[K.thermalGuard] = n.thermalGuard
            p[K.recycle] = n.useRecycleBin
            p[K.retention] = n.recycleRetentionDays
            p[K.largeMb] = n.largeFileThresholdMb
            p[K.oldDownloads] = n.oldDownloadDays
            p[K.oldScreens] = n.oldScreenshotDays
            p[K.exclusions] = n.scanExclusions
            p[K.telemetry] = n.telemetryEnabled
            p[K.telemetryInterval] = n.telemetryIntervalMin
            p[K.dataRetention] = n.dataRetentionDays
            p[K.daily] = n.autoDailyMaintenance
            p[K.lowStorage] = n.lowStorageAlert
            p[K.lowStoragePct] = n.lowStorageThresholdPct
            p[K.highTemp] = n.highTempAlert
            p[K.highTempC] = n.highTempThresholdC
            p[K.lowBattery] = n.lowBatteryAdvice
            p[K.lowBatteryPct] = n.lowBatteryThresholdPct
            p[K.devDiag] = n.developerDiagnostics
            p[K.desktop] = n.desktopAutoPresent
            p[K.shizuku] = n.shizukuEnabled
            p[K.profile] = n.activeProfile
            p[K.onboarding] = n.onboardingDone
            p[K.speedConsent] = n.speedTestConsent
            p[K.designCapacity] = n.designCapacityMah
        }
    }

    suspend fun reset() {
        context.dataStore.edit { it.clear() }
    }

    private fun readFrom(p: Preferences, d: AppSettings): AppSettings = AppSettings(
        themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
        accent = p[K.accent]?.let { runCatching { Accent.valueOf(it) }.getOrNull() } ?: d.accent,
        density = p[K.density]?.let { runCatching { Density.valueOf(it) }.getOrNull() } ?: d.density,
        animations = p[K.animations] ?: d.animations,
        monitorIntervalMs = p[K.monitorInterval] ?: d.monitorIntervalMs,
        adaptiveMonitoring = p[K.adaptive] ?: d.adaptiveMonitoring,
        benchmarkSeconds = p[K.benchSeconds] ?: d.benchmarkSeconds,
        sustainedMinutes = p[K.sustainedMinutes] ?: d.sustainedMinutes,
        thermalGuard = p[K.thermalGuard] ?: d.thermalGuard,
        useRecycleBin = p[K.recycle] ?: d.useRecycleBin,
        recycleRetentionDays = p[K.retention] ?: d.recycleRetentionDays,
        largeFileThresholdMb = p[K.largeMb] ?: d.largeFileThresholdMb,
        oldDownloadDays = p[K.oldDownloads] ?: d.oldDownloadDays,
        oldScreenshotDays = p[K.oldScreens] ?: d.oldScreenshotDays,
        scanExclusions = p[K.exclusions] ?: d.scanExclusions,
        telemetryEnabled = p[K.telemetry] ?: d.telemetryEnabled,
        telemetryIntervalMin = p[K.telemetryInterval] ?: d.telemetryIntervalMin,
        dataRetentionDays = p[K.dataRetention] ?: d.dataRetentionDays,
        autoDailyMaintenance = p[K.daily] ?: d.autoDailyMaintenance,
        lowStorageAlert = p[K.lowStorage] ?: d.lowStorageAlert,
        lowStorageThresholdPct = p[K.lowStoragePct] ?: d.lowStorageThresholdPct,
        highTempAlert = p[K.highTemp] ?: d.highTempAlert,
        highTempThresholdC = p[K.highTempC] ?: d.highTempThresholdC,
        lowBatteryAdvice = p[K.lowBattery] ?: d.lowBatteryAdvice,
        lowBatteryThresholdPct = p[K.lowBatteryPct] ?: d.lowBatteryThresholdPct,
        developerDiagnostics = p[K.devDiag] ?: d.developerDiagnostics,
        desktopAutoPresent = p[K.desktop] ?: d.desktopAutoPresent,
        shizukuEnabled = p[K.shizuku] ?: d.shizukuEnabled,
        activeProfile = p[K.profile] ?: d.activeProfile,
        onboardingDone = p[K.onboarding] ?: d.onboardingDone,
        speedTestConsent = p[K.speedConsent] ?: d.speedTestConsent,
        designCapacityMah = p[K.designCapacity] ?: d.designCapacityMah,
    )
}
