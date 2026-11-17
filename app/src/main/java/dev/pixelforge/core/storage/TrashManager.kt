package dev.pixelforge.core.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.storage.StorageManager
import dev.pixelforge.core.data.ActionLogger
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.data.TrashDao
import dev.pixelforge.core.data.TrashEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class DeleteOutcome(val succeeded: List<String>, val failed: List<Pair<String, String>>, val bytesFreed: Long, val bytesTrashed: Long)

/**
 * PixelForge's recycle bin. Files are *renamed* into a hidden `.PixelForgeTrash` directory at the
 * root of the same volume (instant, no copy) and can be restored until the retention period
 * expires. A `.nomedia` marker keeps trashed media out of galleries.
 */
class TrashManager(
    private val context: Context,
    private val dao: TrashDao,
    private val logger: ActionLogger,
) {
    private fun volumeRootFor(file: File): File {
        val sm = context.getSystemService(StorageManager::class.java)
        sm.storageVolumes.forEach { v ->
            val dir = v.directory ?: return@forEach
            if (file.absolutePath.startsWith(dir.absolutePath + "/")) return dir
        }
        return Environment.getExternalStorageDirectory()
    }

    private fun trashDirFor(file: File): File {
        val dir = File(volumeRootFor(file), TRASH_DIR)
        if (!dir.exists()) dir.mkdirs()
        val nomedia = File(dir, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return dir
    }

    /**
     * Deletes [paths]. With [useTrash] files are moved to the recycle bin; directories that are
     * empty are removed outright (nothing to restore except the folder itself, which is logged).
     */
    suspend fun delete(paths: List<String>, useTrash: Boolean, reason: String): DeleteOutcome = withContext(Dispatchers.IO) {
        val ok = ArrayList<String>()
        val failed = ArrayList<Pair<String, String>>()
        var freed = 0L
        var trashed = 0L
        val scan = ArrayList<String>()
        for (p in paths) {
            val f = File(p)
            if (!f.exists()) { failed += p to "No longer exists"; continue }
            if (isProtected(f)) { failed += p to "Protected location"; continue }
            val size = if (f.isDirectory) f.walkBottomUp().filter { it.isFile }.sumOf { it.length() } else f.length()
            if (f.isDirectory && (f.list()?.isEmpty() == true)) {
                if (f.delete()) { ok += p; dao.insert(TrashEntry(originalPath = p, trashPath = "", sizeBytes = 0, isDirectory = true, deletedAt = System.currentTimeMillis())) } else failed += p to "Could not remove folder"
                continue
            }
            if (useTrash) {
                val target = File(trashDirFor(f), "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}_${f.name}")
                if (f.renameTo(target)) {
                    dao.insert(TrashEntry(originalPath = p, trashPath = target.absolutePath, sizeBytes = size, isDirectory = f.isDirectory, deletedAt = System.currentTimeMillis()))
                    ok += p
                    trashed += size
                    scan += p
                } else {
                    failed += p to "Move to recycle bin failed"
                }
            } else {
                val deleted = if (f.isDirectory) f.deleteRecursively() else f.delete()
                if (deleted) { ok += p; freed += size; scan += p } else failed += p to "Delete failed"
            }
        }
        if (scan.isNotEmpty()) MediaScannerConnection.scanFile(context, scan.toTypedArray(), null, null)
        logger.log(
            "cleaning", reason,
            "${ok.size} item(s) ${if (useTrash) "moved to recycle bin" else "deleted"}, ${failed.size} failed" +
                failed.take(10).joinToString("") { "\n• ${it.first}: ${it.second}" },
            Tier.SAFE, failed.isEmpty(),
        )
        DeleteOutcome(ok, failed, freed, trashed)
    }

    suspend fun restore(entry: TrashEntry): Boolean = withContext(Dispatchers.IO) {
        val original = File(entry.originalPath)
        val success = if (entry.trashPath.isEmpty()) {
            original.mkdirs() || original.isDirectory
        } else {
            val src = File(entry.trashPath)
            var dest = original
            if (dest.exists()) dest = File(original.parentFile, "restored_" + original.name)
            dest.parentFile?.mkdirs()
            src.renameTo(dest).also { if (it) MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null) }
        }
        if (success) dao.delete(entry)
        logger.log("cleaning", "Restore from recycle bin", entry.originalPath, Tier.SAFE, success)
        success
    }

    suspend fun purge(entries: List<TrashEntry>): Long = withContext(Dispatchers.IO) {
        var freed = 0L
        entries.forEach { e ->
            val f = File(e.trashPath)
            val ok = e.trashPath.isEmpty() || !f.exists() || f.deleteRecursively()
            if (ok) {
                freed += e.sizeBytes
                dao.delete(e)
            }
        }
        if (entries.isNotEmpty()) logger.log("cleaning", "Empty recycle bin", "${entries.size} item(s), ${freed} bytes freed", Tier.SAFE, true)
        freed
    }

    suspend fun purgeExpired(retentionDays: Int): Long =
        purge(dao.olderThan(System.currentTimeMillis() - retentionDays * 86_400_000L))

    private fun isProtected(f: File): Boolean {
        val p = f.absolutePath
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (p == root) return true
        val rel = p.removePrefix("$root/")
        val topLevel = setOf("Android", "DCIM", "Download", "Documents", "Pictures", "Movies", "Music", "Alarms", "Notifications", "Podcasts", "Ringtones", "Recordings", "Audiobooks")
        return rel in topLevel || rel.startsWith("Android/")
    }

    companion object {
        const val TRASH_DIR = ".PixelForgeTrash"
    }
}
