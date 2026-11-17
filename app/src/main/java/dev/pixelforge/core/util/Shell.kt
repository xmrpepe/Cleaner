package dev.pixelforge.core.util

import java.util.concurrent.TimeUnit

/** Result of a shell command, whether executed locally or through Shizuku. */
data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

/** Runs a command as the app's own UID (no extra privileges). */
object LocalShell {
    fun run(vararg command: String, timeoutMs: Long = 15_000): ShellResult = try {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val out = StringBuilder()
        val drain = Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.appendLine(it) } }
            } catch (_: Throwable) {
            }
        }
        drain.isDaemon = true
        drain.start()
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        drain.join(1000)
        ShellResult(if (finished) p.exitValue() else -2, synchronized(out) { out.toString() })
    } catch (t: Throwable) {
        ShellResult(-1, t.message ?: "exec failed")
    }
}
