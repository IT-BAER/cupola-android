package com.baer.cupola.orbit

import com.baer.cupola.orbit.astro.MoonPhase
import com.baer.cupola.orbit.astro.Moon
import com.baer.cupola.orbit.astro.Sun
import com.baer.cupola.orbit.eclipse.Eclipse
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.sgp4.DragGuardedPropagator
import com.baer.cupola.orbit.sgp4.PropagationResult
import com.baer.cupola.orbit.sgp4.Sgp4
import com.baer.cupola.orbit.sgp4.Sgp4CreateResult
import com.baer.cupola.orbit.sgp4.Sgp4Error
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.Sidereal
import com.baer.cupola.orbit.time.TimeScales
import com.baer.cupola.orbit.tle.Tle
import java.time.Duration
import java.time.Instant

sealed interface OrbitStateResult {
    data class Failure(val error: Sgp4Error) : OrbitStateResult
}

/** Everything derived from the TLE at one instant. Angles in degrees unless named Rad; TEME/ECEF in km. */
data class OrbitState(
    val instant: Instant,
    val positionTemeKm: Vector3,
    val velocityTemeKmPerSec: Vector3,
    val positionEcefKm: Vector3,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val heightKm: Double,
    val gmstRad: Double,
    val sunDirectionTeme: Vector3,
    val sunDistanceKm: Double,
    /** Unit vector from the ISS to the Moon (topocentric), TEME. */
    val moonDirectionTeme: Vector3,
    val moonIlluminatedFraction: Double,
    /** 0 in umbra, 1 in full sunlight, linear in between. */
    val sunlitFraction: Double,
    val tleAgeDays: Double,
    /** True when [instant] is later than the TLE epoch plus the drag freeze age. */
    val dragFrozen: Boolean,
) : OrbitStateResult

/** The app's own orbit: [DragGuardedPropagator] over [tle], with frames, Sun, Moon and shadow derived from it. */
class OrbitModel(
    tle: Tle,
    freezeAfterMinutes: Double = DragGuardedPropagator.DEFAULT_FREEZE_AFTER_MINUTES,
) {
    val epoch: Instant = tle.epoch
    val meanMotionRevPerDay: Double = tle.meanMotionRevPerDay

    private val propagator = DragGuardedPropagator(
        when (val created = Sgp4.create(tle)) {
            is Sgp4CreateResult.Ready -> created.propagator
            is Sgp4CreateResult.DeepSpaceNotSupported ->
                throw IllegalArgumentException("Deep-space orbit (period ${created.periodMinutes} min) is not supported")
        },
        freezeAfterMinutes,
    )

    private fun minutesSinceEpoch(instant: Instant): Double = Duration.between(epoch, instant).toNanos() / NANOS_PER_MINUTE

    /** ECEF position only (cheap, no Sun/Moon); null when SGP4 fails. UT1 = UTC as in [TimeScales]. */
    fun ecefPositionKm(instant: Instant): Vector3? {
        val r = propagator.propagate(minutesSinceEpoch(instant)) as? PropagationResult.Success ?: return null
        return Frames.temeToEcef(r.positionKm, TimeScales.julianDateUt1(instant))
    }

    fun stateAt(instant: Instant): OrbitStateResult {
        val minutes = minutesSinceEpoch(instant)
        val r = when (val p = propagator.propagate(minutes)) {
            is PropagationResult.Failure -> return OrbitStateResult.Failure(p.error)
            is PropagationResult.Success -> p
        }
        val jdUt1 = TimeScales.julianDateUt1(instant)
        val jdTt = TimeScales.julianDateTt(instant)
        val ecef = Frames.temeToEcef(r.positionKm, jdUt1)
        val geo = Frames.ecefToGeodetic(ecef)
        val sunKm = Sun.apparent(jdTt).positionTemeKm(jdTt)
        val moonKm = Moon.apparent(jdTt).positionTemeKm(jdTt)
        val toMoon = moonKm - r.positionKm
        return OrbitState(
            instant = instant,
            positionTemeKm = r.positionKm,
            velocityTemeKmPerSec = r.velocityKmPerSec,
            positionEcefKm = ecef,
            latitudeDeg = Math.toDegrees(geo.latitudeRad),
            longitudeDeg = Math.toDegrees(geo.longitudeRad),
            heightKm = geo.heightKm,
            gmstRad = Sidereal.gmstRad(jdUt1),
            sunDirectionTeme = sunKm * (1.0 / sunKm.norm()),
            sunDistanceKm = sunKm.norm(),
            moonDirectionTeme = toMoon * (1.0 / toMoon.norm()),
            moonIlluminatedFraction = MoonPhase.illuminatedFraction(sunKm, moonKm, r.positionKm),
            sunlitFraction = Eclipse.sunlitFraction(r.positionKm, sunKm),
            tleAgeDays = minutes / MINUTES_PER_DAY,
            dragFrozen = minutes > propagator.freezeAfterMinutes,
        )
    }

    private companion object {
        const val NANOS_PER_MINUTE = 60e9
        const val MINUTES_PER_DAY = 1440.0
    }
}
