package com.baer.cupola.core.render.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class LookAroundControllerTest {
    private var now = 0L
    private val frameNs = 16_666_667L
    private val c = LookAroundController { now }

    private fun deg(d: Double) = Math.toRadians(d)

    private fun frame(): LookAroundOutput {
        now += frameNs
        return c.update()
    }

    private fun run(seconds: Double): LookAroundOutput {
        var o = frame()
        repeat((seconds * 60).toInt() - 1) { o = frame() }
        return o
    }

    private fun start(strength: Double = 1.0) {
        c.strength = strength
        c.onVisible()
        c.setOrientation(0.0, 0.0)
        frame()
        run(1.1) // held still once: the look-around is armed
    }

    @Test fun stateLineReportsArmingAfterTheFirstHold() {
        c.onVisible()
        c.setOrientation(0.0, 0.0)
        frame()
        assertTrue(c.stateLine(), c.stateLine().startsWith("armed=false ref=true still_ms="))
        run(1.1)
        assertTrue(c.stateLine(), c.stateLine().startsWith("armed=true ref=true still_ms=1"))
    }

    @Test fun pickUpBeforeTheFirstHoldDoesNotSwingTheView() {
        c.onVisible()
        c.setOrientation(0.0, 0.0)
        frame()
        now += 300_000_000L
        c.setOrientation(deg(40.0), deg(20.0)) // picked up: 40 deg in 0.3 s
        val o = run(0.6)
        assertEquals(0.0, o.yawDeg, 1e-9)
        assertEquals(0.0, o.pitchDeg, 1e-9)
        run(1.1) // held still in the new pose: armed here
        c.setOrientation(deg(52.0), deg(20.0))
        assertTrue("tilt after the hold moves the view", run(0.95).yawDeg > 5.0)
    }

    @Test fun restWithoutInput() {
        start()
        val o = run(2.0)
        assertEquals(0.0, o.yawDeg, 1e-9)
        assertEquals(0.0, o.pitchDeg, 1e-9)
        assertFalse(o.isMoving)
    }

    @Test fun offsetIsClampedToTwentyDegreesTimesStrength() {
        start(strength = 0.5)
        c.setOrientation(deg(90.0), deg(-90.0))
        // 0.95 s: the 0.35 s output filter is at 93 % and the spring has not started (1 s still).
        val o = run(0.95)
        assertTrue("yaw ${o.yawDeg}", o.yawDeg <= 10.0 && o.yawDeg > 9.0)
        assertTrue("pitch ${o.pitchDeg}", o.pitchDeg >= -10.0 && o.pitchDeg < -9.0)
    }

    @Test fun offsetInsideTheDeadBandDoesNotMoveTheView() {
        start()
        c.setOrientation(deg(1.5), deg(-1.0))
        val o = run(0.95)
        assertEquals(0.0, o.yawDeg, 1e-9)
        assertEquals(0.0, o.pitchDeg, 1e-9)
    }

    @Test fun onsetBeyondTheDeadBandIsEasedNotKinked() {
        // Over the 3 deg ease after the 3 deg band the view grows with the square of the excess over 6: 0.5 -> 1/24,
        // 2 -> 2/3, then linear: 3 -> 1.5 deg (soft-clamped 1.497); each read at 93 % of the 0.35 s filter after 0.95 s.
        for ((tilt, expected) in listOf(3.5 to 0.0417, 5.0 to 0.667, 6.0 to 1.497)) {
            start()
            c.setOrientation(0.0, 0.0) // fresh sample, so the tilt below is a fast step, not a slow drift
            frame()
            c.setOrientation(deg(tilt), 0.0)
            assertEquals("tilt $tilt", expected * 0.934, run(0.95).yawDeg, 0.06)
        }
    }

    @Test fun offsetBeyondTheEaseGrowsNearlyLinearly() {
        start()
        c.setOrientation(deg(12.0), 0.0)
        // 12 deg minus the 3 deg band and half the 3 deg ease = 7.5 deg, soft-clamped 7.17, at 93 % of the filter.
        assertEquals(7.168 * 0.934, run(0.95).yawDeg, 0.06)
    }

    @Test fun yawWrapUsesShortestPath() {
        c.onVisible()
        c.setOrientation(deg(179.0), 0.0)
        frame()
        run(1.1)
        c.setOrientation(deg(-169.0), 0.0)
        // 12 deg the short way round, minus the band and half the ease = 7.5 deg, soft-clamped to 7.17 deg.
        assertEquals(7.168 * 0.934, run(0.95).yawDeg, 0.1)
    }

    @Test fun stepResponseReaches63PercentAfterOneTimeConstant() {
        start()
        c.setOrientation(deg(12.0), 0.0)
        now += 350_000_000L
        assertEquals(7.168 * 0.632, c.update().yawDeg, 0.13)
    }

    @Test fun springReturnsToRestWithinTwoSecondsAfterOneSecondStill() {
        start()
        c.setOrientation(deg(15.0), deg(-8.0))
        assertTrue("held while not yet still for 1 s", run(0.9).yawDeg > 8.0) // band and ease leave 12.5 deg, clamp 10.0, 92 % of the filter
        val o = run(5.0)
        assertEquals(0.0, o.yawDeg, 0.1)
        assertEquals(0.0, o.pitchDeg, 0.1)
        assertFalse("still once the 0.5 s moving hold ends", run(0.5).isMoving)
    }

    @Test fun motionDuringSpringCancelsReturn() {
        start()
        c.setOrientation(deg(15.0), 0.0)
        run(1.5)
        c.setOrientation(deg(15.0), 0.0) // fresh sample, so the nudge below is a fast step (60 deg/s), not a slow drift
        frame()
        c.setOrientation(deg(15.0 + 1.0), 0.0)
        run(0.1)
        c.setOrientation(deg(15.0 + 6.0), 0.0)
        val o = run(0.5)
        assertTrue("yaw ${o.yawDeg}", o.yawDeg > 8.0) // 17 deg offset minus band and ease = 12.5, clamp 10.0, filter from 6.3
    }

    @Test fun idleFloatStaysWithinTwoDegreesAndIsContinuous() {
        start()
        c.idleFloat = true
        var maxAbs = 0.0
        var maxStep = 0.0
        var prev = c.update().let { it.yawDeg to it.pitchDeg }
        repeat(10 * 60 * 60) {
            val o = frame()
            maxAbs = max(maxAbs, max(abs(o.yawDeg), abs(o.pitchDeg)))
            maxStep = max(maxStep, max(abs(o.yawDeg - prev.first), abs(o.pitchDeg - prev.second)))
            assertFalse(o.isMoving)
            prev = o.yawDeg to o.pitchDeg
        }
        assertTrue("max $maxAbs", maxAbs <= 1.0 && maxAbs > 0.6)
        assertTrue("step $maxStep", maxStep < 0.05)
    }

    @Test fun launcherPanMapsOffsetToPlusMinusFourDegrees() {
        start()
        c.xOffset = 1.0
        assertEquals(4.0, run(3.0).yawDeg, 0.01)
        c.xOffset = 0.0
        assertEquals(-4.0, run(3.0).yawDeg, 0.01)
        c.xOffset = 0.5
        assertEquals(0.0, run(3.0).yawDeg, 0.01)
    }

    @Test fun reducedMotionPinsRestAndNeverMoves() {
        start()
        c.reducedMotion = true
        c.idleFloat = true
        c.xOffset = 1.0
        c.setOrientation(deg(30.0), deg(30.0))
        repeat(300) {
            val o = frame()
            assertEquals(0.0, o.yawDeg, 0.0)
            assertEquals(0.0, o.pitchDeg, 0.0)
            assertFalse(o.isMoving)
        }
    }

    @Test fun isMovingTransitions() {
        start()
        assertFalse(frame().isMoving)
        c.setOrientation(deg(10.0), 0.0)
        assertTrue(frame().isMoving)
        var seenStill = false
        var o = frame()
        repeat(60 * 6) {
            o = frame()
            if (!o.isMoving) seenStill = true
        }
        assertTrue(seenStill)
        assertFalse(o.isMoving)
    }

    @Test fun bunchedSensorDeliveryIsNotMotion() {
        c.onVisible()
        val sampleNs = 20_000_000L
        val base = now
        var nextFrame = now + 66_666_667L
        var movingAfterSettle = 0
        for (i in 1..300) {
            // Phone flat: yaw noise of +-0.3 deg is 0.37 deg/s on the view sphere at 20 ms steps.
            // Every 25th sample is delivered 19 ms late, right before the next one (a 1 ms delivery gap).
            val deliveryNs = base + i * sampleNs + if (i % 25 == 0) 19_000_000L else 0L
            while (nextFrame <= deliveryNs) {
                now = nextFrame
                val o = c.update()
                if (now - base > 3_000_000_000L && o.isMoving) movingAfterSettle++
                nextFrame += 66_666_667L
            }
            now = deliveryNs
            c.setOrientation(deg(if (i % 2 == 0) 0.3 else -0.3), deg(-89.3), base + i * sampleNs)
        }
        assertEquals(0, movingAfterSettle)
    }

    /**
     * Frames follow the flag as the scheduler does (60 fps moving, 15 fps still). A slow drift whose view speed
     * hovers at the moving threshold must not alternate 60 and 15 fps frame by frame (A35 trace: 16/66 ms judder).
     */
    @Test fun slowDriftAtTheThresholdDoesNotFlap() {
        start()
        var moving = false
        var restarts = 0
        var yaw = 0.0
        val end = now + 6_000_000_000L
        while (now < end) {
            val dt = if (moving) frameNs else 66_666_667L
            now += dt
            yaw += 0.21 * (1.0 + 0.3 * kotlin.math.sin(now / 1e8)) * dt / 1e9 // 0.21 deg/s +-30 %
            c.setOrientation(deg(yaw), 0.0)
            val o = c.update()
            if (o.isMoving && !moving) restarts++
            moving = o.isMoving
        }
        assertTrue("restarts $restarts", restarts <= 2)
    }

    @Test fun isMovingWhileSpringUnsettled() {
        start()
        c.setOrientation(deg(15.0), 0.0)
        run(1.5)
        assertTrue(frame().isMoving)
    }

    /** Hand tremor (peaks of about 4 deg/s measured on the Pixel 7 Pro) is still: spring and arming must work in the hand. */
    @Test fun handTremorCountsAsStill() {
        start()
        c.setOrientation(deg(15.0), 0.0)
        var sign = 1.0
        val end = now + 2_500_000_000L
        while (now < end) {
            now += 10_000_000L
            sign = -sign
            c.setOrientation(deg(15.0 + sign * 0.0225), 0.0) // 0.045 deg per 10 ms = 4.5 deg/s
            c.update()
        }
        assertTrue("yaw ${c.update().yawDeg}", c.update().yawDeg < 5.0)
    }

    /** A hand that keeps drifting slowly (1.5 deg/s) is still: the view springs back instead of waiting for rest. */
    @Test fun slowDriftStillSpringsBackToRest() {
        start()
        c.setOrientation(deg(15.0), 0.0)
        assertTrue(run(0.5).yawDeg > 6.0) // band and ease leave 10.5 deg, clamp 9.6, 76 % of the filter
        var yaw = 15.0
        repeat(240) {
            yaw += 1.5 / 60.0
            c.setOrientation(deg(yaw), 0.0)
            frame()
        }
        assertTrue("yaw ${c.update().yawDeg}", abs(c.update().yawDeg) < 3.0)
    }

    @Test fun referenceIsRecapturedOnVisible() {
        start()
        c.setOrientation(deg(15.0), 0.0)
        run(0.5)
        c.onVisible()
        c.setOrientation(deg(15.0), 0.0)
        assertEquals(0.0, run(2.0).yawDeg, 0.05)
    }
}
