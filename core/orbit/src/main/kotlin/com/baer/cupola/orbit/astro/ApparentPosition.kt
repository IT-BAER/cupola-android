package com.baer.cupola.orbit.astro

import com.baer.cupola.orbit.frames.rotatedFrameZ
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.sgp4.Vector3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Geocentric apparent place in the true equator and equinox of date (TOD). */
data class ApparentPosition(val rightAscensionRad: Double, val declinationRad: Double, val distanceKm: Double) {

    fun unitVector(): Vector3 = Vector3(
        cos(declinationRad) * cos(rightAscensionRad),
        cos(declinationRad) * sin(rightAscensionRad),
        sin(declinationRad),
    )

    fun positionTodKm(): Vector3 = unitVector() * distanceKm

    /** TEME shares the true equator but uses the mean equinox: rotate by the equation of the equinoxes. */
    fun positionTemeKm(jdTt: Double): Vector3 = positionTodKm().rotatedFrameZ(Nutation.equationOfEquinoxesRad(jdTt))

    internal companion object {
        fun fromEcliptic(lambdaRad: Double, betaRad: Double, distanceKm: Double, obliquityRad: Double): ApparentPosition {
            val ce = cos(obliquityRad)
            val se = sin(obliquityRad)
            val x = cos(betaRad) * cos(lambdaRad)
            val y = ce * cos(betaRad) * sin(lambdaRad) - se * sin(betaRad)
            val z = se * cos(betaRad) * sin(lambdaRad) + ce * sin(betaRad)
            val ra = atan2(y, x).let { if (it < 0.0) it + 2 * PI else it }
            return ApparentPosition(ra, asin(z.coerceIn(-1.0, 1.0)), distanceKm)
        }
    }
}
