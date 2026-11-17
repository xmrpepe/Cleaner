package dev.pixelforge.core.nativebridge

import android.util.Log
import org.json.JSONObject

/**
 * Thin Kotlin facade over libpixelforge.so (see app/src/main/cpp). Every call is blocking and
 * must run on a background dispatcher. Results are JSON objects produced natively.
 */
object NativeLab {
    private const val TAG = "NativeLab"

    val isLoaded: Boolean = try {
        System.loadLibrary("pixelforge")
        true
    } catch (t: Throwable) {
        Log.e(TAG, "Native library unavailable", t)
        false
    }

    const val CPU_INTEGER = 0
    const val CPU_FLOAT = 1
    const val CPU_SORT = 2

    fun setCancelled(cancelled: Boolean) {
        if (isLoaded) nativeSetCancelled(cancelled)
    }

    fun cpuBench(kind: Int, threads: Int, durationMs: Int, cpuMask: Long = 0L): JSONObject =
        call { nativeCpuBench(kind, threads, durationMs, cpuMask) }

    fun memBandwidth(bufferBytes: Long, durationMs: Int): JSONObject =
        call { nativeMemBandwidth(bufferBytes, durationMs) }

    fun memLatency(maxBytes: Long): JSONObject = call { nativeMemLatency(maxBytes) }

    fun storageBench(dir: String, fileMb: Int): JSONObject = call { nativeStorageBench(dir, fileMb) }

    fun vulkanProbe(): JSONObject = call { nativeVulkanProbe() }

    fun vulkanCompute(durationMs: Int): JSONObject = call { nativeVulkanCompute(durationMs) }

    fun vulkanFill(durationMs: Int, width: Int, height: Int): JSONObject =
        call { nativeVulkanFill(durationMs, width, height) }

    private inline fun call(block: () -> String): JSONObject {
        if (!isLoaded) return JSONObject().put("available", false).put("error", "native library not loaded")
        return try {
            JSONObject(block())
        } catch (t: Throwable) {
            Log.e(TAG, "Native call failed", t)
            JSONObject().put("available", false).put("error", t.message ?: t.javaClass.simpleName)
        }
    }

    private external fun nativeSetCancelled(cancelled: Boolean)
    private external fun nativeCpuBench(kind: Int, threads: Int, durationMs: Int, cpuMask: Long): String
    private external fun nativeMemBandwidth(bufferBytes: Long, durationMs: Int): String
    private external fun nativeMemLatency(maxBytes: Long): String
    private external fun nativeStorageBench(dir: String, fileMb: Int): String
    private external fun nativeVulkanProbe(): String
    private external fun nativeVulkanCompute(durationMs: Int): String
    private external fun nativeVulkanFill(durationMs: Int, width: Int, height: Int): String
}
