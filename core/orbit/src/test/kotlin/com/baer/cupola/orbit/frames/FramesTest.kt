package com.baer.cupola.orbit.frames

import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.Sidereal
import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FramesTest {

    private val deg = PI / 180.0

    @Test
    fun geodeticRoundTrips() {
        val latitudesDeg = listOf(90.0, -90.0, 0.0, 51.6, -51.6, 89.999999, 45.0)
        val longitudesDeg = listOf(0.0, 47.3, -120.0, 179.9)
        val heightsKm = listOf(0.0, 420.0)
        for (latDeg in latitudesDeg) for (lonDeg in longitudesDeg) for (h in heightsKm) {
            val input = Geodetic(latDeg * deg, lonDeg * deg, h)
            val ecef = Frames.geodeticToEcef(input)
            val back = Frames.ecefToGeodetic(ecef)
            val again = Frames.geodeticToEcef(back)
            val label = "lat $latDeg lon $lonDeg h $h"
            assertEquals(label, input.latitudeRad, back.latitudeRad, 1e-9)
            assertEquals(label, input.heightKm, back.heightKm, 1e-6)
            if (abs(latDeg) < 90.0) assertEquals(label, 0.0, angleDiff(input.longitudeRad, back.longitudeRad), 1e-9)
            assertTrue(label, (again - ecef).norm() <= 1e-6)
        }
    }

    @Test
    fun knownEcefPoints() {
        val equator = Frames.geodeticToEcef(Geodetic(0.0, 0.0, 0.0))
        assertEquals(6378.137, equator.x, 1e-9)
        val pole = Frames.geodeticToEcef(Geodetic(PI / 2, 0.0, 0.0))
        assertEquals(6356.752314245, pole.z, 1e-6)
        assertEquals(0.0, Frames.ecefToGeodetic(Vector3(0.0, 0.0, -6356.752314245)).heightKm, 1e-6)
    }

    @Test
    fun temeToEcefIsRotationByGmst() {
        val jdUt1 = 2461331.75
        val r = Vector3(-4400.594, 1932.870, 4760.712)
        val ecef = Frames.temeToEcef(r, jdUt1)
        assertEquals(r.norm(), ecef.norm(), 1e-9)
        assertEquals(r.z, ecef.z, 0.0)
        val g = Sidereal.gmstRad(jdUt1)
        // Longitude in ECEF = right ascension in TEME minus GMST.
        val expected = kotlin.math.atan2(r.y, r.x) - g
        assertEquals(0.0, angleDiff(expected, kotlin.math.atan2(ecef.y, ecef.x)), 1e-12)
        val back = Frames.ecefToTeme(ecef, jdUt1)
        assertTrue((back - r).norm() < 1e-9)
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = (a - b) % (2 * PI)
        if (d > PI) d -= 2 * PI
        if (d < -PI) d += 2 * PI
        return d
    }
}
