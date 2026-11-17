package dev.pixelforge.core.shizuku

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Snapshot of everything PixelForge is currently authorised to do. */
data class CapabilityState(
    val allFilesAccess: Boolean = false,
    val usageAccess: Boolean = false,
    val notifications: Boolean = false,
    val fineLocation: Boolean = false,
    val phoneState: Boolean = false,
    val writeSecureSettings: Boolean = false,
    val dump: Boolean = false,
    val batteryStats: Boolean = false,
    val shizuku: ShizukuState = ShizukuState.NOT_RUNNING,
) {
    val shizukuReady: Boolean get() = shizuku == ShizukuState.READY
    /** Can run `dumpsys` (via Shizuku, or locally after `pm grant ... DUMP`). */
    val canDump: Boolean get() = shizukuReady || dump
    /** Can change global/secure settings (via Shizuku or an ADB-granted permission). */
    val canWriteSecureSettings: Boolean get() = shizukuReady || writeSecureSettings
}

class Capabilities(private val context: Context, private val shizuku: ShizukuManager) {
    private val _state = MutableStateFlow(CapabilityState())
    val state: StateFlow<CapabilityState> = _state.asStateFlow()

    fun refresh() {
        shizuku.refresh()
        _state.value = CapabilityState(
            allFilesAccess = Environment.isExternalStorageManager(),
            usageAccess = hasUsageAccess(),
            notifications = granted(Manifest.permission.POST_NOTIFICATIONS),
            fineLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
            phoneState = granted(Manifest.permission.READ_PHONE_STATE),
            writeSecureSettings = granted(Manifest.permission.WRITE_SECURE_SETTINGS),
            dump = granted(Manifest.permission.DUMP),
            batteryStats = granted(Manifest.permission.BATTERY_STATS),
            shizuku = shizuku.state.value,
        )
    }

    fun current(): CapabilityState = _state.value

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasUsageAccess(): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            granted(Manifest.permission.PACKAGE_USAGE_STATS)
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
