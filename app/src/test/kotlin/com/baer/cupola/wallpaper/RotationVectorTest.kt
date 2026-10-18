package com.baer.cupola.wallpaper

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationVectorTest {
    private data class Q(val x: Double, val y: Double, val z: Double, val w: Double) {
        operator fun times(o: Q) = Q(
            w * o.x + x * o.w + y * o.z - z * o.y,
            w * o.y - x * o.z + y * o.w + z * o.x,
            w * o.z + x * o.y - y * o.x + z * o.w,
            w * o.w - x * o.x - y * o.y - z * o.z,
        )
    }

    private fun aboutX(rad: Double) = Q(sin(rad / 2), 0.0, 0.0, cos(rad / 2))
    private fun aboutZ(rad: Double) = Q(0.0, 0.0, sin(rad / 2), cos(rad / 2))

    // Flat on a table is identity; raising the top edge by 90 deg about the device X axis makes it portrait upright.
    private val upright = aboutX(PI / 2)

    private fun orient(q: Q) = RotationVector.toYawPitch(q.x, q.y, q.z, q.w)

    private fun assertAngle(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    @Test fun identityIsFlatFaceUpLookingDown() {
        val o = orient(Q(0.0, 0.0, 0.0, 1.0))
        assertAngle(0.0, o.yawRad)
        assertAngle(-PI / 2, o.pitchRad)
    }

    @Test fun uprightPortraitIsZeroZero() {
        val o = orient(upright)
        assertAngle(0.0, o.yawRad)
        assertAngle(0.0, o.pitchRad)
    }

    @Test fun turningRightByNinetyDegreesGivesPositiveYaw() {
        // World Z is up; a clockwise turn seen from above is a negative rotation about Z.
        val o = orient(aboutZ(-PI / 2) * upright)
        assertAngle(PI / 2, o.yawRad)
        assertAngle(0.0, o.pitchRad)
    }

    @Test fun turningLeftGivesNegativeYaw() {
        val o = orient(aboutZ(PI / 4) * upright)
        assertAngle(-PI / 4, o.yawRad)
    }

    @Test fun tiltingThirtyDegreesGivesSignedPitch() {
        val down = orient(aboutX(PI / 2 - PI / 6))
        assertAngle(0.0, down.yawRad)
        assertAngle(-PI / 6, down.pitchRad)
        val up = orient(aboutX(PI / 2 + PI / 6))
        assertAngle(PI / 6, up.pitchRad)
    }

    @Test fun yawAndPitchComposeIndependently() {
        val o = orient(aboutZ(-PI / 3) * aboutX(PI / 2 + PI / 6))
        assertAngle(PI / 3, o.yawRad)
        assertAngle(PI / 6, o.pitchRad)
    }

    @Test fun nearVerticalKeepsYawFiniteAndContinuous() {
        val flatYaw = PI / 5
        val base = aboutZ(-flatYaw)
        for (deltaDeg in listOf(0.0, 1e-5, 0.001, 0.5)) {
            val d = Math.toRadians(deltaDeg)
            val down = orient(base * aboutX(d))
            assertTrue(down.yawRad.isFinite())
            assertAngle(-PI / 2 + d, down.pitchRad)
            assertEquals("yaw at ${deltaDeg}deg", flatYaw, down.yawRad, 1e-2)
            val up = orient(base * aboutX(PI - d))
            assertTrue(up.yawRad.isFinite())
            assertAngle(PI / 2 - d, up.pitchRad)
            assertEquals("yaw at ${deltaDeg}deg flipped", flatYaw, up.yawRad, 1e-2)
        }
    }

    @Test fun screenFacingFloorIsPlusNinetyPitch() {
        val o = orient(aboutX(PI))
        assertAngle(0.0, o.yawRad)
        assertAngle(PI / 2, o.pitchRad)
    }

    @Test fun missingWIsRecoveredFromUnitNorm() {
        val s = sin(PI / 4).toFloat()
        val o = RotationVector.fromSensorValues(floatArrayOf(s, 0f, 0f))
        assertEquals(0.0, o.pitchRad, 1e-6)
    }
}
