package com.baer.cupola.orbit.astro

import com.baer.cupola.orbit.frames.angleTo
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.sgp4.Vector3
import kotlin.math.cos

object Topocentric {

    /** Vector from the observer to the target; both geocentric, in km, in the same frame. */
    fun vector(targetKm: Vector3, observerKm: Vector3): Vector3 = targetKm - observerKm
}

object MoonPhase {

    private val GEOCENTER = Vector3(0.0, 0.0, 0.0)

    /** Sun-Moon-observer angle in radians: 0 at full Moon, pi at new Moon. Inputs share one frame. */
    fun phaseAngleRad(sunKm: Vector3, moonKm: Vector3, observerKm: Vector3 = GEOCENTER): Double =
        (sunKm - moonKm).angleTo(observerKm - moonKm)

    /** Illuminated fraction of the lunar disk, (1 + cos i) / 2. */
    fun illuminatedFraction(sunKm: Vector3, moonKm: Vector3, observerKm: Vector3 = GEOCENTER): Double =
        (1.0 + cos(phaseAngleRad(sunKm, moonKm, observerKm))) / 2.0
}
