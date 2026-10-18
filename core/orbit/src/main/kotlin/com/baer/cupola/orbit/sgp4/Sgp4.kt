// Near-Earth SGP4 ported from python-sgp4 2.27 propagation.py (sgp4init, _initl, sgp4),
// Copyright Brandon Rhodes, MIT licence; see core/orbit/NOTICE-python-sgp4.txt.
package com.baer.cupola.orbit.sgp4

import com.baer.cupola.orbit.tle.Tle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class Vector3(val x: Double, val y: Double, val z: Double)

/** SGP4 error codes as defined by sgp4init/sgp4. */
enum class Sgp4Error(val code: Int) {
    MEAN_ECCENTRICITY(1),
    MEAN_MOTION(2),
    PERTURBED_ECCENTRICITY(3),
    SEMI_LATUS_RECTUM(4),
    SUB_ORBITAL(5),
    DECAYED(6),
}

sealed interface PropagationResult {
    /** TEME frame: position in km, velocity in km/s. */
    data class Success(val positionKm: Vector3, val velocityKmPerSec: Vector3) : PropagationResult
    data class Failure(val error: Sgp4Error) : PropagationResult
}

sealed interface Sgp4CreateResult {
    data class Ready(val propagator: Sgp4) : Sgp4CreateResult
    data class DeepSpaceNotSupported(val periodMinutes: Double) : Sgp4CreateResult
}

/** Secular and drag coefficients produced by sgp4init/initl for a near-Earth orbit (angles in rad, time in min). */
internal data class NearEarthState(
    val gravity: Gravity,
    val bstar: Double,
    val ecco: Double,
    val argpo: Double,
    val inclo: Double,
    val mo: Double,
    val nodeo: Double,
    val noUnkozai: Double,
    val isimp: Boolean,
    val con41: Double,
    val cc1: Double,
    val cc4: Double,
    val cc5: Double,
    val d2: Double,
    val d3: Double,
    val d4: Double,
    val delmo: Double,
    val eta: Double,
    val argpdot: Double,
    val omgcof: Double,
    val sinmao: Double,
    val t2cof: Double,
    val t3cof: Double,
    val t4cof: Double,
    val t5cof: Double,
    val x1mth2: Double,
    val x7thm1: Double,
    val mdot: Double,
    val nodedot: Double,
    val xlcof: Double,
    val xmcof: Double,
    val nodecf: Double,
    val aycof: Double,
)

/** Near-Earth SGP4 propagator (opsmode 'i'); deep-space orbits are rejected by [create]. */
class Sgp4 internal constructor(internal val state: NearEarthState) {

    fun propagate(minutesSinceEpoch: Double): PropagationResult = periodics(secular(minutesSinceEpoch))

    /** Mean elements after the secular gravity and drag update; [mm] already includes the templ drag term. */
    internal data class Secular(
        val mm: Double,
        val argpm: Double,
        val nodem: Double,
        val tempa: Double,
        val tempe: Double,
    )

    /** Time derivatives (rad/min) of [Secular.mm], [Secular.argpm] and [Secular.nodem] at [t]. */
    internal data class SecularRates(val mm: Double, val argpm: Double, val nodem: Double)

    internal fun secular(t: Double): Secular = with(state) {
        // ------- update for secular gravity and atmospheric drag -----
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf
        var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1.0 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2

        if (!isimp) {
            val delomg = omgcof * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            val delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo)
            val temp = delomg + delm
            mm = xmdf + temp
            argpm = argpdf - temp
            val t3 = t2 * t
            val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe = tempe + bstar * cc5 * (sin(mm) - sinmao)
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        Secular(mm = mm + noUnkozai * templ, argpm = argpm, nodem = nodem, tempa = tempa, tempe = tempe)
    }

    internal fun secularRates(t: Double): SecularRates = with(state) {
        val t2 = t * t
        var templRate = 2.0 * t2cof * t
        var delRate = 0.0
        if (!isimp) {
            val xmdf = mo + mdot * t
            val delmtemp = 1.0 + eta * cos(xmdf)
            delRate = omgcof - 3.0 * xmcof * delmtemp * delmtemp * eta * sin(xmdf) * mdot
            templRate += 3.0 * t3cof * t2 + 4.0 * t4cof * t2 * t + 5.0 * t5cof * t2 * t2
        }
        SecularRates(
            mm = mdot + delRate + noUnkozai * templRate,
            argpm = argpdot - delRate,
            nodem = nodedot + 2.0 * nodecf * t,
        )
    }

    internal fun periodics(secular: Secular): PropagationResult = with(state) {
        val xke = gravity.xke
        val j2 = gravity.j2
        val vkmpersec = gravity.radiusEarthKm * xke / 60.0
        var mm = secular.mm
        var argpm = secular.argpm
        var nodem = secular.nodem
        val tempa = secular.tempa

        var nm = noUnkozai
        var em = ecco
        val inclm = inclo
        if (nm <= 0.0) return PropagationResult.Failure(Sgp4Error.MEAN_MOTION)

        val am = (xke / nm).pow(X2O3) * tempa * tempa
        nm = xke / am.pow(1.5)
        em = em - secular.tempe
        if (em >= 1.0 || em < -0.001) return PropagationResult.Failure(Sgp4Error.MEAN_ECCENTRICITY)
        if (em < 1.0e-6) em = 1.0e-6
        var xlm = mm + argpm + nodem

        // Kotlin % on doubles is C fmod, which is what python-sgp4 applies to nodem.
        nodem = nodem % TWO_PI
        argpm = pyMod(argpm, TWO_PI)
        xlm = pyMod(xlm, TWO_PI)
        mm = pyMod(xlm - argpm - nodem, TWO_PI)

        val sinip = sin(inclm)
        val cosip = cos(inclm)
        val ep = em
        val argpp = argpm
        val nodep = nodem
        val mp = mm

        // -------------------- long period periodics ------------------
        val axnl = ep * cos(argpp)
        var temp = 1.0 / (am * (1.0 - ep * ep))
        val aynl = ep * sin(argpp) + temp * aycof
        val xl = mp + argpp + nodep + temp * xlcof * axnl

        // --------------------- solve kepler's equation ---------------
        val u = pyMod(xl - nodep, TWO_PI)
        var eo1 = u
        var tem5 = 9999.9
        var ktr = 1
        var sineo1 = 0.0
        var coseo1 = 0.0
        // sineo1/coseo1 keep the values from the start of the last iteration, as in the reference.
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1)
            coseo1 = cos(eo1)
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0.0) 0.95 else -0.95
            eo1 = eo1 + tem5
            ktr = ktr + 1
        }

        // ------------- short period preliminary quantities -----------
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1.0 - el2)
        if (pl < 0.0) return PropagationResult.Failure(Sgp4Error.SEMI_LATUS_RECTUM)

        val rl = am * (1.0 - ecose)
        val rdotl = sqrt(am) * esine / rl
        val rvdotl = sqrt(pl) / rl
        val betal = sqrt(1.0 - el2)
        temp = esine / (1.0 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1.0 - 2.0 * sinu * sinu
        temp = 1.0 / pl
        val temp1 = 0.5 * j2 * temp
        val temp2 = temp1 * temp

        // -------------- update for short period periodics ------------
        val mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su = su - 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodep + 1.5 * temp2 * cosip * sin2u
        val xinc = inclm + 1.5 * temp2 * cosip * sinip * cos2u
        val mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke
        val rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke

        // --------------------- orientation vectors -------------------
        val sinsu = sin(su)
        val cossu = cos(su)
        val snod = sin(xnode)
        val cnod = cos(xnode)
        val sini = sin(xinc)
        val cosi = cos(xinc)
        val xmx = -snod * cosi
        val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val vx = xmx * cossu - cnod * sinsu
        val vy = xmy * cossu - snod * sinsu
        val vz = sini * cossu

        if (mrt < 1.0) return PropagationResult.Failure(Sgp4Error.DECAYED)

        val mr = mrt * gravity.radiusEarthKm
        PropagationResult.Success(
            positionKm = Vector3(mr * ux, mr * uy, mr * uz),
            velocityKmPerSec = Vector3(
                (mvt * ux + rvdot * vx) * vkmpersec,
                (mvt * uy + rvdot * vy) * vkmpersec,
                (mvt * uz + rvdot * vz) * vkmpersec,
            ),
        )
    }

    companion object {
        private const val TWO_PI = 2.0 * PI
        private const val X2O3 = 2.0 / 3.0
        private const val DEG2RAD = PI / 180.0
        private const val XPDOTP = 1440.0 / (2.0 * PI)
        private const val DEEP_SPACE_PERIOD_MIN = 225.0

        fun create(tle: Tle, gravity: Gravity = Gravity.WGS72): Sgp4CreateResult {
            val ecco = tle.eccentricity
            val inclo = tle.inclinationDeg * DEG2RAD
            val argpo = tle.argumentOfPerigeeDeg * DEG2RAD
            val mo = tle.meanAnomalyDeg * DEG2RAD
            val nodeo = tle.raanDeg * DEG2RAD
            val noKozai = tle.meanMotionRevPerDay / XPDOTP
            val bstar = tle.bstar
            val xke = gravity.xke
            val j2 = gravity.j2
            val radius = gravity.radiusEarthKm

            // ------------------------- initl -------------------------
            val eccsq = ecco * ecco
            val omeosq = 1.0 - eccsq
            val rteosq = sqrt(omeosq)
            val cosio = cos(inclo)
            val cosio2 = cosio * cosio
            val ak = (xke / noKozai).pow(X2O3)
            val d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq)
            var del = d1 / (ak * ak)
            val adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0))
            del = d1 / (adel * adel)
            val no = noKozai / (1.0 + del)
            val ao = (xke / no).pow(X2O3)
            val sinio = sin(inclo)
            val po = ao * omeosq
            val con42 = 1.0 - 5.0 * cosio2
            val con41 = -con42 - cosio2 - cosio2
            val posq = po * po
            val rp = ao * (1.0 - ecco)

            val period = 2.0 * PI / no
            if (period >= DEEP_SPACE_PERIOD_MIN) return Sgp4CreateResult.DeepSpaceNotSupported(period)

            // ------------------------ sgp4init -----------------------
            val ss = 78.0 / radius + 1.0
            val qzms2ttemp = (120.0 - 78.0) / radius
            val qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp
            val x1mth2 = 1.0 - cosio2
            val x7thm1 = 7.0 * cosio2 - 1.0
            val isimp = rp < 220.0 / radius + 1.0
            var sfour = ss
            var qzms24 = qzms2t
            val perige = (rp - 1.0) * radius
            // - for perigees below 156 km, s and qoms2t are altered -
            if (perige < 156.0) {
                sfour = perige - 78.0
                if (perige < 98.0) sfour = 20.0
                val qzms24temp = (120.0 - sfour) / radius
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp
                sfour = sfour / radius + 1.0
            }
            val pinvsq = 1.0 / posq

            val tsi = 1.0 / (ao - sfour)
            val eta = ao * ecco * tsi
            val etasq = eta * eta
            val eeta = ecco * eta
            val psisq = abs(1.0 - etasq)
            val coef = qzms24 * tsi.pow(4.0)
            val coef1 = coef / psisq.pow(3.5)
            val cc2 = coef1 * no * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq)) +
                0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)))
            val cc1 = bstar * cc2
            val cc3 = if (ecco > 1.0e-4) -2.0 * coef * tsi * gravity.j3oj2 * no * sinio / ecco else 0.0
            val cc4 = 2.0 * no * coef1 * ao * omeosq *
                (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq) - j2 * tsi / (ao * psisq) *
                    (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta)) + 0.75 * x1mth2 *
                        (2.0 * etasq - eeta * (1.0 + etasq)) * cos(2.0 * argpo)))
            val cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq)
            val cosio4 = cosio2 * cosio2
            val temp1 = 1.5 * j2 * pinvsq * no
            val temp2 = 0.5 * temp1 * j2 * pinvsq
            val temp3 = -0.46875 * gravity.j4 * pinvsq * pinvsq * no
            val mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 *
                temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4)
            val argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 *
                (7.0 - 114.0 * cosio2 + 395.0 * cosio4) +
                temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4)
            val xhdot1 = -temp1 * cosio
            val nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) +
                2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio
            val omgcof = bstar * cc3 * cos(argpo)
            val xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
            val nodecf = 3.5 * omeosq * xhdot1 * cc1
            val t2cof = 1.5 * cc1
            // Divide-by-zero guard for inclination of 180 degrees.
            val xlcofDivisor = if (abs(cosio + 1.0) > 1.5e-12) 1.0 + cosio else 1.5e-12
            val xlcof = -0.25 * gravity.j3oj2 * sinio * (3.0 + 5.0 * cosio) / xlcofDivisor
            val aycof = -0.5 * gravity.j3oj2 * sinio
            val delmotemp = 1.0 + eta * cos(mo)
            val delmo = delmotemp * delmotemp * delmotemp
            val sinmao = sin(mo)

            var d2 = 0.0
            var d3 = 0.0
            var d4 = 0.0
            var t3cof = 0.0
            var t4cof = 0.0
            var t5cof = 0.0
            if (!isimp) {
                val cc1sq = cc1 * cc1
                d2 = 4.0 * ao * tsi * cc1sq
                val temp = d2 * tsi * cc1 / 3.0
                d3 = (17.0 * ao + sfour) * temp
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1
                t3cof = d2 + 2.0 * cc1sq
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq))
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq))
            }

            return Sgp4CreateResult.Ready(
                Sgp4(
                    NearEarthState(
                        gravity = gravity, bstar = bstar, ecco = ecco, argpo = argpo, inclo = inclo, mo = mo,
                        nodeo = nodeo, noUnkozai = no, isimp = isimp, con41 = con41, cc1 = cc1, cc4 = cc4,
                        cc5 = cc5, d2 = d2, d3 = d3, d4 = d4, delmo = delmo, eta = eta, argpdot = argpdot,
                        omgcof = omgcof, sinmao = sinmao, t2cof = t2cof, t3cof = t3cof, t4cof = t4cof,
                        t5cof = t5cof, x1mth2 = x1mth2, x7thm1 = x7thm1, mdot = mdot, nodedot = nodedot,
                        xlcof = xlcof, xmcof = xmcof, nodecf = nodecf, aycof = aycof,
                    ),
                ),
            )
        }

        /** Python float modulo for positive [b]: the result lies in [0, b). */
        private fun pyMod(a: Double, b: Double): Double {
            val mod = a % b
            return if (mod < 0.0) mod + b else mod
        }
    }
}
