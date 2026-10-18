package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class GateStatsTest {
    private val stats = GateStats()

    private fun frames(count: Int, startNs: Long, stepNs: Long, gpuMs: (Int) -> Double) {
        for (i in 0 until count) stats.record(startNs + i * stepNs, gpuMs(i))
    }

    @Test fun formatsTheLogLine() {
        frames(100, 0, 10_000_000L) { (it + 1).toDouble() }
        assertEquals(
            "gate id=3 preview=true visible=false frames=100 fps1s=100 gpu_p50_ms=50.00 gpu_p95_ms=95.00 gpu_max_ms=100.00 load=40",
            stats.line(3, preview = true, visible = false, nowNs = 1_000_000_000L, load = 40),
        )
    }

    @Test fun fpsCountsOnlyTheLastSecond() {
        frames(120, 0, 16_666_667L) { 1.0 }
        assertEquals(60, stats.fps1s(nowNs = 2_000_000_000L - 1))
        assertEquals(0, stats.fps1s(nowNs = 10_000_000_000L))
    }

    @Test fun resetClearsPercentilesButKeepsTheTotal() {
        frames(10, 0, 1_000_000L) { 9.0 }
        stats.reset()
        frames(2, 20_000_000L, 1_000_000L) { 2.0 }
        assertEquals(12, stats.frames)
        assertEquals(
            "gate id=1 preview=false visible=true frames=12 fps1s=12 gpu_p50_ms=2.00 gpu_p95_ms=2.00 gpu_max_ms=2.00 load=0",
            stats.line(1, preview = false, visible = true, nowNs = 30_000_000L, load = 0),
        )
    }

    @Test fun unknownGpuTimeCountsTheFrameOnly() {
        stats.record(0, -1.0)
        assertEquals(1, stats.frames)
        assertEquals(
            "gate id=1 preview=false visible=true frames=1 fps1s=1 gpu_p50_ms=0.00 gpu_p95_ms=0.00 gpu_max_ms=0.00 load=0",
            stats.line(1, preview = false, visible = true, nowNs = 1_000L, load = 0),
        )
    }
}
