package com.baer.cupola.orbit.eclipse

import com.baer.cupola.orbit.frames.angleTo
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.frames.unaryMinus
import com.baer.cupola.orbit.sgp4.Vector3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.sqrt

object Eclipse {

    const val EARTH_RADIUS_KM = 6378.137
    const val SUN_RADIUS_KM = 696000.0

    /**
     * Sunlit fraction 0..1 of the solar disk seen from [positionKm] (geocentric, same frame as [sunKm]).
     * Conical shadow of a spherical Earth: umbra where the Earth disk covers the Sun disk, penumbra where
     * they overlap. Across the penumbra the fraction is linear in the apparent overlap area (flat-disk
     * circle intersection of the two angular radii). Positions inside the Earth return 0.
     */
    fun sunlitFraction(positionKm: Vector3, sunKm: Vector3): Double {
        val r = positionKm.norm()
        if (r <= EARTH_RADIUS_KM) return 0.0
        val toSun = sunKm - positionKm
        val a = asin((SUN_RADIUS_KM / toSun.norm()).coerceAtMost(1.0))
        val b = asin(EARTH_RADIUS_KM / r)
        val c = toSun.angleTo(-positionKm)
        return when {
            c >= a + b -> 1.0
            c <= b - a -> 0.0
            c <= a - b -> 1.0 - (b * b) / (a * a)
            else -> (1.0 - overlapArea(a, b, c) / (PI * a * a)).coerceIn(0.0, 1.0)
        }
    }

    /** Area of intersection of two circles with radii [a], [b] and centre distance [c], |a-b| < c < a+b. */
    private fun overlapArea(a: Double, b: Double, c: Double): Double {
        val alpha = acos(((c * c + a * a - b * b) / (2 * c * a)).coerceIn(-1.0, 1.0))
        val beta = acos(((c * c + b * b - a * a) / (2 * c * b)).coerceIn(-1.0, 1.0))
        val k = (-c + a + b) * (c + a - b) * (c - a + b) * (c + a + b)
        return a * a * alpha + b * b * beta - 0.5 * sqrt(k.coerceAtLeast(0.0))
    }
}
