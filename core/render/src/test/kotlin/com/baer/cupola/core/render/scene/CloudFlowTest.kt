package com.baer.cupola.core.render.scene

import java.time.Instant
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudFlowTest {
    private val periodS = CloudFlow.PERIOD_HOURS * 3600.0

    private fun at(seconds: Long) = CloudFlow.of(Instant.ofEpochSecond(seconds))

    @Test fun phasesAreHalfAPeriodApart() {
        for (s in listOf(1_761_000_000L, 1_761_012_345L, 1_790_000_001L)) {
            val f = at(s)
            val a = f.phaseA - floor(f.phaseA)
            val b = f.phaseB - floor(f.phaseB)
            assertEquals("t=$s", (a + 0.5) % 1.0, b.toDouble(), 1e-3)
        }
    }

    /** Each phase restarts (new cycle) only where its fraction wraps, i.e. where the shader gives it weight 0. */
    @Test fun cycleChangesOnlyWhereItsFractionWraps() {
        // Window around a wrap of the cycle counter (every 1024 cycles), so that wrap is covered too.
        val wrapS = (floor(1_761_000_000.0 / (periodS * CloudFlow.CYCLES)) + 1) * periodS * CloudFlow.CYCLES
        var prev = at(wrapS.toLong() - 2 * 86_400)
        var restartsA = 0
        var restartsB = 0
        for (s in (wrapS.toLong() - 2 * 86_400 + 60)..(wrapS.toLong() + 2 * 86_400) step 60) {
            val f = at(s)
            for ((p, c) in listOf(prev.phaseA to f.phaseA, prev.phaseB to f.phaseB)) {
                val fp = p - floor(p)
                val fc = c - floor(c)
                if (floor(p) != floor(c)) {
                    assertTrue("restart at fraction $fp -> $fc (t=$s)", fp > 0.99f && fc < 0.01f)
                } else {
                    assertEquals("smooth step at t=$s", 60.0 / periodS, (fc - fp).toDouble(), 1e-4)
                }
            }
            if (floor(prev.phaseA) != floor(f.phaseA)) restartsA++
            if (floor(prev.phaseB) != floor(f.phaseB)) restartsB++
            prev = f
        }
        assertEquals(16, restartsA) // 4 days / 6 h
        assertEquals(16, restartsB)
    }

    @Test fun valuesStaySmallForFloatPrecision() {
        for (s in 1_000_000_000L..2_000_000_000L step 7_777_777L) {
            val f = at(s)
            assertTrue(f.phaseA < CloudFlow.CYCLES + 1 && f.phaseB < CloudFlow.CYCLES + 1 && f.days < CloudFlow.DAYS_WRAP + 1)
        }
    }

    @Test fun daysAdvanceOnePerDay() {
        val f0 = at(1_761_000_000L)
        val f1 = at(1_761_000_000L + 43_200)
        assertEquals(0.5, (f1.days - f0.days).toDouble(), 1e-3)
    }
}
