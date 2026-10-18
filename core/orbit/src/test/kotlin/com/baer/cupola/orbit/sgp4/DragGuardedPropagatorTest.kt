package com.baer.cupola.orbit.sgp4

import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.tle.Tle
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DragGuardedPropagatorTest {

    private val tle = Tle.parse(
        "1 25544U 98067A   26291.14461894  .00007503  00000+0  14456-3 0  9994",
        "2 25544  51.6328  51.6259 0006780 277.7856  82.2363 15.48918166590814",
    )
    private val plain = (Sgp4.create(tle) as Sgp4CreateResult.Ready).propagator
    private val freezeMin = 14.0 * 1440.0
    private val guarded = DragGuardedPropagator(plain)
    private val periodMin = 1440.0 / tle.meanMotionRevPerDay
    private val tenYearsMin = 3652.5 * 1440.0

    @Test
    fun identicalToPlainSgp4UpToFreezeAge() {
        assertEquals(freezeMin, guarded.freezeAfterMinutes, 0.0)
        var t = 0.0
        while (t <= freezeMin) {
            assertEquals("t=$t", plain.propagate(t), guarded.propagate(t))
            t += 1.0
        }
    }

    @Test
    fun continuousAcrossFreezeAge() {
        // The guard drops plain SGP4's decay after T, so guarded and plain drift apart at about that rate.
        val decayKmPerSec = (averageAltitudeKm(plain::propagate, 0.0) - averageAltitudeKm(plain::propagate, freezeMin)) /
            (freezeMin * 60.0)
        for (hSec in listOf(1.0, 60.0)) {
            val h = hSec / 60.0
            val gBefore = success(guarded, freezeMin - h)
            val gAfter = success(guarded, freezeMin + h)
            val pBefore = success(plain, freezeMin - h)
            val pAfter = success(plain, freezeMin + h)
            val gStep = (gAfter.positionKm - gBefore.positionKm).norm()
            val pStep = (pAfter.positionKm - pBefore.positionKm).norm()
            val dvExcess = ((gAfter.velocityKmPerSec - gBefore.velocityKmPerSec) -
                (pAfter.velocityKmPerSec - pBefore.velocityKmPerSec)).norm()
            val posOffset = (gAfter.positionKm - pAfter.positionKm).norm()
            println(
                "P1.6 continuity h=${hSec}s: guarded step ${gStep * 1e3} m, plain step ${pStep * 1e3} m, " +
                    "excess ${(gStep - pStep) * 1e3} m, velocity jump excess ${dvExcess * 1e3} m/s, " +
                    "guarded-plain at T+h ${posOffset * 1e3} m",
            )
            val flownKm = gBefore.velocityKmPerSec.norm() * 2.0 * hSec
            assertTrue("h=$hSec position jump above distance flown", gStep <= flownKm + 1.0e-6)
            assertTrue("h=$hSec position jump above plain plus decay", gStep - pStep <= 1.0e-6 + 2.0 * decayKmPerSec * hSec)
            assertTrue("h=$hSec velocity jump", dvExcess <= 1.0e-3)
        }
    }

    @Test
    fun orbitAveragedAltitudeHeldAfterFreezeAge() {
        val atFreeze = averageAltitudeKm(guarded, freezeMin)
        var worst = 0.0
        var worstDay = 0
        var day = 30
        while (day * 1440.0 <= tenYearsMin) {
            val deviation = abs(averageAltitudeKm(guarded, freezeMin + day * 1440.0) - atFreeze)
            if (deviation > worst) {
                worst = deviation
                worstDay = day
            }
            day += 30
        }
        println("P1.6 averaged altitude at T $atFreeze km, largest deviation $worst km at T+$worstDay d")
        assertTrue("largest deviation $worst km at T+$worstDay d", worst <= 1.0)
    }

    @Test
    fun noErrorForTenYears() {
        var t = 0.0
        while (t <= tenYearsMin) {
            success(guarded, t)
            t += 10.0
        }
    }

    @Test
    fun plainAltitudeDropUpToFreezeAge() {
        val drop = averageAltitudeKm(plain::propagate, 0.0) - averageAltitudeKm(plain::propagate, freezeMin)
        println("P1.6 plain SGP4 averaged altitude drop epoch->T $drop km")
        assertTrue("drop $drop km", drop > 0.0 && drop < 10.0)
    }

    private fun averageAltitudeKm(p: DragGuardedPropagator, startMin: Double) = averageAltitudeKm(p::propagate, startMin)

    private fun averageAltitudeKm(propagate: (Double) -> PropagationResult, startMin: Double): Double {
        val samples = (periodMin * 2.0).toInt()
        var sum = 0.0
        for (i in 0 until samples) {
            val t = startMin + i * 0.5
            val r = propagate(t).asSuccess(t)
            val jd = tle.epochJdWhole + tle.epochJdFraction + t / 1440.0
            sum += Frames.ecefToGeodetic(Frames.temeToEcef(r.positionKm, jd)).heightKm
        }
        return sum / samples
    }

    private fun success(p: Sgp4, t: Double) = p.propagate(t).asSuccess(t)
    private fun success(p: DragGuardedPropagator, t: Double) = p.propagate(t).asSuccess(t)

    private fun PropagationResult.asSuccess(t: Double): PropagationResult.Success = when (this) {
        is PropagationResult.Success -> this
        is PropagationResult.Failure -> throw AssertionError("SGP4 error $error at t=$t min")
    }
}
