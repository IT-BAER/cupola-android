package com.baer.cupola.orbit

import com.baer.cupola.orbit.astro.Moon
import com.baer.cupola.orbit.astro.Sun
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.sgp4.DragGuardedPropagator
import com.baer.cupola.orbit.sgp4.PropagationResult
import com.baer.cupola.orbit.sgp4.Sgp4
import com.baer.cupola.orbit.sgp4.Sgp4CreateResult
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.TimeScales
import com.baer.cupola.orbit.tle.Tle
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitModelTest {

    private val tle = Tle.parse(
        "1 25544U 98067A   26291.14461894  .00007503  00000+0  14456-3 0  9994",
        "2 25544  51.6328  51.6259 0006780 277.7856  82.2363 15.48918166590814",
    )
    private val model = OrbitModel(tle)
    private val propagator = DragGuardedPropagator((Sgp4.create(tle) as Sgp4CreateResult.Ready).propagator)

    private fun state(t: Instant) = model.stateAt(t) as OrbitState

    private fun minutes(t: Instant) = Duration.between(tle.epoch, t).toNanos() / 60e9

    @Test
    fun temePositionEqualsGuardedPropagatorExactly() {
        for (min in listOf(0.0, 1.0, 93.5, 1440.0, 14.0 * 1440.0, 20.0 * 1440.0)) {
            val t = tle.epoch.plusNanos(Math.round(min * 60e9))
            val expected = propagator.propagate(minutes(t)) as PropagationResult.Success
            val s = state(t)
            assertEquals("min=$min", expected.positionKm, s.positionTemeKm)
            assertEquals("min=$min", expected.velocityKmPerSec, s.velocityTemeKmPerSec)
        }
        assertEquals(tle.epoch, model.epoch)
        assertEquals(tle.meanMotionRevPerDay, model.meanMotionRevPerDay, 0.0)
    }

    @Test
    fun subSatelliteLatitudeBoundedByInclination() {
        // Inclination bounds the geocentric latitude; the geodetic sub-point sits up to about 0.19 deg higher.
        var maxLat = 0.0
        var maxGeocentric = 0.0
        for (i in 0..1440) {
            val s = state(tle.epoch.plusSeconds(60L * i))
            maxLat = maxOf(maxLat, abs(s.latitudeDeg))
            maxGeocentric = maxOf(maxGeocentric, Math.toDegrees(abs(asin(s.positionEcefKm.z / s.positionEcefKm.norm()))))
        }
        assertTrue("max geocentric lat $maxGeocentric", maxGeocentric <= tle.inclinationDeg + 0.1)
        assertTrue("max geocentric lat $maxGeocentric", maxGeocentric > tle.inclinationDeg - 1.0)
        assertTrue("max geodetic lat $maxLat", maxLat <= tle.inclinationDeg + 0.25)
    }

    @Test
    fun heightStaysInIssBandOverFourteenDays() {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in 0..(14 * 1440)) {
            val h = state(tle.epoch.plusSeconds(60L * i)).heightKm
            lo = minOf(lo, h)
            hi = maxOf(hi, h)
        }
        assertTrue("min $lo max $hi", lo >= 380.0 && hi <= 460.0)
    }

    @Test
    fun sunAndMoonDirectionsAreUnitAndMatchAstro() {
        val t = tle.epoch.plusSeconds(5 * 3600L + 123)
        val s = state(t)
        val jdTt = TimeScales.julianDateTt(t)
        val sun = Sun.apparent(jdTt).positionTemeKm(jdTt)
        val moon = Moon.apparent(jdTt).positionTemeKm(jdTt) - s.positionTemeKm
        assertEquals(1.0, s.sunDirectionTeme.norm(), 1e-12)
        assertEquals(1.0, s.moonDirectionTeme.norm(), 1e-12)
        assertEquals(sun.norm(), s.sunDistanceKm, 1e-6)
        assertTrue((s.sunDirectionTeme - sun * (1.0 / sun.norm())).norm() < 1e-12)
        assertTrue((s.moonDirectionTeme - moon * (1.0 / moon.norm())).norm() < 1e-12)
        assertTrue(s.moonIlluminatedFraction in 0.0..1.0)
        assertTrue(s.sunlitFraction in 0.0..1.0)
    }

    @Test
    fun dragFrozenAndAgeAroundFreezeAge() {
        val freeze = tle.epoch.plus(Duration.ofDays(14))
        val at0 = state(tle.epoch)
        assertEquals(0.0, at0.tleAgeDays, 0.0)
        assertFalse(at0.dragFrozen)
        val before = state(freeze.minusSeconds(1))
        assertFalse(before.dragFrozen)
        assertEquals(14.0 - 1.0 / 86400.0, before.tleAgeDays, 1e-9)
        val after = state(freeze.plusSeconds(1))
        assertTrue(after.dragFrozen)
        assertEquals(14.0 + 1.0 / 86400.0, after.tleAgeDays, 1e-9)
    }
}
