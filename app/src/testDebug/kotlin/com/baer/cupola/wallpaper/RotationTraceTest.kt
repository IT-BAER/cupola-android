package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationTraceTest {
    private fun at(t: Double) = checkNotNull(RotationTrace.sample(t)) { "no sample at $t" }

    @Test fun durationIsSixtySeconds() {
        assertEquals(60.0, RotationTrace.DURATION_SECONDS, 0.0)
        assertNotNull(RotationTrace.sample(0.0))
        assertNotNull(RotationTrace.sample(59.99))
        assertNull(RotationTrace.sample(60.01))
        assertNull(RotationTrace.sample(-0.01))
    }

    @Test fun continuousAtSixtyHertz() {
        var prev = at(0.0)
        var t = 1.0 / 60
        while (t <= 60.0) {
            val cur = at(t)
            assertTrue("yaw jump at $t", Math.toDegrees(Math.abs(cur.yawRad - prev.yawRad)) < MAX_STEP_DEG)
            assertTrue("pitch jump at $t", Math.toDegrees(Math.abs(cur.pitchRad - prev.pitchRad)) < MAX_STEP_DEG)
            prev = cur
            t += 1.0 / 60
        }
    }

    @Test fun staysInRangeAndPassesTheClamp() {
        val samples = (0..6000).map { at(it / 100.0) }
        val yaw = samples.map { Math.toDegrees(it.yawRad) }
        val pitch = samples.map { Math.toDegrees(it.pitchRad) }
        assertTrue(yaw.all { it in -180.0..180.0 } && pitch.all { it in -80.0..80.0 })
        assertTrue(yaw.max() > 25.0 && yaw.min() < -25.0)
        assertTrue(pitch.max() > 20.0 && pitch.min() < -20.0)
    }

    @Test fun holdsTwoSecondsWithoutMovement() {
        assertEquals(at(6.0), at(8.0))
        assertEquals(at(6.0), at(7.0))
        assertEquals(at(37.0), at(39.0))
    }

    @Test fun containsAFastFlick() {
        val peak = (0 until 6000).maxOf { i ->
            Math.toDegrees(Math.abs(at((i + 1) / 100.0).yawRad - at(i / 100.0).yawRad)) * 100
        }
        assertTrue("peak $peak deg/s", peak > 150.0)
    }

    private companion object {
        const val MAX_STEP_DEG = 6.0
    }
}
