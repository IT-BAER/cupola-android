package com.baer.cupola.orbit.time

import kotlin.math.PI

object Sidereal {

    private const val TWO_PI = 2.0 * PI

    /**
     * Greenwich mean sidereal time, IAU-82, in radians [0, 2pi): the angle SGP4's TEME frame expects.
     * Same formula as python-sgp4 `gstime`.
     */
    fun gmstRad(jdUt1: Double): Double {
        val t = (jdUt1 - 2451545.0) / 36525.0
        val seconds = -6.2e-6 * t * t * t + 0.093104 * t * t +
            (876600.0 * 3600.0 + 8640184.812866) * t + 67310.54841
        val rad = (seconds * (PI / 180.0) / 240.0) % TWO_PI
        return if (rad < 0.0) rad + TWO_PI else rad
    }
}
