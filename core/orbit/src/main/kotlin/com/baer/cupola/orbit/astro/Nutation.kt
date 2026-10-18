package com.baer.cupola.orbit.astro

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Low-precision nutation (Meeus ch. 22, leading terms; about 0.5" in longitude, 0.1" in obliquity). */
data class NutationAngles(val dPsiRad: Double, val dEpsRad: Double, val meanObliquityRad: Double) {
    val trueObliquityRad: Double get() = meanObliquityRad + dEpsRad
}

object Nutation {

    internal const val DEG = PI / 180.0
    internal const val ARCSEC = DEG / 3600.0

    internal fun centuriesTt(jdTt: Double): Double = (jdTt - 2451545.0) / 36525.0

    internal fun moonNodeRad(t: Double): Double =
        (125.04452 - 1934.136261 * t + 0.0020708 * t * t + t * t * t / 450000.0) * DEG

    fun at(jdTt: Double): NutationAngles {
        val t = centuriesTt(jdTt)
        val omega = moonNodeRad(t)
        val sunL = (280.4665 + 36000.7698 * t) * DEG
        val moonL = (218.3165 + 481267.8813 * t) * DEG
        val dPsi = (-17.20 * sin(omega) - 1.32 * sin(2 * sunL) - 0.23 * sin(2 * moonL) + 0.21 * sin(2 * omega)) * ARCSEC
        val dEps = (9.20 * cos(omega) + 0.57 * cos(2 * sunL) + 0.10 * cos(2 * moonL) - 0.09 * cos(2 * omega)) * ARCSEC
        val eps0 = 23.0 * DEG + 26.0 * DEG / 60.0 +
            (21.448 - 46.8150 * t - 0.00059 * t * t + 0.001813 * t * t * t) * ARCSEC
        return NutationAngles(dPsi, dEps, eps0)
    }

    /** GAST - GMST in radians, with the IERS 1992 complementary terms. */
    fun equationOfEquinoxesRad(jdTt: Double): Double {
        val n = at(jdTt)
        val omega = moonNodeRad(centuriesTt(jdTt))
        return n.dPsiRad * cos(n.trueObliquityRad) + (0.00264 * sin(omega) + 0.000063 * sin(2 * omega)) * ARCSEC
    }
}
