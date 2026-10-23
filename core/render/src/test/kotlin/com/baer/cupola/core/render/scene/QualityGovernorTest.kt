package com.baer.cupola.core.render.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class QualityGovernorTest {
    private var now = 0L
    private val g = QualityGovernor(steps = 5, defaultStep = 3, clockNs = { now })

    private fun frames(n: Int, ms: Double, dtMs: Double = 16.0) = repeat(n) {
        now += (dtMs * 1e6).toLong()
        g.onFrame(ms)
    }

    private fun seconds(s: Double) { now += (s * 1e9).toLong() }

    @Test fun startsAtDefaultStep() = assertEquals(3, g.step)

    @Test fun rejectsBadDefaultStep() {
        assertThrows(IllegalArgumentException::class.java) { QualityGovernor(3, 3, clockNs = { 0L }) }
    }

    @Test fun stepsDownWhenP95ExceedsBudget() {
        frames(119, 8.0)
        assertEquals(3, g.step)
        frames(1, 13.0) // 1 slow of 120 does not move p95
        assertEquals(3, g.step)
        frames(120, 8.0)
        frames(10, 13.0) // 10 of 120 slow = p95 over budget
        assertEquals(2, g.step)
    }

    /** A35 home screen at 15 fps: the GPU clock drops to its minimum and a frame takes about 21.7 ms of its 66.7 ms. */
    @Test fun stillFramesAtALowGpuClockDoNotStepDown() {
        repeat(600) {
            now += 66_666_667L
            g.onFrame(21.7, intervalMs = 1000.0 / 15)
        }
        assertEquals(3, g.step)
    }

    @Test fun stillFramesThatFillTheirIntervalStepDown() {
        repeat(130) {
            now += 66_666_667L
            g.onFrame(55.0, intervalMs = 1000.0 / 15)
        }
        assertEquals(2, g.step)
    }

    @Test fun fewSlowFramesDoNotStepDown() {
        frames(114, 8.0)
        frames(6, 20.0)
        assertEquals(3, g.step)
    }

    @Test fun stepsDownOnHighHeadroomWithoutFrames() {
        g.onThermalHeadroom(0.86f)
        frames(1, 5.0)
        assertEquals(2, g.step)
    }

    @Test fun headroomAtThresholdDoesNotStepDown() {
        g.onThermalHeadroom(0.85f)
        frames(200, 5.0)
        assertEquals(3, g.step)
    }

    @Test fun hysteresisLimitsToOneChangePerFiveSeconds() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        assertEquals(2, g.step)
        frames(200, 5.0, dtMs = 20.0) // 4 s
        assertEquals(2, g.step)
        frames(60, 5.0, dtMs = 20.0) // past 5 s
        assertEquals(1, g.step)
    }

    @Test fun neverBelowZero() {
        g.onThermalHeadroom(1.0f)
        repeat(10) { seconds(6.0); frames(1, 5.0) }
        assertEquals(0, g.step)
    }

    @Test fun stepsUpAfterTenCalmSecondsWithLowHeadroom() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.5f)
        frames(120, 5.0, dtMs = 10.0) // window full again, calm starts
        frames(900, 5.0, dtMs = 10.0) // 9 s calm
        assertEquals(2, g.step)
        frames(150, 5.0, dtMs = 10.0)
        assertEquals(3, g.step)
    }

    @Test fun nanHeadroomCountsAsCalmAndNeverStepsDown() {
        g.onThermalHeadroom(Float.NaN)
        frames(2000, 5.0)
        assertEquals(3, g.step)
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(Float.NaN)
        frames(121 + 1100, 5.0, dtMs = 10.0)
        assertEquals(3, g.step)
    }

    @Test fun midHeadroomBlocksStepUp() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.7f)
        frames(3000, 5.0, dtMs = 10.0)
        assertEquals(2, g.step)
    }

    @Test fun p95AboveThreeQuartersOfBudgetBlocksStepUp() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.3f)
        frames(3000, 10.0, dtMs = 10.0)
        assertEquals(2, g.step)
    }

    @Test fun interruptedCalmRestartsTheTenSecondTimer() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.3f)
        frames(120 + 800, 5.0, dtMs = 10.0)
        g.onThermalHeadroom(0.7f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.3f)
        frames(800, 5.0, dtMs = 10.0)
        assertEquals(2, g.step)
        frames(300, 5.0, dtMs = 10.0)
        assertEquals(3, g.step)
    }

    @Test fun reportsEveryStepChange() {
        val changes = mutableListOf<Pair<Int, Int>>()
        val reported = QualityGovernor(steps = 5, defaultStep = 3, clockNs = { now }) { from, to -> changes += from to to }
        reported.onThermalHeadroom(0.9f)
        now += 16_000_000L
        reported.onFrame(5.0)
        reported.onThermalHeadroom(0.3f)
        repeat(1200) {
            now += 10_000_000L
            reported.onFrame(5.0)
        }
        assertEquals(listOf(3 to 2, 2 to 3), changes)
    }

    @Test fun neverAboveDefaultStep() {
        frames(5000, 1.0, dtMs = 10.0)
        assertEquals(3, g.step)
    }
}
