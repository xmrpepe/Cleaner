package dev.pixelforge.core.storage

/**
 * Cleaning categories. [preselected] marks categories whose items are selected by default in
 * Quick Clean because they are regenerable or redundant by construction. Everything else must
 * be chosen explicitly by the user.
 */
enum class JunkCategory(
    val label: String,
    val description: String,
    val preselected: Boolean,
) {
    TEMP_FILES("Temporary files", "Partial downloads (.part, .crdownload), .tmp files and thumbnail caches that Android regenerates on demand.", true),
    EMPTY_FOLDERS("Empty folders", "Directories that contain no files at all.", true),
    OBSOLETE_APKS("Obsolete APKs", "Installer files for apps already installed at the same or a newer version.", true),
    REDUNDANT_ARCHIVES("Extracted archives", "Archives whose contents already sit next to them in an extracted folder of the same name.", false),
    UNINSTALLED_APKS("Other APKs", "Installer files for apps that are not installed. Keep them if you plan to install later.", false),
    OLD_DOWNLOADS("Old downloads", "Files in Download/ not modified for a long time.", false),
    OLD_SCREENSHOTS("Old screenshots", "Screenshots older than your configured age.", false),
    BACKUP_FILES("Backup leftovers", "Files with .bak, .old, .backup or ~ suffixes left behind by editors and installers.", false),
    LOG_FILES("Log & dump files", "Log, trace and crash-dump files written to shared storage by apps.", false),
    LARGE_FILES("Large files", "Files above your size threshold. Review before deleting.", false),
    DUPLICATES("Duplicate files", "Byte-identical copies (verified with SHA-256). The oldest copy in each group is kept by default.", false),
    SIMILAR_IMAGES("Similar photos", "Visually near-identical photos (perceptual hash). The highest-resolution photo is kept by default.", false),
}

data class ScannedFile(
    val path: String,
    val name: String,
    val size: Long,
    val modified: Long,
    val isDirectory: Boolean = false,
    val reason: String = "",
    /** For grouped categories (duplicates / similar): the group identifier. */
    val groupId: Int = -1,
    /** True for the file proposed to keep within a group. */
    val keep: Boolean = false,
    /** Whether the item starts selected in the review screen. */
    val selectedByDefault: Boolean = false,
)

data class DirUsage(val path: String, val name: String, val bytes: Long, val files: Int, val children: List<DirUsage> = emptyList())

data class ScanProgress(val filesSeen: Int, val bytesSeen: Long, val currentPath: String, val phase: String)

data class ScanResult(
    val root: String,
    val startedAt: Long,
    val durationMs: Long,
    val totalFiles: Int,
    val totalBytes: Long,
    val byKind: Map<FileKind, Long>,
    val countByKind: Map<FileKind, Int>,
    val categories: Map<JunkCategory, List<ScannedFile>>,
    val tree: DirUsage,
    val inaccessible: List<String>,
    val deepAnalysis: Boolean,
) {
    fun bytesIn(c: JunkCategory): Long = categories[c].orEmpty().filter { !it.keep }.sumOf { it.size }
}

data class ScanOptions(
    val deep: Boolean,
    val findDuplicates: Boolean,
    val findSimilarImages: Boolean,
    val largeFileBytes: Long,
    val oldDownloadDays: Int,
    val oldScreenshotDays: Int,
    val exclusions: Set<String>,
)
