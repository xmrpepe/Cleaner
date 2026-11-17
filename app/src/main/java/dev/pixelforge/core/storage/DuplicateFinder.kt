package dev.pixelforge.core.storage

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * Three-stage duplicate detection: equal size → equal head/tail sample → equal SHA-256.
 * Only byte-identical files are reported.
 */
object DuplicateFinder {
    private const val SAMPLE = 16 * 1024

    suspend fun find(files: List<ScannedFile>, onProgress: (Int, Int) -> Unit): List<ScannedFile> {
        val bySize = files.groupBy { it.size }.values.filter { it.size > 1 }
        val candidates = bySize.sumOf { it.size }
        var done = 0
        val out = ArrayList<ScannedFile>()
        var groupId = 0
        for (sameSize in bySize) {
            coroutineContext.ensureActive()
            val bySample = sameSize.groupBy { quickHash(it.path) }.filterKeys { it != null }.values.filter { it.size > 1 }
            for (group in bySample) {
                val byFull = group.groupBy { fullHash(it.path) }.filterKeys { it != null }.values.filter { it.size > 1 }
                for (dupes in byFull) {
                    // Keep the oldest copy (most likely the original), prefer DCIM/ for camera media.
                    val keeper = dupes.minWith(compareBy<ScannedFile>({ if (it.path.contains("/DCIM/")) 0 else 1 }, { it.modified }))
                    val id = groupId++
                    dupes.sortedBy { it.modified }.forEach { f ->
                        val isKeeper = f.path == keeper.path
                        out += f.copy(
                            groupId = id,
                            keep = isKeeper,
                            selectedByDefault = !isKeeper,
                            reason = if (isKeeper) "Original (kept)" else "Identical to ${keeper.name}",
                        )
                    }
                }
            }
            done += sameSize.size
            onProgress(done, candidates)
        }
        return out
    }

    private fun quickHash(path: String): String? = try {
        RandomAccessFile(File(path), "r").use { raf ->
            val md = MessageDigest.getInstance("SHA-256")
            val len = raf.length()
            val buf = ByteArray(SAMPLE)
            val n1 = raf.read(buf, 0, minOf(SAMPLE.toLong(), len).toInt())
            if (n1 > 0) md.update(buf, 0, n1)
            if (len > SAMPLE * 2) {
                raf.seek(len - SAMPLE)
                val n2 = raf.read(buf)
                if (n2 > 0) md.update(buf, 0, n2)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Throwable) {
        null
    }

    private fun fullHash(path: String): String? = try {
        File(path).inputStream().buffered(256 * 1024).use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
    } catch (_: Throwable) {
        null
    }
}
