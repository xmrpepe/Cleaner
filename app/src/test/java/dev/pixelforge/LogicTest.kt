package dev.pixelforge

import androidx.compose.ui.geometry.Rect
import dev.pixelforge.core.bench.BenchmarkRunner
import dev.pixelforge.core.bench.Metric
import dev.pixelforge.core.bench.parseMetrics
import dev.pixelforge.core.network.LatencyResult
import dev.pixelforge.core.shizuku.PrivilegedExec
import dev.pixelforge.core.storage.FileKind
import dev.pixelforge.core.storage.FileKinds
import dev.pixelforge.core.util.Format
import dev.pixelforge.ui.components.squarify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {

    @Test
    fun formatBytes() {
        assertEquals("512 B", Format.bytes(512))
        assertEquals("1.0 KB", Format.bytes(1024))
        assertEquals("1.5 MB", Format.bytes(1536L * 1024))
        assertEquals("—", Format.bytes(-1))
        assertEquals("2.40 GHz", Format.mhz(2_400_000))
        assertEquals("650 MHz", Format.mhz(650_000))
    }

    @Test
    fun fileKinds() {
        assertEquals(FileKind.IMAGE, FileKinds.kindOf("IMG_0001.HEIC"))
        assertEquals(FileKind.APK, FileKinds.kindOf("app.apk"))
        assertEquals(FileKind.ARCHIVE, FileKinds.kindOf("backup.tar.gz"))
        assertEquals(FileKind.OTHER, FileKinds.kindOf("noext"))
        assertEquals("application/pdf", FileKinds.mimeOf("doc.PDF"))
    }

    @Test
    fun packageNameGuard() {
        assertTrue(PrivilegedExec.isValidPackageName("com.example.app"))
        assertTrue(PrivilegedExec.isValidPackageName("dev.pixelforge.debug"))
        assertFalse(PrivilegedExec.isValidPackageName("com.evil; rm -rf /"))
        assertFalse(PrivilegedExec.isValidPackageName("noDots"))
        assertFalse(PrivilegedExec.isValidPackageName("com.\$(reboot)"))
    }

    @Test
    fun shellQuoting() {
        assertEquals("'a b'", PrivilegedExec.shellQuote("a b"))
        assertEquals("'it'\\''s'", PrivilegedExec.shellQuote("it's"))
    }

    @Test
    fun geomeanAndPercentiles() {
        assertEquals(2.0, BenchmarkRunner.geomean(listOf(1.0, 4.0)), 1e-9)
        assertEquals(0.0, BenchmarkRunner.geomean(emptyList()), 1e-9)
        assertEquals(3.0, BenchmarkRunner.geomean(listOf(3.0, 0.0, Double.NaN)), 1e-9) // invalid ignored
        val frames = List(99) { 8.33 } + 33.3
        assertEquals(8.33, BenchmarkRunner.percentile(frames, 0.5), 1e-9)
        assertTrue(BenchmarkRunner.stability(List(50) { 10.0 }) > 99.9)
    }

    @Test
    fun metricsRoundTrip() {
        val json = """[{"name":"Fill","value":4.2,"unit":"Gpix/s","higherIsBetter":true},{"name":"p99","value":12.5,"unit":"ms","higherIsBetter":false}]"""
        val m = parseMetrics(json)
        assertEquals(listOf(Metric("Fill", 4.2, "Gpix/s", true), Metric("p99", 12.5, "ms", false)), m)
        assertEquals(emptyList<Metric>(), parseMetrics("not json"))
    }

    @Test
    fun latencyStats() {
        val r = LatencyResult("x", listOf(10.0, 20.0, 30.0), lost = 1)
        assertEquals(4, r.sent)
        assertEquals(20.0, r.avg!!, 1e-9)
        assertEquals(25.0, r.lossPct, 1e-9)
        assertEquals(10.0, r.jitter!!, 1e-9)
    }

    @Test
    fun treemapConservesArea() {
        val bounds = Rect(0f, 0f, 400f, 300f)
        val values = listOf(500.0, 250.0, 120.0, 80.0, 30.0, 20.0)
        val rects = squarify(values, bounds)
        assertEquals(values.size, rects.size)
        val area = rects.sumOf { (it.width * it.height).toDouble() }
        assertEquals(400.0 * 300.0, area, 1.0)
        rects.forEach { r ->
            assertTrue(r.left >= -0.01f && r.top >= -0.01f && r.right <= 400.01f && r.bottom <= 300.01f)
        }
        // Proportionality.
        val ratio = (rects[0].width * rects[0].height) / (rects[1].width * rects[1].height)
        assertEquals(2.0, ratio.toDouble(), 0.01)
    }
}
