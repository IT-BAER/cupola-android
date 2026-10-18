package com.baer.cupola.orbit.time

import com.baer.cupola.orbit.astro.Nutation
import com.baer.cupola.orbit.readHorizonsCsv
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SiderealTest {

    @Test
    fun gmstMatchesPythonSgp4AtJ2000() {
        // gstime(2451545.0) = 67310.54841 s of time -> radians.
        assertEquals(67310.54841 / 86400.0 * 2 * PI, Sidereal.gmstRad(2451545.0), 1e-12)
    }

    @Test
    fun apparentSiderealTimeMatchesHorizons() {
        val rows = readHorizonsCsv("/horizons/sidereal.csv")
        assertEquals(10, rows.size)
        var worstSeconds = 0.0
        for ((jdUt1, lastHours) in rows.map { it[0] to it[1] }) {
            // TT-UT1 (~30 s in the 1950s) is negligible for the nutation arguments.
            val gastRad = Sidereal.gmstRad(jdUt1) + Nutation.equationOfEquinoxesRad(jdUt1)
            val gastHours = gastRad * 12.0 / PI
            var diffHours = (gastHours - lastHours) % 24.0
            if (diffHours > 12.0) diffHours -= 24.0
            if (diffHours < -12.0) diffHours += 24.0
            val diffSeconds = abs(diffHours) * 3600.0
            worstSeconds = maxOf(worstSeconds, diffSeconds)
            assertTrue("jd $jdUt1: |GAST-LAST| = $diffSeconds s", diffSeconds <= 0.1)
        }
        println("Largest |GAST - Horizons LAST| = %.4f s".format(Locale.ROOT, worstSeconds))
    }
}
