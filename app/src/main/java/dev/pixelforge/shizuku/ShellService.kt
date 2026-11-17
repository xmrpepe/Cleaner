package dev.pixelforge.shizuku

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService implementation. Instantiated by Shizuku in a separate process that
 * runs as the shell UID (2000). Must keep a no-arg or (Context) constructor.
 */
@Keep
class ShellService() : IShellService.Stub() {

    @Keep
    @Suppress("unused")
    constructor(context: Context) : this()

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String, timeoutMs: Long): String = try {
        val p = ProcessBuilder("/system/bin/sh", "-c", command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val drain = Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(out) {
                            if (out.length < MAX_OUTPUT) out.appendLine(line)
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
        drain.isDaemon = true
        drain.start()
        val finished = p.waitFor(timeoutMs.coerceIn(100, 600_000), TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        drain.join(2000)
        val code = if (finished) p.exitValue() else -2
        "$code\n" + synchronized(out) { out.toString() }
    } catch (t: Throwable) {
        "-1\n${t.message}"
    }

    override fun readFile(path: String, maxBytes: Int): String? = try {
        val f = File(path)
        f.inputStream().use { input ->
            val limit = maxBytes.coerceIn(1, 1 shl 20)
            val buf = ByteArray(limit)
            var total = 0
            while (total < limit) {
                val n = input.read(buf, total, limit - total)
                if (n <= 0) break
                total += n
            }
            String(buf, 0, total)
        }
    } catch (_: Throwable) {
        null
    }

    override fun uid(): Int = Process.myUid()

    companion object {
        private const val MAX_OUTPUT = 4 * 1024 * 1024
    }
}
