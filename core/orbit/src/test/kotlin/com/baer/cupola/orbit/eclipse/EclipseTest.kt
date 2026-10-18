package com.baer.cupola.orbit.eclipse

import com.baer.cupola.orbit.astro.Sun
import com.baer.cupola.orbit.sgp4.Vector3
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EclipseTest {

    private val sun = Vector3(Sun.AU_KM, 0.0, 0.0)

    @Test
    fun deepUmbraIsDarkAndDaySideIsLit() {
        assertEquals(0.0, Eclipse.sunlitFraction(Vector3(-7000.0, 0.0, 0.0), sun), 0.0)
        assertEquals(1.0, Eclipse.sunlitFraction(Vector3(7000.0, 0.0, 0.0), sun), 0.0)
        assertEquals(1.0, Eclipse.sunlitFraction(Vector3(0.0, 0.0, 7000.0), sun), 0.0)
    }

    @Test
    fun radialSweepAcrossPenumbraIsMonotonic() {
        var previous = -1.0
        var partialSamples = 0
        var y = 0.0
        while (y <= 8000.0) {
            val f = Eclipse.sunlitFraction(Vector3(-7000.0, y, 0.0), sun)
            assertTrue("y $y: $f < $previous", f >= previous)
            assertTrue(f in 0.0..1.0)
            if (f > 0.0 && f < 1.0) partialSamples++
            previous = f
            y += 0.5
        }
        assertEquals(1.0, previous, 0.0)
        assertTrue("penumbra crossed with $partialSamples partial samples", partialSamples > 10)
    }

    @Test
    fun shadowFractionMatchesCylindricalModelAtBetaZero() {
        val r = Eclipse.EARTH_RADIUS_KM + 420.0
        val cylindrical = 2 * asin(Eclipse.EARTH_RADIUS_KM / r) / (2 * PI)
        val samples = 360_000
        var shadowed = 0
        for (i in 0 until samples) {
            val u = 2 * PI * i / samples
            if (Eclipse.sunlitFraction(Vector3(r * cos(u), r * sin(u), 0.0), sun) < 0.5) shadowed++
        }
        val conical = shadowed.toDouble() / samples
        println("Shadow fraction at 420 km, beta 0: cylindrical %.4f %%, conical %.4f %%".format(Locale.ROOT, cylindrical * 100, conical * 100))
        assertTrue(abs(conical - cylindrical) <= 0.005)
    }
}
