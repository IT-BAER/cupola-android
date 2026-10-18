package com.baer.cupola.orbit.astro

import com.baer.cupola.orbit.frames.angleTo
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.readHorizonsCsv
import com.baer.cupola.orbit.sgp4.Vector3
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunMoonTest {

    private val deg = PI / 180.0
    private val rows = readHorizonsCsv("/horizons/sun_moon.csv")

    private fun unit(raDeg: Double, decDeg: Double) =
        Vector3(cos(decDeg * deg) * cos(raDeg * deg), cos(decDeg * deg) * sin(raDeg * deg), sin(decDeg * deg))

    @Test
    fun sunMatchesHorizons() {
        assertEquals(50, rows.size)
        var worstDeg = 0.0
        var worstRange = 0.0
        for (r in rows) {
            val sun = Sun.apparent(r[0])
            val sepDeg = sun.unitVector().angleTo(unit(r[1], r[2])) / deg
            val rangeRel = abs(sun.distanceKm / r[3] - 1.0)
            worstDeg = maxOf(worstDeg, sepDeg)
            worstRange = maxOf(worstRange, rangeRel)
            assertTrue("jd ${r[0]}: Sun separation $sepDeg deg", sepDeg <= 0.01)
            assertTrue("jd ${r[0]}: Sun range rel error $rangeRel", rangeRel <= 1e-4)
        }
        println("Largest Sun separation = %.5f deg, range rel error = %.2e".format(Locale.ROOT, worstDeg, worstRange))
    }

    @Test
    fun moonMatchesHorizons() {
        var worstDeg = 0.0
        var worstRangeKm = 0.0
        for (r in rows) {
            val moon = Moon.apparent(r[0])
            val sepDeg = moon.unitVector().angleTo(unit(r[4], r[5])) / deg
            val rangeKm = abs(moon.distanceKm - r[6])
            worstDeg = maxOf(worstDeg, sepDeg)
            worstRangeKm = maxOf(worstRangeKm, rangeKm)
            assertTrue("jd ${r[0]}: Moon separation $sepDeg deg", sepDeg <= 0.3)
            assertTrue("jd ${r[0]}: Moon range error $rangeKm km", rangeKm <= 400.0)
        }
        println("Largest Moon separation = %.5f deg, range error = %.1f km".format(Locale.ROOT, worstDeg, worstRangeKm))
    }

    @Test
    fun trueOfDateToTemeRemovesEquationOfEquinoxes() {
        val jdTt = 2461041.5
        val sun = Sun.apparent(jdTt)
        val teme = sun.positionTemeKm(jdTt)
        assertEquals(sun.distanceKm, teme.norm(), 1e-6)
        // Hour angle is invariant: GAST - RA_tod == GMST - RA_teme.
        val raTeme = kotlin.math.atan2(teme.y, teme.x)
        val eqeq = Nutation.equationOfEquinoxesRad(jdTt)
        var d = (sun.rightAscensionRad - eqeq) - raTeme
        d = (d + 3 * PI) % (2 * PI) - PI
        assertEquals(0.0, d, 1e-12)
    }
}
