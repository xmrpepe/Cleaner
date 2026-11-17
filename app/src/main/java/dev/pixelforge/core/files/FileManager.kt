package dev.pixelforge.core.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import dev.pixelforge.core.data.ActionLogger
import dev.pixelforge.core.data.Tier
import dev.pixelforge.core.storage.FileKinds
import dev.pixelforge.core.storage.TrashManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** A file or folder from either the local filesystem or a Storage Access Framework tree. */
data class Entry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val local: File? = null,
    val doc: DocumentFile? = null,
) {
    val id: String get() = local?.absolutePath ?: doc!!.uri.toString()
    val mime: String get() = doc?.type ?: FileKinds.mimeOf(name)
    val hidden: Boolean get() = name.startsWith(".")

    companion object {
        fun of(f: File) = Entry(f.name, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified(), local = f)
        fun of(d: DocumentFile) = Entry(d.name ?: "?", d.isDirectory, if (d.isFile) d.length() else 0, d.lastModified(), doc = d)
    }
}

data class Volume(val label: String, val root: File, val removable: Boolean, val totalBytes: Long, val freeBytes: Long)

data class OpProgress(val done: Int, val total: Int, val current: String)

/**
 * File operations over java.io (shared storage with All files access, including USB OTG and SD
 * volumes) and DocumentFile (any location the user picks through the system picker).
 */
class FileManager(private val context: Context, private val trash: TrashManager, private val logger: ActionLogger) {

    fun volumes(): List<Volume> {
        val sm = context.getSystemService(StorageManager::class.java)
        return sm.storageVolumes.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            if (v.state != Environment.MEDIA_MOUNTED && v.state != Environment.MEDIA_MOUNTED_READ_ONLY) return@mapNotNull null
            Volume(v.getDescription(context), dir, v.isRemovable, dir.totalSpace, dir.usableSpace)
        }
    }

    fun primaryRoot(): File = Environment.getExternalStorageDirectory()

    suspend fun list(dir: Entry, showHidden: Boolean): List<Entry> = withContext(Dispatchers.IO) {
        val items = when {
            dir.local != null -> dir.local.listFiles()?.map(Entry::of).orEmpty()
            dir.doc != null -> dir.doc.listFiles().map(Entry::of)
            else -> emptyList()
        }
        items.filter { showHidden || !it.hidden }
    }

    fun openTree(uri: Uri): Entry? {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        return DocumentFile.fromTreeUri(context, uri)?.let(Entry::of)
    }

    private fun input(e: Entry): InputStream? = e.local?.inputStream() ?: e.doc?.let { context.contentResolver.openInputStream(it.uri) }

    private fun createChild(dir: Entry, name: String, isDir: Boolean, mime: String): Pair<Entry, OutputStream?>? {
        dir.local?.let { d ->
            var target = File(d, name)
            var n = 1
            while (target.exists()) { target = File(d, "${name.substringBeforeLast('.', name)} ($n)${if (name.contains('.') && !isDir) "." + name.substringAfterLast('.') else ""}"); n++ }
            return if (isDir) { target.mkdirs(); Entry.of(target) to null } else Entry.of(target) to target.outputStream()
        }
        dir.doc?.let { d ->
            val created = (if (isDir) d.createDirectory(name) else d.createFile(mime, name)) ?: return null
            return Entry.of(created) to if (isDir) null else context.contentResolver.openOutputStream(created.uri)
        }
        return null
    }

    private suspend fun copyInto(src: Entry, destDir: Entry, onFile: (String) -> Unit) {
        coroutineContext.ensureActive()
        if (src.isDir) {
            val (newDir, _) = createChild(destDir, src.name, true, "") ?: error("Cannot create ${src.name}")
            list(src, showHidden = true).forEach { copyInto(it, newDir, onFile) }
        } else {
            onFile(src.name)
            val (_, out) = createChild(destDir, src.name, false, src.mime) ?: error("Cannot create ${src.name}")
            input(src).use { i -> out.use { o -> if (i != null && o != null) i.copyTo(o, 256 * 1024) } }
        }
    }

    suspend fun copy(items: List<Entry>, dest: Entry, move: Boolean, onProgress: (OpProgress) -> Unit): Int = withContext(Dispatchers.IO) {
        var ok = 0
        items.forEachIndexed { i, e ->
            onProgress(OpProgress(i, items.size, e.name))
            if (dest.local != null && e.local != null && dest.local.absolutePath.startsWith(e.local.absolutePath + "/")) return@forEachIndexed // into itself
            val fastMove = move && e.local != null && dest.local != null && File(dest.local, e.name).let { !it.exists() && e.local.renameTo(it) }
            if (!fastMove) {
                runCatching { copyInto(e, dest) { onProgress(OpProgress(i, items.size, it)) } }.onFailure { return@forEachIndexed }
                if (move) { if (e.local != null) e.local.deleteRecursively() else e.doc?.delete() }
            }
            ok++
        }
        logger.log("files", if (move) "Move" else "Copy", "$ok/${items.size} item(s) → ${dest.name}", Tier.SAFE, ok == items.size)
        ok
    }

    suspend fun delete(items: List<Entry>, useTrash: Boolean): Int = withContext(Dispatchers.IO) {
        val locals = items.mapNotNull { it.local?.absolutePath }
        var ok = 0
        if (locals.isNotEmpty()) ok += trash.delete(locals, useTrash, "File manager delete").succeeded.size
        items.mapNotNull { it.doc }.forEach { if (it.delete()) ok++ }
        ok
    }

    suspend fun rename(e: Entry, newName: String): Boolean = withContext(Dispatchers.IO) {
        require(!newName.contains('/') && newName.isNotBlank()) { "Invalid name" }
        val ok = e.local?.let { it.renameTo(File(it.parentFile, newName)) } ?: e.doc?.renameTo(newName) ?: false
        logger.log("files", "Rename", "${e.name} → $newName", Tier.SAFE, ok)
        ok
    }

    suspend fun mkdir(dir: Entry, name: String): Boolean = withContext(Dispatchers.IO) {
        require(!name.contains('/') && name.isNotBlank()) { "Invalid name" }
        createChild(dir, name, true, "") != null
    }

    suspend fun zip(items: List<Entry>, destDir: Entry, archiveName: String, onProgress: (OpProgress) -> Unit): Entry? = withContext(Dispatchers.IO) {
        val name = if (archiveName.endsWith(".zip")) archiveName else "$archiveName.zip"
        val (entry, out) = createChild(destDir, name, false, "application/zip") ?: return@withContext null
        var count = 0
        ZipOutputStream(out!!.buffered()).use { zos ->
            suspend fun add(e: Entry, prefix: String) {
                coroutineContext.ensureActive()
                if (e.isDir) {
                    zos.putNextEntry(ZipEntry("$prefix${e.name}/")); zos.closeEntry()
                    list(e, true).forEach { add(it, "$prefix${e.name}/") }
                } else {
                    onProgress(OpProgress(count++, -1, e.name))
                    zos.putNextEntry(ZipEntry(prefix + e.name).apply { time = e.modified })
                    input(e)?.use { it.copyTo(zos, 256 * 1024) }
                    zos.closeEntry()
                }
            }
            items.forEach { add(it, "") }
        }
        logger.log("files", "Create archive", "$name ($count files)", Tier.SAFE, true)
        entry
    }

    /** Extracts a ZIP into a sibling folder; rejects entries that would escape it (zip-slip). */
    suspend fun unzip(archive: Entry, destDir: Entry, onProgress: (OpProgress) -> Unit): Int = withContext(Dispatchers.IO) {
        val (root, _) = createChild(destDir, archive.name.substringBeforeLast('.'), true, "") ?: return@withContext 0
        var count = 0
        ZipInputStream(input(archive)!!.buffered()).use { zis ->
            while (true) {
                coroutineContext.ensureActive()
                val ze = zis.nextEntry ?: break
                val clean = ze.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (clean.any { it == ".." } || clean.isEmpty()) continue
                var dir = root
                clean.dropLast(1).forEach { part ->
                    dir = list(dir, true).firstOrNull { it.isDir && it.name == part } ?: createChild(dir, part, true, "")!!.first
                }
                if (ze.isDirectory) {
                    if (list(dir, true).none { it.name == clean.last() }) createChild(dir, clean.last(), true, "")
                } else {
                    onProgress(OpProgress(count, -1, clean.last()))
                    val (_, out) = createChild(dir, clean.last(), false, FileKinds.mimeOf(clean.last())) ?: continue
                    out?.use { zis.copyTo(it, 256 * 1024) }
                    count++
                }
            }
        }
        logger.log("files", "Extract archive", "${archive.name}: $count files", Tier.SAFE, true)
        count
    }

    suspend fun search(dir: Entry, query: String, limit: Int = 500): List<Entry> = withContext(Dispatchers.IO) {
        val out = ArrayList<Entry>()
        val q = query.lowercase()
        if (dir.local != null) {
            dir.local.walkTopDown().onEnter { it.name != TrashManager.TRASH_DIR && !(it.name == "data" && it.parentFile?.name == "Android") }
                .forEach { f ->
                    if (out.size >= limit) return@withContext out
                    if (f != dir.local && f.name.lowercase().contains(q)) out += Entry.of(f)
                }
        } else {
            suspend fun rec(e: Entry, depth: Int) {
                if (out.size >= limit || depth > 12) return
                coroutineContext.ensureActive()
                list(e, true).forEach { c -> if (c.name.lowercase().contains(q)) out += c; if (c.isDir) rec(c, depth + 1) }
            }
            rec(dir, 0)
        }
        out
    }

    suspend fun folderStats(e: Entry): Pair<Int, Long> = withContext(Dispatchers.IO) {
        e.local?.walkTopDown()?.filter { it.isFile }?.fold(0 to 0L) { (n, s), f -> (n + 1) to (s + f.length()) } ?: (0 to 0L)
    }

    fun readTextPreview(e: Entry, maxBytes: Int = 32 * 1024): String? = runCatching {
        input(e)?.use { i ->
            val buf = ByteArray(maxBytes)
            val n = i.read(buf)
            if (n <= 0) "" else String(buf, 0, n)
        }
    }.getOrNull()

    fun uriFor(e: Entry): Uri? = e.doc?.uri ?: e.local?.let { runCatching { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", it) }.getOrNull() }
}
