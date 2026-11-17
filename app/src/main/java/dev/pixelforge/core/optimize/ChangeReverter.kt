package dev.pixelforge.core.optimize

import dev.pixelforge.core.apps.ChangeKind
import dev.pixelforge.core.data.ReversibleChange
import dev.pixelforge.core.data.ReversibleChangeDao
import dev.pixelforge.core.shizuku.PrivilegedExec
import dev.pixelforge.core.shizuku.SettingsNamespace

/** Restores any recorded [ReversibleChange] to its previous value. */
class ChangeReverter(private val exec: PrivilegedExec, private val dao: ReversibleChangeDao) {

    suspend fun revert(change: ReversibleChange): Boolean {
        // Restore the *original* value: the earliest unreverted change for the same key.
        val original = dao.earliestActive(change.kind, change.target, change.key) ?: change
        val prev = original.previousValue
        val ok = when (runCatching { ChangeKind.valueOf(change.kind) }.getOrNull()) {
            ChangeKind.SETTING -> {
                val ns = runCatching { SettingsNamespace.valueOf(change.target) }.getOrNull() ?: return false
                exec.writeSetting(ns, change.key, prev, "Restore ${change.key}")
            }
            ChangeKind.PACKAGE_ENABLED -> {
                val cmd = if (prev == "false") "pm disable-user --user 0 ${change.target}" else "pm enable ${change.target}"
                exec.shell(cmd, "Restore enabled state of ${change.target}")?.ok == true
            }
            ChangeKind.APPOP -> exec.shell("cmd appops set ${change.target} ${change.key} ${prev ?: "default"}", "Restore ${change.key} for ${change.target}")?.ok == true
            ChangeKind.STANDBY_BUCKET -> exec.shell("am set-standby-bucket ${change.target} ${prev ?: "active"}", "Restore standby bucket")?.ok == true
            ChangeKind.GAME_MODE -> exec.shell("cmd game mode ${prev ?: "standard"} ${change.target}", "Restore game mode")?.ok == true
            null -> false
        }
        if (ok) dao.markReverted(change.kind, change.target, change.key)
        return ok
    }

    suspend fun revertAll(source: String? = null): Pair<Int, Int> {
        val list = if (source == null) dao.active() else dao.activeFromSource(source)
        val keys = list.distinctBy { Triple(it.kind, it.target, it.key) }
        var ok = 0
        keys.forEach { if (revert(it)) ok++ }
        return ok to keys.size
    }
}
