package com.baer.cupola.orbit.frames

import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.Sidereal
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 geodetic coordinates; height in km above the ellipsoid. */
data class Geodetic(val latitudeRad: Double, val longitudeRad: Double, val heightKm: Double)

object Frames {

    const val WGS84_A_KM = 6378.137
    const val WGS84_F = 1.0 / 298.257223563
    private const val E2 = WGS84_F * (2.0 - WGS84_F)

    // Polar motion (< 15 m at the surface) is ignored: ECEF here is the pseudo-Earth-fixed frame.
    fun temeToEcef(temeKm: Vector3, jdUt1: Double): Vector3 = temeKm.rotatedFrameZ(Sidereal.gmstRad(jdUt1))

    fun ecefToTeme(ecefKm: Vector3, jdUt1: Double): Vector3 = ecefKm.rotatedFrameZ(-Sidereal.gmstRad(jdUt1))

    fun geodeticToEcef(g: Geodetic): Vector3 {
        val sinLat = sin(g.latitudeRad)
        val n = WGS84_A_KM / sqrt(1.0 - E2 * sinLat * sinLat)
        val rxy = (n + g.heightKm) * cos(g.latitudeRad)
        return Vector3(rxy * cos(g.longitudeRad), rxy * sin(g.longitudeRad), (n * (1.0 - E2) + g.heightKm) * sinLat)
    }

    /** Fixed-point iteration on latitude, converged to 1e-13 rad; longitude is 0 on the polar axis. */
    fun ecefToGeodetic(ecefKm: Vector3): Geodetic {
        val p = hypot(ecefKm.x, ecefKm.y)
        val z = ecefKm.z
        var lat = atan2(z, p * (1.0 - E2))
        for (i in 0 until 50) {
            val sinLat = sin(lat)
            val n = WGS84_A_KM / sqrt(1.0 - E2 * sinLat * sinLat)
            val next = atan2(z + E2 * n * sinLat, p)
            val done = abs(next - lat) < 1e-13
            lat = next
            if (done) break
        }
        val sinLat = sin(lat)
        val height = p * cos(lat) + z * sinLat - WGS84_A_KM * sqrt(1.0 - E2 * sinLat * sinLat)
        return Geodetic(lat, atan2(ecefKm.y, ecefKm.x), height)
    }
}
