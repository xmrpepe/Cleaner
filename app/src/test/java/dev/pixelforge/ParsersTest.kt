package dev.pixelforge

import dev.pixelforge.core.monitor.CpuSampler
import dev.pixelforge.core.monitor.MemoryReader
import dev.pixelforge.core.monitor.ThermalReader
import dev.pixelforge.core.util.SysFs
import dev.pixelforge.ui.screens.parseMeminfoDump
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsersTest {

    @Test
    fun cpuListParsing() {
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), SysFs.parseCpuList("0-7"))
        assertEquals(listOf(0, 1, 2, 3, 5, 7, 8), SysFs.parseCpuList("0-3,5,7-8"))
        assertEquals(emptyList<Int>(), SysFs.parseCpuList(""))
        assertEquals(emptyList<Int>(), SysFs.parseCpuList("garbage"))
    }

    @Test
    fun procStatUtilisation() {
        val a = CpuSampler.parseStat("cpu  100 0 100 800 0 0 0 0 0 0\ncpu0 50 0 50 400 0 0 0 0 0 0\nintr 1 2 3")
        val b = CpuSampler.parseStat("cpu  200 0 200 900 0 0 0 0 0 0\ncpu0 100 0 100 450 0 0 0 0 0 0\n")
        val total = CpuSampler.utilisation(a.getValue(-1), b.getValue(-1))!!
        // Δbusy = 200, Δidle = 100, Δtotal = 300 → 66.7 %
        assertEquals(0.6667f, total, 0.001f)
        val core = CpuSampler.utilisation(a.getValue(0), b.getValue(0))!!
        assertEquals(0.6667f, core, 0.001f)
        assertNull(CpuSampler.utilisation(longArrayOf(1, 2), longArrayOf(3, 4)))
    }

    @Test
    fun meminfoParsing() {
        val m = MemoryReader.parseMeminfo("MemTotal:       11811012 kB\nMemAvailable:    5000000 kB\nSwapTotal: 0 kB\nHugePages_Total: 0\n")
        assertEquals(11811012L * 1024, m["MemTotal"])
        assertEquals(5000000L * 1024, m["MemAvailable"])
        assertEquals(0L, m["HugePages_Total"])
    }

    @Test
    fun psiParsing() {
        val (some, full) = MemoryReader.parsePsi("some avg10=1.50 avg60=0.75 avg300=0.10 total=12345\nfull avg10=0.20 avg60=0.10 avg300=0.00 total=999\n")
        assertNotNull(some); assertNotNull(full)
        assertEquals(1.5f, some!!.avg10, 0.0001f)
        assertEquals(0.1f, full!!.avg60, 0.0001f)
    }

    @Test
    fun thermalServiceParsing() {
        val dump = """
            IsStatusOverride: false
            ThermalEventListeners:
            Current temperatures from HAL:
            	Temperature{mValue=36.5, mType=0, mName=BIG, mStatus=0}
            	Temperature{mValue=31.2, mType=2, mName=battery, mStatus=0}
            	Temperature{mValue=33.0, mType=3, mName=VIRTUAL-SKIN, mStatus=0}
            Current cooling devices from HAL:
            	CoolingDevice{mValue=0, mType=2, mName=thermal-cpufreq-0}
        """.trimIndent()
        val s = ThermalReader.parseThermalService(dump)
        assertEquals(3, s.size)
        assertEquals("BIG", s[0].name)
        assertEquals("CPU", s[0].typeLabel)
        assertEquals(31.2f, s[1].celsius, 0.01f)
    }

    @Test
    fun meminfoDumpParsing() {
        val dump = """
            Total PSS by process:
                412,345K: system (pid 1234)
                 98,765K: com.google.android.apps.nexuslauncher (pid 2345 / activities)
                  1,024K: dev.pixelforge (pid 999)
            Total PSS by OOM adjustment:
                500,000K: Native
        """.trimIndent()
        val r = parseMeminfoDump(dump)
        assertEquals(3, r.size)
        assertEquals("system", r[0].first)
        assertEquals(412345L * 1024, r[0].second)
        assertTrue(r.none { it.first == "Native" })
    }
}
