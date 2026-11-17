package dev.pixelforge.core.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Finds visually near-identical photos (bursts, re-saves, resized copies) using a 64-bit
 * difference hash computed from MediaStore's cached thumbnails, grouped by Hamming distance.
 */
class SimilarImageFinder(private val context: Context) {

    private data class Img(val id: Long, val path: String, val name: String, val size: Long, val modified: Long, val pixels: Long, val hash: Long)

    suspend fun find(maxImages: Int = 6000, maxDistance: Int = 5, onProgress: (Int, Int) -> Unit): List<ScannedFile> {
        val rows = queryImages(maxImages)
        val images = ArrayList<Img>(rows.size)
        rows.forEachIndexed { i, r ->
            coroutineContext.ensureActive()
            val hash = dHash(r.first) ?: return@forEachIndexed
            images += r.second.copy(hash = hash)
            if (i % 25 == 0) onProgress(i, rows.size)
        }
        onProgress(rows.size, rows.size)

        // Union-find over pairs within the distance threshold. Sorting by time first keeps
        // the comparison window small for bursts, but we compare all pairs for correctness.
        val parent = IntArray(images.size) { it }
        fun find(x: Int): Int {
            var a = x
            while (parent[a] != a) { parent[a] = parent[parent[a]]; a = parent[a] }
            return a
        }
        for (i in images.indices) {
            if (i % 200 == 0) coroutineContext.ensureActive()
            val hi = images[i].hash
            for (j in i + 1 until images.size) {
                if (java.lang.Long.bitCount(hi xor images[j].hash) <= maxDistance) {
                    val a = find(i); val b = find(j)
                    if (a != b) parent[a] = b
                }
            }
        }
        val groups = images.indices.groupBy { find(it) }.values.filter { it.size > 1 }
        val out = ArrayList<ScannedFile>()
        groups.forEachIndexed { gid, idxs ->
            val members = idxs.map { images[it] }
            val keeper = members.maxWith(compareBy<Img>({ it.pixels }, { it.size }))
            members.sortedBy { it.modified }.forEach { m ->
                val isKeeper = m.id == keeper.id
                out += ScannedFile(
                    path = m.path, name = m.name, size = m.size, modified = m.modified,
                    groupId = gid, keep = isKeeper, selectedByDefault = false,
                    reason = if (isKeeper) "Best quality in group (kept)" else "Looks like ${keeper.name}",
                )
            }
        }
        return out
    }

    private fun queryImages(limit: Int): List<Pair<Long, Img>> {
        val out = ArrayList<Pair<Long, Img>>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC",
        )?.use { c ->
            while (c.moveToNext() && out.size < limit) {
                val path = c.getString(1) ?: continue
                if (!File(path).exists()) continue
                val id = c.getLong(0)
                out += id to Img(
                    id = id, path = path, name = c.getString(2) ?: File(path).name, size = c.getLong(3),
                    modified = c.getLong(4) * 1000, pixels = c.getLong(5) * c.getLong(6), hash = 0,
                )
            }
        }
        return out
    }

    private fun dHash(id: Long): Long? = try {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val thumb = context.contentResolver.loadThumbnail(uri, Size(96, 96), null)
        val small = Bitmap.createScaledBitmap(thumb, 9, 8, true)
        if (small !== thumb) thumb.recycle()
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            for (x in 0 until 8) {
                if (luma(small.getPixel(x, y)) > luma(small.getPixel(x + 1, y))) hash = hash or (1L shl bit)
                bit++
            }
        }
        small.recycle()
        hash
    } catch (_: Throwable) {
        null
    }

    private fun luma(c: Int): Int = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
}
