package com.baer.cupola.orbit.astro

import com.baer.cupola.orbit.astro.Nutation.ARCSEC
import com.baer.cupola.orbit.astro.Nutation.DEG
import kotlin.math.cos
import kotlin.math.sin

/** Low-precision Sun (Meeus, Astronomical Algorithms ch. 25) with the leading nutation terms and aberration. */
object Sun {

    const val AU_KM = 149597870.7

    fun apparent(jdTt: Double): ApparentPosition {
        val t = Nutation.centuriesTt(jdTt)
        val l0 = 280.46646 + 36000.76983 * t + 0.0003032 * t * t
        val m = (357.52911 + 35999.05029 * t - 0.0001537 * t * t) * DEG
        val e = 0.016708634 - 0.000042037 * t - 0.0000001267 * t * t
        val c = (1.914602 - 0.004817 * t - 0.000014 * t * t) * sin(m) +
            (0.019993 - 0.000101 * t) * sin(2 * m) + 0.000289 * sin(3 * m)
        val v = m + c * DEG
        val rAu = 1.000001018 * (1 - e * e) / (1 + e * cos(v))
        val n = Nutation.at(jdTt)
        val lambda = (l0 + c) * DEG + n.dPsiRad - 20.4898 * ARCSEC / rAu
        return ApparentPosition.fromEcliptic(lambda, 0.0, rAu * AU_KM, n.trueObliquityRad)
    }
}
