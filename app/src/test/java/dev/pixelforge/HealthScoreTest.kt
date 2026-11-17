package dev.pixelforge

import dev.pixelforge.core.monitor.BatterySample
import dev.pixelforge.core.monitor.CpuLoadSource
import dev.pixelforge.core.monitor.CpuSample
import dev.pixelforge.core.monitor.GpuSample
import dev.pixelforge.core.monitor.MemorySample
import dev.pixelforge.core.monitor.PsiStat
import dev.pixelforge.core.monitor.StorageSample
import dev.pixelforge.core.monitor.SystemSnapshot
import dev.pixelforge.core.monitor.ThermalSample
import org.junit.Assert.assertEquals
import org.junit.Test

class HealthScoreTest {
    private fun mem(avail: Long, threshold: Long = 500, low: Boolean = false, psi: Float? = null) = MemorySample(
        totalBytes = 10_000, availableBytes = avail, thresholdBytes = threshold, lowMemory = low, meminfo = emptyMap(),
        psiSome = psi?.let { PsiStat(it, 0f, 0f) }, psiFull = null, selfPssBytes = 0, selfJavaHeapBytes = 0, selfNativeHeapBytes = 0,
    )

    @Test
    fun memoryPressureLevels() {
        assertEquals(0, mem(6000).pressureLevel)
        assertEquals(1, mem(1500).pressureLevel)           // < 20 % available
        assertEquals(2, mem(600, threshold = 500).pressureLevel) // close to LMK threshold
        assertEquals(3, mem(600, low = true).pressureLevel)
        assertEquals(2, mem(6000, psi = 7f).pressureLevel) // PSI takes precedence
        assertEquals(0, mem(100, psi = 0.2f).pressureLevel)
    }

    @Test
    fun batteryDerivedValues() {
        val b = BatterySample(
            levelPct = 50, status = 3, plugged = 0, health = 2, temperatureC = 30f, voltageMv = 4000, technology = "Li-ion",
            currentNowUa = -500_000, currentAvgUa = null, chargeCounterUah = 2_300_000, cycleCount = 100, chargeTimeRemainingMs = null,
            stateOfHealthPct = null, powerSaveMode = false,
        )
        assertEquals(-2.0, b.powerW!!, 1e-9)
        assertEquals(4600.0, b.estimatedFullCapacityMah!!, 1e-6)
        assertEquals("Discharging", b.statusLabel)
        assertEquals("Unplugged", b.chargingSource)
    }

    @Suppress("unused")
    private fun snapshot(freeFrac: Double, thermal: Int) = SystemSnapshot(
        0, 0, CpuSample(emptyList(), emptyList(), null, CpuLoadSource.FREQUENCY, 0f, 8), mem(6000),
        BatterySample(80, 3, 0, 2, 30f, 4000, null, null, null, null, null, null, null, false),
        ThermalSample(thermal, 0.3f, 0.3f, emptyList()), StorageSample(1000, (1000 * freeFrac).toLong()), GpuSample(null, null, null),
    )
}
