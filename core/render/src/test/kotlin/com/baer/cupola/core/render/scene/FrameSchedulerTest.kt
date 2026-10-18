package com.baer.cupola.core.render.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSchedulerTest {
    private val vsync60 = 16_666_667L
    private val vsync120 = 8_333_333L

    private fun renderedTicks(s: FrameScheduler, vsync: Long, ticks: Int, startNs: Long = 0L): List<Int> =
        (0 until ticks).filter { s.shouldRender(startNs + it * vsync, vsync) }

    @Test fun hiddenIsZeroFpsAndNeverRenders() {
        val s = FrameScheduler()
        s.moving = true
        assertEquals(0, s.targetFps)
        assertTrue(renderedTicks(s, vsync60, 120).isEmpty())
    }

    @Test fun movingIs60AndStillIs15() {
        val s = FrameScheduler()
        s.setVisible(true)
        s.moving = true
        assertEquals(60, s.targetFps)
        s.moving = false
        assertEquals(15, s.targetFps)
    }

    @Test fun batterySaverRendersExactlyOneFramePerVisibleTransition() {
        val s = FrameScheduler()
        s.batterySaver = true
        s.moving = true
        s.setVisible(true)
        assertTrue(s.renderOnce)
        assertEquals(0, s.targetFps)
        assertEquals(listOf(0), renderedTicks(s, vsync60, 240))
        assertFalse(s.renderOnce)
        s.setVisible(false)
        s.setVisible(true)
        assertEquals(listOf(0), renderedTicks(s, vsync60, 240, 10_000_000_000L))
    }

    @Test fun hiddenThenBatterySaverDoesNotRenderWhileHidden() {
        val s = FrameScheduler()
        s.batterySaver = true
        s.setVisible(true)
        s.setVisible(false)
        assertTrue(renderedTicks(s, vsync60, 10).isEmpty())
    }

    @Test fun fifteenFpsPicksEveryEighthVsyncAt120Hz() {
        val s = FrameScheduler()
        s.setVisible(true)
        assertEquals((0 until 240 step 8).toList(), renderedTicks(s, vsync120, 240))
    }

    @Test fun fifteenFpsPicksEveryFourthVsyncAt60Hz() {
        val s = FrameScheduler()
        s.setVisible(true)
        assertEquals((0 until 120 step 4).toList(), renderedTicks(s, vsync60, 120))
    }

    @Test fun sixtyFpsPicksEverySecondVsyncAt120Hz() {
        val s = FrameScheduler()
        s.setVisible(true)
        s.moving = true
        assertEquals((0 until 240 step 2).toList(), renderedTicks(s, vsync120, 240))
    }

    @Test fun sixtyFpsOn60HzRendersEveryVsync() {
        val s = FrameScheduler()
        s.setVisible(true)
        s.moving = true
        assertEquals((0 until 60).toList(), renderedTicks(s, vsync60, 60))
    }

    @Test fun vsyncJitterDoesNotShiftDivisor() {
        val s = FrameScheduler()
        s.setVisible(true)
        val picked = (0 until 240).filter { s.shouldRender(it * vsync120 + ((it % 3) - 1) * 600_000L, vsync120) }
        assertEquals((0 until 240 step 8).toList(), picked)
    }

    @Test fun speedingUpFromStillRendersWithoutWaitingForOldDeadline() {
        val s = FrameScheduler()
        s.setVisible(true)
        assertTrue(s.shouldRender(0, vsync60))
        s.moving = true
        assertTrue(s.shouldRender(vsync60, vsync60))
    }
}
