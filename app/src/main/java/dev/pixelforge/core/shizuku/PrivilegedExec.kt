package dev.pixelforge.core.shizuku

import android.content.Context
import android.provider.Settings
import dev.pixelforge.core.data.ActionLogger
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.util.LocalShell
import dev.pixelforge.core.util.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SettingsNamespace(val cli: String) { SYSTEM("system"), SECURE("secure"), GLOBAL("global") }

/**
 * Single choke point for every privileged operation. It picks the least-privileged route
 * available, refuses when none exists (never pretends), and writes an audit-log entry.
 */
class PrivilegedExec(
    private val context: Context,
    private val shizuku: ShizukuManager,
    private val capabilities: Capabilities,
    private val logger: ActionLogger,
) {
    /** Runs a shell command with ADB-shell privileges via Shizuku. Returns null if unavailable. */
    suspend fun shell(command: String, purpose: String, timeoutMs: Long = 30_000, audit: Boolean = true): ShellResult? {
        val result = shizuku.exec(command, timeoutMs)
        if (audit) {
            logger.log(
                category = "privileged",
                title = purpose,
                detail = "$ ${command}\n" + (result?.output?.take(1500) ?: "Shizuku unavailable — not executed"),
                tier = Tier.ADVANCED,
                success = result?.ok == true,
            )
        }
        return result
    }

    /** `dumpsys <service>` via Shizuku, or locally when DUMP was granted over ADB. Not audited (read-only). */
    suspend fun dumpsys(args: String, timeoutMs: Long = 20_000): String? = withContext(Dispatchers.IO) {
        val caps = capabilities.current()
        when {
            caps.shizukuReady -> shizuku.exec("dumpsys $args", timeoutMs)?.takeIf { it.output.isNotBlank() }?.output
            caps.dump -> LocalShell.run("/system/bin/sh", "-c", "dumpsys $args", timeoutMs = timeoutMs)
                .takeIf { it.output.isNotBlank() && !it.output.contains("Permission Denial") }?.output
            else -> null
        }
    }

    fun readSetting(ns: SettingsNamespace, key: String): String? = try {
        when (ns) {
            SettingsNamespace.SYSTEM -> Settings.System.getString(context.contentResolver, key)
            SettingsNamespace.SECURE -> Settings.Secure.getString(context.contentResolver, key)
            SettingsNamespace.GLOBAL -> Settings.Global.getString(context.contentResolver, key)
        }
    } catch (_: Throwable) {
        null
    }

    /** Reads a setting, falling back to `settings get` via Shizuku for keys hidden from apps. */
    suspend fun readSettingPrivileged(ns: SettingsNamespace, key: String): String? {
        readSetting(ns, key)?.let { return it }
        val r = shizuku.exec("settings get ${ns.cli} $key", 5000) ?: return null
        return r.output.trim().takeIf { r.ok && it.isNotEmpty() && it != "null" }
    }

    /**
     * Writes a setting. Uses the Settings provider directly when WRITE_SECURE_SETTINGS was granted
     * via ADB, else `settings put` through Shizuku. A null value deletes the key.
     */
    suspend fun writeSetting(ns: SettingsNamespace, key: String, value: String?, purpose: String): Boolean = withContext(Dispatchers.IO) {
        val caps = capabilities.current()
        var ok = false
        var route = "none"
        if (caps.writeSecureSettings && ns != SettingsNamespace.SYSTEM) {
            ok = try {
                val cr = context.contentResolver
                if (ns == SettingsNamespace.GLOBAL) Settings.Global.putString(cr, key, value) else Settings.Secure.putString(cr, key, value)
            } catch (_: Throwable) {
                false
            }
            route = "WRITE_SECURE_SETTINGS"
        }
        if (!ok && caps.shizukuReady) {
            val cmd = if (value == null) "settings delete ${ns.cli} $key" else "settings put ${ns.cli} $key ${shellQuote(value)}"
            ok = shizuku.exec(cmd, 5000)?.ok == true
            route = "Shizuku"
        }
        logger.log("settings", purpose, "${ns.cli}/$key = ${value ?: "<deleted>"} via $route", Tier.ADVANCED, ok)
        ok
    }

    companion object {
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

        private val packageRegex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

        /** Guards against shell injection when interpolating package names into commands. */
        fun isValidPackageName(name: String): Boolean = packageRegex.matches(name)
    }
}
