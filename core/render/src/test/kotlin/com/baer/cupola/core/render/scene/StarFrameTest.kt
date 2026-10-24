package com.baer.cupola.core.render.scene

import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.TimeScales
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

class StarFrameTest {
    /** Unit J2000 vector of right ascension / declination in degrees. */
    private fun radec(raDeg: Double, decDeg: Double): Vector3 {
        val ra = Math.toRadians(raDeg)
        val dec = Math.toRadians(decDeg)
        return Vector3(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
    }

    private fun assertVec(expected: Vector3, actual: Vector3, tol: Double) {
        assertEquals("x", expected.x, actual.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
        assertEquals("z", expected.z, actual.z, tol)
    }

    /** IAU galactic frame: the centre (l = 0, b = 0) and the north pole (b = 90) from their J2000 coordinates, via ECEF. */
    @Test fun galacticCentreAndPoleLandOnTheGalacticAxes() {
        for (instant in listOf(Instant.parse("2026-10-25T09:50:00Z"), Instant.parse("2030-03-01T00:00:00Z"))) {
            val jd = TimeScales.julianDateUt1(instant)
            val m = StarFrame.galacticFromEcef(instant)
            val centre = Frames.temeToEcef(radec(266.40510, -28.936175), jd)
            val pole = Frames.temeToEcef(radec(192.85948, 27.12825), jd)
            assertVec(Vector3(1.0, 0.0, 0.0), m * centre, 1e-5) // 2 arcsec: published coordinates vs the matrix
            assertVec(Vector3(0.0, 0.0, 1.0), m * pole, 1e-5)
        }
    }

    @Test fun rotatesWithTheEarthOncePerSiderealDay() {
        val t0 = Instant.parse("2026-10-25T00:00:00Z")
        val v = Vector3(1.0, 0.0, 0.0)
        val a = StarFrame.galacticFromEcef(t0) * v
        val b = StarFrame.galacticFromEcef(t0.plusMillis(86_164_091L)) * v
        assertVec(a, b, 1e-4)
    }
}
