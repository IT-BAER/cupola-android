package com.baer.cupola.orbit.astro

import com.baer.cupola.orbit.astro.Nutation.DEG
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Moon from Meeus, Astronomical Algorithms ch. 47 (truncated ELP-2000/82). The latitude table is cut
 * below 0.001 deg per term; Meeus quotes about 10" in longitude for the full tables.
 */
object Moon {

    // D, M, M', F, sum-l coefficient (1e-6 deg), sum-r coefficient (1e-3 km). Meeus table 47.A.
    private val lonDist = intArrayOf(
        0, 0, 1, 0, 6288774, -20905355,
        2, 0, -1, 0, 1274027, -3699111,
        2, 0, 0, 0, 658314, -2955968,
        0, 0, 2, 0, 213618, -569925,
        0, 1, 0, 0, -185116, 48888,
        0, 0, 0, 2, -114332, -3149,
        2, 0, -2, 0, 58793, 246158,
        2, -1, -1, 0, 57066, -152138,
        2, 0, 1, 0, 53322, -170733,
        2, -1, 0, 0, 45758, -204586,
        0, 1, -1, 0, -40923, -129620,
        1, 0, 0, 0, -34720, 108743,
        0, 1, 1, 0, -30383, 104755,
        2, 0, 0, -2, 15327, 10321,
        0, 0, 1, 2, -12528, 0,
        0, 0, 1, -2, 10980, 79661,
        4, 0, -1, 0, 10675, -34782,
        0, 0, 3, 0, 10034, -23210,
        4, 0, -2, 0, 8548, -21636,
        2, 1, -1, 0, -7888, 24208,
        2, 1, 0, 0, -6766, 30824,
        1, 0, -1, 0, -5163, -8379,
        1, 1, 0, 0, 4987, -16675,
        2, -1, 1, 0, 4036, -12831,
        2, 0, 2, 0, 3994, -10445,
        4, 0, 0, 0, 3861, -11650,
        2, 0, -3, 0, 3665, 14403,
        0, 1, -2, 0, -2689, -7003,
        2, 0, -1, 2, -2602, 0,
        2, -1, -2, 0, 2390, 10056,
        1, 0, 1, 0, -2348, 6322,
        2, -2, 0, 0, 2236, -9884,
        0, 1, 2, 0, -2120, 5751,
        0, 2, 0, 0, -2069, 0,
        2, -2, -1, 0, 2048, -4950,
        2, 0, 1, -2, -1773, 4130,
        2, 0, 0, 2, -1595, 0,
        4, -1, -1, 0, 1215, -3958,
        0, 0, 2, 2, -1110, 0,
        3, 0, -1, 0, -892, 3258,
        2, 1, 1, 0, -810, 2616,
        4, -1, -2, 0, 759, -1897,
        0, 2, -1, 0, -713, -2117,
        2, 2, -1, 0, -700, 2354,
        2, 1, -2, 0, 691, 0,
        2, -1, 0, -2, 596, 0,
        4, 0, 1, 0, 549, -1423,
        0, 0, 4, 0, 537, -1117,
        4, -1, 0, 0, 520, -1571,
        1, 0, -2, 0, -487, -1739,
        2, 1, 0, -2, -399, 0,
        0, 0, 2, -2, -381, -4421,
        1, 1, 1, 0, 351, 0,
        3, 0, -2, 0, -340, 0,
        4, 0, -3, 0, 330, 0,
        2, -1, 2, 0, 327, 0,
        0, 2, 1, 0, -323, 1165,
        1, 1, -1, 0, 299, 0,
        2, 0, 3, 0, 294, 0,
        2, 0, -1, -2, 0, 8752,
    )

    // D, M, M', F, sum-b coefficient (1e-6 deg). Meeus table 47.B, terms of at least 0.001 deg.
    private val lat = intArrayOf(
        0, 0, 0, 1, 5128122,
        0, 0, 1, 1, 280602,
        0, 0, 1, -1, 277693,
        2, 0, 0, -1, 173237,
        2, 0, -1, 1, 55413,
        2, 0, -1, -1, 46271,
        2, 0, 0, 1, 32573,
        0, 0, 2, 1, 17198,
        2, 0, 1, -1, 9266,
        0, 0, 2, -1, 8822,
        2, -1, 0, -1, 8216,
        2, 0, -2, -1, 4324,
        2, 0, 1, 1, 4200,
        2, 1, 0, -1, -3359,
        2, -1, -1, 1, 2463,
        2, -1, 0, 1, 2211,
        2, -1, -1, -1, 2065,
        0, 1, -1, -1, -1870,
        4, 0, -1, -1, 1828,
        0, 1, 0, 1, -1794,
        0, 0, 0, 3, -1749,
        0, 1, -1, 1, -1565,
        1, 0, 0, 1, -1491,
        0, 1, 1, 1, -1475,
        0, 1, 1, -1, -1410,
        0, 1, 0, -1, -1344,
        1, 0, 0, -1, -1335,
        0, 0, 3, 1, 1107,
        4, 0, 0, -1, 1021,
    )

    fun apparent(jdTt: Double): ApparentPosition {
        val t = Nutation.centuriesTt(jdTt)
        val t2 = t * t
        val t3 = t2 * t
        val t4 = t3 * t
        val lp = (218.3164477 + 481267.88123421 * t - 0.0015786 * t2 + t3 / 538841.0 - t4 / 65194000.0) * DEG
        val d = (297.8501921 + 445267.1114034 * t - 0.0018819 * t2 + t3 / 545868.0 - t4 / 113065000.0) * DEG
        val m = (357.5291092 + 35999.0502909 * t - 0.0001536 * t2 + t3 / 24490000.0) * DEG
        val mp = (134.9633964 + 477198.8675055 * t + 0.0087414 * t2 + t3 / 69699.0 - t4 / 14712000.0) * DEG
        val f = (93.2720950 + 483202.0175233 * t - 0.0036539 * t2 - t3 / 3526000.0 + t4 / 863310000.0) * DEG
        val a1 = (119.75 + 131.849 * t) * DEG
        val a2 = (53.09 + 479264.290 * t) * DEG
        val a3 = (313.45 + 481266.484 * t) * DEG
        val e = 1.0 - 0.002516 * t - 0.0000074 * t2

        // Terms containing the Sun's mean anomaly M scale with the Earth's orbital eccentricity.
        fun eccentricityFactor(mMultiple: Int) = when (abs(mMultiple)) {
            1 -> e
            2 -> e * e
            else -> 1.0
        }

        var sumL = 0.0
        var sumR = 0.0
        for (i in lonDist.indices step 6) {
            val arg = lonDist[i] * d + lonDist[i + 1] * m + lonDist[i + 2] * mp + lonDist[i + 3] * f
            val k = eccentricityFactor(lonDist[i + 1])
            sumL += lonDist[i + 4] * k * sin(arg)
            sumR += lonDist[i + 5] * k * cos(arg)
        }
        var sumB = 0.0
        for (i in lat.indices step 5) {
            val arg = lat[i] * d + lat[i + 1] * m + lat[i + 2] * mp + lat[i + 3] * f
            sumB += lat[i + 4] * eccentricityFactor(lat[i + 1]) * sin(arg)
        }
        sumL += 3958 * sin(a1) + 1962 * sin(lp - f) + 318 * sin(a2)
        sumB += -2235 * sin(lp) + 382 * sin(a3) + 175 * sin(a1 - f) + 175 * sin(a1 + f) +
            127 * sin(lp - mp) - 115 * sin(lp + mp)

        val n = Nutation.at(jdTt)
        val lambda = lp + sumL * 1e-6 * DEG + n.dPsiRad
        val beta = sumB * 1e-6 * DEG
        val distanceKm = 385000.56 + sumR / 1000.0
        return ApparentPosition.fromEcliptic(lambda, beta, distanceKm, n.trueObliquityRad)
    }
}
