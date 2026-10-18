package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class TapDetectorTest {
    private val events = mutableListOf<String>()
    private val listener = object : TapListener {
        override fun onSingleTap(x: Float, y: Float) { events += "single $x,$y" }
        override fun onDoubleTap(x: Float, y: Float) { events += "double $x,$y" }
    }
    private val detector = TapDetector(timeoutMs = 300, slopPx = 100f, listener = listener)

    @Test fun singleFiresOnlyAfterTimeout() {
        detector.onTap(10f, 20f, 1000)
        detector.tick(1300)
        assertEquals(emptyList<String>(), events)
        detector.tick(1301)
        assertEquals(listOf("single 10.0,20.0"), events)
    }

    @Test fun singleFiresOnce() {
        detector.onTap(1f, 1f, 0)
        detector.tick(301)
        detector.tick(500)
        assertEquals(1, events.size)
    }

    @Test fun secondTapWithinTimeoutAndSlopIsDouble() {
        detector.onTap(10f, 10f, 0)
        detector.onTap(60f, 10f, 200)
        detector.tick(1000)
        assertEquals(listOf("double 60.0,10.0"), events)
    }

    @Test fun secondTapExactlyAtTimeoutIsDouble() {
        detector.onTap(0f, 0f, 0)
        detector.onTap(0f, 0f, 300)
        assertEquals(listOf("double 0.0,0.0"), events)
    }

    @Test fun secondTapOneMsAfterTimeoutIsTwoSingles() {
        detector.onTap(0f, 0f, 0)
        detector.onTap(0f, 0f, 301)
        detector.tick(700)
        assertEquals(listOf("single 0.0,0.0", "single 0.0,0.0"), events)
    }

    @Test fun slowSecondTapAfterTickIsTwoSingles() {
        detector.onTap(5f, 5f, 0)
        detector.tick(301)
        detector.onTap(5f, 5f, 900)
        detector.tick(1300)
        assertEquals(listOf("single 5.0,5.0", "single 5.0,5.0"), events)
    }

    @Test fun secondTapOutsideSlopIsTwoSingles() {
        detector.onTap(0f, 0f, 0)
        detector.onTap(101f, 0f, 100)
        detector.tick(500)
        assertEquals(listOf("single 0.0,0.0", "single 101.0,0.0"), events)
    }

    @Test fun secondTapExactlyAtSlopIsDouble() {
        detector.onTap(0f, 0f, 0)
        detector.onTap(60f, 80f, 100)
        assertEquals(listOf("double 60.0,80.0"), events)
    }

    @Test fun thirdTapStartsNewSequenceAfterDouble() {
        detector.onTap(0f, 0f, 0)
        detector.onTap(0f, 0f, 100)
        detector.onTap(0f, 0f, 200)
        detector.tick(600)
        assertEquals(listOf("double 0.0,0.0", "single 0.0,0.0"), events)
    }

    @Test fun deadlineReportsPendingTap() {
        assertEquals(null, detector.deadlineMs)
        detector.onTap(0f, 0f, 1000)
        assertEquals(1301L, detector.deadlineMs)
        detector.tick(1301)
        assertEquals(null, detector.deadlineMs)
    }
}
