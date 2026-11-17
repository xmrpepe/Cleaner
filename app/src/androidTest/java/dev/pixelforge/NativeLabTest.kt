package dev.pixelforge

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pixelforge.core.nativebridge.NativeLab
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLabTest {
    @Test
    fun nativeLibraryLoads() = assertTrue(NativeLab.isLoaded)

    @Test
    fun cpuBenchmarkProducesThroughput() {
        val r = NativeLab.cpuBench(NativeLab.CPU_INTEGER, 1, 200)
        assertTrue("ops/s should be positive: $r", r.getDouble("opsPerSecond") > 0)
        val mt = NativeLab.cpuBench(NativeLab.CPU_FLOAT, 2, 200)
        assertTrue(mt.getJSONArray("perThread").length() == 2)
    }

    @Test
    fun memoryBenchmarksProduceResults() {
        val bw = NativeLab.memBandwidth(4L shl 20, 300)
        assertTrue(bw.getDouble("copyGBs") > 0)
        val lat = NativeLab.memLatency(1L shl 20)
        assertTrue(lat.getJSONArray("points").length() >= 3)
    }

    @Test
    fun storageBenchmarkCleansUp() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val r = NativeLab.storageBench(dir.absolutePath, 16)
        assertTrue(r.toString(), r.optDouble("seqWriteMBs", 0.0) > 0)
        assertTrue(dir.listFiles()!!.none { it.name == "pf_storage_bench.bin" })
    }

    @Test
    fun vulkanProbeNeverCrashes() {
        // Emulators may lack Vulkan; the probe must still return a well-formed object.
        val r = NativeLab.vulkanProbe()
        assertTrue(r.has("available"))
    }
}
