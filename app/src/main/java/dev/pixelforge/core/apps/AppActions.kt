package dev.pixelforge.core.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.pixelforge.core.data.ReversibleChange
import dev.pixelforge.core.data.ReversibleChangeDao
import dev.pixelforge.core.shizuku.PrivilegedExec

enum class ChangeKind { PACKAGE_ENABLED, APPOP, STANDBY_BUCKET, GAME_MODE, SETTING }

/** App-level actions. Intents are Safe tier; shell actions are Advanced and reversible. */
class AppActions(
    private val context: Context,
    private val exec: PrivilegedExec,
    private val changes: ReversibleChangeDao,
) {
    fun launchIntent(pkg: String): Intent? = context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun detailsIntent(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    fun uninstallIntent(pkg: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun batteryOptimizationListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun guard(pkg: String): String? = when {
        !PrivilegedExec.isValidPackageName(pkg) -> "Invalid package name"
        AppRepository.isCritical(pkg) -> "Protected system component — PixelForge will not modify it"
        else -> null
    }

    suspend fun setEnabled(pkg: String, label: String, enable: Boolean): Result<String> {
        guard(pkg)?.let { if (!enable) return Result.failure(IllegalStateException(it)) }
        val cmd = if (enable) "pm enable $pkg" else "pm disable-user --user 0 $pkg"
        val r = exec.shell(cmd, "${if (enable) "Enable" else "Disable"} $label") ?: return unavailable()
        if (!r.ok) return Result.failure(IllegalStateException(r.output.trim()))
        record(ChangeKind.PACKAGE_ENABLED, pkg, "enabled", if (enable) "false" else "true", enable.toString(), "${if (enable) "Enabled" else "Disabled"} $label")
        return Result.success(r.output.trim())
    }

    /** Denies RUN_ANY_IN_BACKGROUND (the "Restricted" battery state); reversible. */
    suspend fun setBackgroundRestricted(pkg: String, label: String, restricted: Boolean): Result<String> {
        guard(pkg)?.let { return Result.failure(IllegalStateException(it)) }
        val prev = exec.shell("cmd appops get $pkg RUN_ANY_IN_BACKGROUND", "Read background policy", audit = false)
            ?.output?.substringAfter("RUN_ANY_IN_BACKGROUND: ", "")?.substringBefore(';')?.trim()?.ifEmpty { null } ?: "allow"
        val mode = if (restricted) "ignore" else "allow"
        val r = exec.shell("cmd appops set $pkg RUN_ANY_IN_BACKGROUND $mode", "${if (restricted) "Restrict" else "Allow"} background for $label") ?: return unavailable()
        if (!r.ok) return Result.failure(IllegalStateException(r.output.trim()))
        record(ChangeKind.APPOP, pkg, "RUN_ANY_IN_BACKGROUND", prev, mode, "Background ${if (restricted) "restricted" else "allowed"}: $label")
        return Result.success("RUN_ANY_IN_BACKGROUND=$mode")
    }

    suspend fun standbyBucket(pkg: String): String? =
        exec.shell("am get-standby-bucket $pkg", "Read standby bucket", audit = false)?.output?.trim()?.let(::bucketName)

    suspend fun forceStop(pkg: String, label: String): Result<String> {
        guard(pkg)?.let { return Result.failure(IllegalStateException(it)) }
        val r = exec.shell("am force-stop $pkg", "Force stop $label (one-off, user requested)") ?: return unavailable()
        return if (r.ok) Result.success("Stopped") else Result.failure(IllegalStateException(r.output))
    }

    /** `pm clear --cache-only` deletes only the app's cache directories (Android 14+). */
    suspend fun clearCache(pkg: String, label: String): Result<String> {
        if (!PrivilegedExec.isValidPackageName(pkg)) return Result.failure(IllegalArgumentException("Invalid package"))
        val r = exec.shell("pm clear --cache-only $pkg", "Clear cache of $label") ?: return unavailable()
        return if (r.ok) Result.success(r.output.trim()) else Result.failure(IllegalStateException(r.output.trim()))
    }

    /** Recompiles with the profile-guided "speed-profile" filter — what the idle dexopt job does. */
    suspend fun compileSpeedProfile(pkg: String, label: String): Result<String> {
        if (!PrivilegedExec.isValidPackageName(pkg)) return Result.failure(IllegalArgumentException("Invalid package"))
        val r = exec.shell("cmd package compile -m speed-profile -f $pkg", "ART compile (speed-profile) $label", timeoutMs = 300_000) ?: return unavailable()
        return if (r.ok) Result.success(r.output.trim()) else Result.failure(IllegalStateException(r.output.trim()))
    }

    suspend fun setGameMode(pkg: String, label: String, mode: String): Result<String> {
        if (!PrivilegedExec.isValidPackageName(pkg)) return Result.failure(IllegalArgumentException("Invalid package"))
        val prev = exec.shell("cmd game mode get $pkg", "Read game mode", audit = false)?.output?.trim()
            ?.let { out -> listOf("performance", "battery", "standard", "custom").firstOrNull { out.contains(it, ignoreCase = true) } } ?: "standard"
        val r = exec.shell("cmd game mode $mode $pkg", "Game mode $mode for $label") ?: return unavailable()
        if (!r.ok || r.output.contains("not", ignoreCase = true) && r.output.contains("support", ignoreCase = true)) {
            return Result.failure(IllegalStateException(r.output.trim().ifEmpty { "Game mode not supported by this app" }))
        }
        record(ChangeKind.GAME_MODE, pkg, "game_mode", prev, mode, "Game mode $mode: $label")
        return Result.success(mode)
    }

    private suspend fun record(kind: ChangeKind, target: String, key: String, prev: String?, new: String, label: String, source: String = "manual") {
        changes.insert(
            ReversibleChange(time = System.currentTimeMillis(), kind = kind.name, target = target, key = key, previousValue = prev, newValue = new, label = label, source = source),
        )
    }

    private fun <T> unavailable(): Result<T> = Result.failure(IllegalStateException("Requires Shizuku (not connected)"))

    companion object {
        fun bucketName(raw: String): String = when (raw.trim().toIntOrNull()) {
            5 -> "Exempted"; 10 -> "Active"; 20 -> "Working set"; 30 -> "Frequent"; 40 -> "Rare"; 45 -> "Restricted"; 50 -> "Never"
            else -> raw.trim()
        }
    }
}
