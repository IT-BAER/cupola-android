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

    /** Still-mode frames at 15 fps whose GPU time, scaled to the 60 fps interval, is [ms60]. */
    private fun still(n: Int, ms60: Double) = repeat(n) {
        now += 66_666_667L
        g.onFrame(ms60 * 4, intervalMs = 1000.0 / 15)
    }

    private fun seconds(s: Double) { now += (s * 1e9).toLong() }

    @Test fun startsAtDefaultStep() = assertEquals(3, g.step)

    @Test fun rejectsBadDefaultStep() {
        assertThrows(IllegalArgumentException::class.java) { QualityGovernor(3, 3, clockNs = { 0L }) }
    }

    @Test fun stillStepsDownWhenP95ExceedsBudget() {
        still(119, 8.0)
        assertEquals(3, g.step)
        still(1, 13.0) // 1 slow of 120 does not move p95
        assertEquals(3, g.step)
        still(120, 8.0)
        still(10, 13.0) // 10 of 120 slow = p95 over budget
        assertEquals(2, g.step)
    }

    /**
     * Two A35 motion traces: 2.3-3.7 % of frames miss a vsync at render scale 0.6 and 3.6 % at 0.4, clustered
     * after each motion start (GPU clock ramp). A lower step does not lower that rate, so it must not step down.
     */
    @Test fun recordedA35MotionDoesNotStepDown() {
        for (name in listOf("a35-motion-trace-a.csv", "a35-motion-trace-b.csv")) {
            var clock = 0L
            val changes = mutableListOf<Pair<Int, Int>>()
            val replay = QualityGovernor(steps = 3, defaultStep = 2, clockNs = { clock }) { from, to -> changes += from to to }
            replay.onThermalHeadroom(0.55f) // measured 0.51-0.57 during both traces
            javaClass.getResourceAsStream("/governor/$name")!!.bufferedReader().useLines { lines ->
                lines.filterNot { it.startsWith("#") }.forEach { line ->
                    val (frameUs, moving, gpuUs) = line.split(',').map { it.toLong() }
                    clock = frameUs * 1000
                    if (gpuUs >= 0) replay.onFrame(gpuUs / 1000.0, if (moving == 1L) 1000.0 / 60 else 1000.0 / 15)
                }
            }
            assertEquals(name, emptyList<Pair<Int, Int>>(), changes)
        }
    }

    @Test fun movingStepsDownWhenMoreThanTenPercentOfFourSecondsMissAVsync() {
        frames(240, 8.0, dtMs = 16.667)
        frames(24, 8.0, dtMs = 33.333)
        assertEquals(3, g.step)
        frames(1, 8.0, dtMs = 33.333)
        assertEquals(2, g.step)
    }

    @Test fun movingCountsEverySkippedVsync() {
        frames(240, 8.0, dtMs = 16.667)
        frames(4, 8.0, dtMs = 16.667 * 8) // 7 intervals skipped per gap
        assertEquals(2, g.step)
    }

    /** 60 fps on a 90 Hz panel: gaps alternate one and two refreshes. */
    @Test fun moving90HzCadenceIsNotAMiss() {
        repeat(300) { frames(1, 8.0, dtMs = 11.111); frames(1, 8.0, dtMs = 22.222) }
        assertEquals(3, g.step)
    }

    @Test fun pauseBetweenMovingFramesIsNotAMiss() {
        frames(120, 8.0, dtMs = 16.667)
        seconds(3.0)
        frames(120, 8.0, dtMs = 16.667)
        assertEquals(3, g.step)
    }

    /** A35 home screen at 15 fps: the GPU clock drops to its minimum and a frame takes about 21.7 ms of its 66.7 ms. */
    @Test fun stillFramesAtALowGpuClockDoNotStepDown() {
        repeat(600) {
            now += 66_666_667L
            g.onFrame(21.7, intervalMs = 1000.0 / 15)
        }
        assertEquals(3, g.step)
    }

    /** A35 motion at 60 fps: the GPU clock ramps 221 -> 650 MHz, frames take up to 20 ms, yet every vsync is met. */
    @Test fun movingFramesThatMeetEveryVsyncDoNotStepDown() {
        frames(600, 20.0, dtMs = 16.667)
        assertEquals(3, g.step)
    }

    @Test fun stillFramesThatFillTheirIntervalStepDown() {
        repeat(130) {
            now += 66_666_667L
            g.onFrame(55.0, intervalMs = 1000.0 / 15)
        }
        assertEquals(2, g.step)
    }

    @Test fun fewSlowStillFramesDoNotStepDown() {
        still(114, 8.0)
        still(6, 20.0)
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
        frames(240, 5.0, dtMs = 10.0) // motion window full again, calm starts
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
        frames(241 + 1100, 5.0, dtMs = 10.0)
        assertEquals(3, g.step)
    }

    @Test fun midHeadroomBlocksStepUp() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.7f)
        frames(3000, 5.0, dtMs = 10.0)
        assertEquals(2, g.step)
    }

    @Test fun stillP95AboveThreeQuartersOfBudgetBlocksStepUp() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.3f)
        still(3000, 10.0)
        assertEquals(2, g.step)
    }

    @Test fun interruptedCalmRestartsTheTenSecondTimer() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.3f)
        frames(240 + 800, 5.0, dtMs = 10.0)
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
        repeat(1300) {
            now += 10_000_000L
            reported.onFrame(5.0)
        }
        assertEquals(listOf(3 to 2, 2 to 3), changes)
    }

    @Test fun movingMissesBlockStepUp() {
        g.onThermalHeadroom(0.9f)
        frames(1, 5.0)
        g.onThermalHeadroom(0.3f)
        repeat(100) { frames(57, 5.0, dtMs = 16.667); frames(3, 5.0, dtMs = 33.333) } // 5 %
        assertEquals(2, g.step)
    }

    @Test fun neverAboveDefaultStep() {
        frames(5000, 1.0, dtMs = 10.0)
        assertEquals(3, g.step)
    }
}
