package dev.pixelforge.core.storage

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Walks user-accessible shared storage (requires "All files access"). Android/data and
 * Android/obb are private to each app since Android 11 and are reported as inaccessible
 * rather than scanned. Nothing is deleted here — the scanner only classifies.
 */
class StorageScanner(private val context: Context) {
    companion object {
        /** Standard shared-storage folders that apps expect to exist; never proposed for removal. */
        val STANDARD_DIRS = setOf("Alarms", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music", "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones", "Android")
    }

    private val pm: PackageManager = context.packageManager

    suspend fun scan(
        root: File = Environment.getExternalStorageDirectory(),
        options: ScanOptions,
        onProgress: (ScanProgress) -> Unit,
    ): ScanResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val now = started
        val cats = JunkCategory.entries.associateWith { mutableListOf<ScannedFile>() }
        val byKind = HashMap<FileKind, Long>()
        val countByKind = HashMap<FileKind, Int>()
        val inaccessible = mutableListOf<String>()
        val allFiles = ArrayList<ScannedFile>(if (options.findDuplicates) 20_000 else 0)
        val rootPath = root.absolutePath
        val exclusions = options.exclusions.map { it.trimEnd('/') }
        var files = 0
        var bytes = 0L
        var lastReport = 0L

        val apkCache = HashMap<String, Long?>()

        fun installedVersion(pkg: String): Long? = apkCache.getOrPut(pkg) {
            try {
                pm.getPackageInfo(pkg, 0).longVersionCode
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
        }

        fun classifyFile(f: File, size: Long, modified: Long, relLower: String, parentNames: String) {
            val name = f.name
            val nameLower = name.lowercase(Locale.ROOT)
            val ext = FileKinds.ext(name)
            val kind = FileKinds.kindOf(name)
            byKind[kind] = (byKind[kind] ?: 0) + size
            countByKind[kind] = (countByKind[kind] ?: 0) + 1
            val ageDays = (now - modified) / 86_400_000L
            val path = f.absolutePath

            // Temporary / regenerable.
            when {
                ext in setOf("tmp", "temp", "part", "crdownload", "partial") && ageDays >= 1 ->
                    cats.getValue(JunkCategory.TEMP_FILES) += ScannedFile(path, name, size, modified, reason = "Incomplete or temporary file, ${ageDays}d old", selectedByDefault = true)
                parentNames.contains("/.thumbnails") ->
                    cats.getValue(JunkCategory.TEMP_FILES) += ScannedFile(path, name, size, modified, reason = "Thumbnail cache (regenerated automatically)", selectedByDefault = true)
                nameLower.startsWith(".pending-") && ageDays >= 1 ->
                    cats.getValue(JunkCategory.TEMP_FILES) += ScannedFile(path, name, size, modified, reason = "Abandoned pending media write", selectedByDefault = true)
                nameLower.startsWith(".trashed-") ->
                    cats.getValue(JunkCategory.TEMP_FILES) += ScannedFile(path, name, size, modified, reason = "Already in Android's media trash (auto-deleted after 30 days)", selectedByDefault = false)
            }

            if (ext in FileKinds.apks) {
                val info = if (ext == "apk") try { pm.getPackageArchiveInfo(path, 0) } catch (_: Throwable) { null } else null
                val installed = info?.packageName?.let { installedVersion(it) }
                if (info != null && installed != null && installed >= info.longVersionCode) {
                    cats.getValue(JunkCategory.OBSOLETE_APKS) += ScannedFile(
                        path, name, size, modified,
                        reason = "${info.packageName} v${info.longVersionCode} — installed v$installed",
                        selectedByDefault = true,
                    )
                } else {
                    cats.getValue(JunkCategory.UNINSTALLED_APKS) += ScannedFile(
                        path, name, size, modified,
                        reason = info?.let { "${it.packageName} v${it.longVersionCode} (${if (installed == null) "not installed" else "newer than installed"})" } ?: "Split/bundle or unreadable package",
                    )
                }
            }

            if (ext in setOf("bak", "old", "backup", "orig") || name.endsWith("~")) {
                cats.getValue(JunkCategory.BACKUP_FILES) += ScannedFile(path, name, size, modified, reason = "Backup leftover, ${ageDays}d old")
            }

            if ((ext in setOf("log", "dmp", "trace", "hprof") || nameLower.startsWith("crash")) &&
                !relLower.startsWith("documents/") && !relLower.startsWith("dcim/")
            ) {
                cats.getValue(JunkCategory.LOG_FILES) += ScannedFile(path, name, size, modified, reason = "Log/dump written to shared storage")
            }

            if (relLower.startsWith("download/") && ageDays >= options.oldDownloadDays && kind != FileKind.APK) {
                cats.getValue(JunkCategory.OLD_DOWNLOADS) += ScannedFile(path, name, size, modified, reason = "Not modified for $ageDays days")
            }

            if ((parentNames.contains("/screenshots")) && kind == FileKind.IMAGE && ageDays >= options.oldScreenshotDays) {
                cats.getValue(JunkCategory.OLD_SCREENSHOTS) += ScannedFile(path, name, size, modified, reason = "Screenshot, $ageDays days old")
            }

            if (size >= options.largeFileBytes) {
                cats.getValue(JunkCategory.LARGE_FILES) += ScannedFile(path, name, size, modified, reason = "${kind.label.removeSuffix("s")} · ${ageDays}d old")
            }

            if (ext in FileKinds.archives) {
                val base = name.substringBeforeLast('.').removeSuffix(".tar")
                val extracted = File(f.parentFile, base)
                if (extracted.isDirectory && (extracted.list()?.isNotEmpty() == true)) {
                    cats.getValue(JunkCategory.REDUNDANT_ARCHIVES) += ScannedFile(path, name, size, modified, reason = "Contents already extracted to ${base}/")
                }
            }

            if (options.findDuplicates && size >= 4096) allFiles += ScannedFile(path, name, size, modified)
        }

        fun walk(dir: File, depth: Int, rel: String, parentNames: String): DirUsage {
            var dirBytes = 0L
            var dirFiles = 0
            val children = ArrayList<DirUsage>()
            val entries = dir.listFiles()
            if (entries == null) {
                inaccessible += dir.absolutePath
                return DirUsage(dir.absolutePath, dir.name, 0, 0)
            }
            val relLowerDir = rel.lowercase(Locale.ROOT)
            val standardTopLevel = depth == 1 && dir.name in STANDARD_DIRS
            if (entries.isEmpty() && depth > 0 && !standardTopLevel && !relLowerDir.startsWith("android/")) {
                cats.getValue(JunkCategory.EMPTY_FOLDERS) += ScannedFile(dir.absolutePath, dir.name, 0, dir.lastModified(), isDirectory = true, reason = "Empty directory", selectedByDefault = true)
            }
            for (e in entries) {
                val childRel = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                val childRelLower = childRel.lowercase(Locale.ROOT)
                if (childRelLower == "android/data" || childRelLower == "android/obb") {
                    inaccessible += e.absolutePath
                    continue
                }
                if (e.name == TrashManager.TRASH_DIR) continue
                if (exclusions.any { e.absolutePath == it || e.absolutePath.startsWith("$it/") }) continue
                if (e.isDirectory) {
                    // Do not follow symlinks (avoid loops / double counting).
                    if (java.nio.file.Files.isSymbolicLink(e.toPath())) continue
                    val sub = walk(e, depth + 1, childRel, parentNames + "/" + e.name.lowercase(Locale.ROOT))
                    dirBytes += sub.bytes
                    dirFiles += sub.files
                    if (depth < 3) children += sub
                } else {
                    val size = e.length()
                    val mod = e.lastModified()
                    files++
                    bytes += size
                    dirBytes += size
                    dirFiles++
                    classifyFile(e, size, mod, childRelLower, parentNames)
                    val t = System.currentTimeMillis()
                    if (t - lastReport > 120) {
                        lastReport = t
                        onProgress(ScanProgress(files, bytes, e.absolutePath.removePrefix(rootPath), "Indexing"))
                    }
                }
            }
            children.sortByDescending { it.bytes }
            return DirUsage(dir.absolutePath, dir.name, dirBytes, dirFiles, if (children.size > 40) children.take(40) else children)
        }

        coroutineContext.ensureActive()
        val tree = walk(root, 0, "", "")
        coroutineContext.ensureActive()

        if (options.findDuplicates) {
            onProgress(ScanProgress(files, bytes, "", "Verifying duplicates"))
            val groups = DuplicateFinder.find(allFiles) { done, total ->
                onProgress(ScanProgress(files, bytes, "$done / $total candidates", "Hashing duplicates"))
            }
            cats.getValue(JunkCategory.DUPLICATES).addAll(groups)
        }
        if (options.findSimilarImages) {
            onProgress(ScanProgress(files, bytes, "", "Comparing photos"))
            val similar = SimilarImageFinder(context).find { done, total ->
                onProgress(ScanProgress(files, bytes, "$done / $total photos", "Comparing photos"))
            }
            cats.getValue(JunkCategory.SIMILAR_IMAGES).addAll(similar)
        }

        val sortedCats = cats.mapValues { (c, list) ->
            if (c == JunkCategory.DUPLICATES || c == JunkCategory.SIMILAR_IMAGES) list
            else list.sortedByDescending { it.size }
        }

        ScanResult(
            root = rootPath,
            startedAt = started,
            durationMs = System.currentTimeMillis() - started,
            totalFiles = files,
            totalBytes = bytes,
            byKind = byKind,
            countByKind = countByKind,
            categories = sortedCats,
            tree = tree,
            inaccessible = inaccessible,
            deepAnalysis = options.deep,
        )
    }
}
