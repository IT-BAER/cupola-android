package com.baer.cupola.feature.settings

import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.Pass
import com.baer.cupola.orbit.PassPredictor
import com.baer.cupola.orbit.geo.GroundLabel
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Everything the Info screen shows. [timeToDaylight] is [Duration.ZERO] in sunlight, null when no daylight is found. */
data class InfoSnapshot(
    val instant: Instant,
    val groundLabel: GroundLabel,
    val localTime: LocalTime,
    val utcOffsetHours: Int,
    val sunlit: Boolean,
    val timeToDaylight: Duration?,
    val orbitDataEpoch: Instant,
    val retired: Boolean,
    val homeCity: CityEntry?,
    val nextPass: Pass?,
)

/** Pure Info logic over the own orbit model. Not thread-safe (caches the home-city pass); use from one coroutine. */
class InfoLogic(private val orbit: OrbitModel, private val cities: CityRepository) {
    private val passes = PassPredictor(orbit)
    private var cachedPass: Triple<String, Instant, Pass?>? = null

    suspend fun snapshot(now: Instant, metadata: OrbitMetadata, home: CityEntry?): InfoSnapshot {
        val state = orbit.stateAt(now) as? OrbitState ?: throw IllegalStateException("orbit propagation failed at $now")
        val sunlit = state.sunlitFraction > 0.0
        return InfoSnapshot(
            instant = now,
            groundLabel = cities.label(state.latitudeDeg, state.longitudeDeg),
            localTime = localTime(now, state.longitudeDeg),
            utcOffsetHours = utcOffsetHours(state.longitudeDeg),
            sunlit = sunlit,
            timeToDaylight = if (sunlit) Duration.ZERO else timeToDaylight(now),
            orbitDataEpoch = metadata.epoch,
            retired = metadata.retired,
            homeCity = home,
            nextPass = home?.let { nextPass(it, now) },
        )
    }

    private fun timeToDaylight(now: Instant): Duration? {
        var s = 0L
        while (s <= SEARCH_LIMIT_S) {
            s += STEP_S
            val state = orbit.stateAt(now.plusSeconds(s)) as? OrbitState ?: return null
            if (state.sunlitFraction > 0.0) return Duration.ofSeconds(s)
        }
        return null
    }

    // A found pass is reused until it ends; "no pass in 7 days" is rechecked hourly.
    private fun nextPass(home: CityEntry, now: Instant): Pass? {
        cachedPass?.let { (id, at, pass) ->
            val fresh = if (pass == null) now < at.plusSeconds(PASS_RECHECK_S) else pass.end > now
            if (id == home.id && fresh) return pass
        }
        val pass = passes.nextPass(home.city.latDeg, home.city.lonDeg, now)
        cachedPass = Triple(home.id, now, pass)
        return pass
    }

    companion object {
        private const val STEP_S = 10L
        private const val SEARCH_LIMIT_S = 100 * 60L
        private const val PASS_RECHECK_S = 3600L

        /**
         * Simplification: 15 degrees of longitude per hour, rounded to the hour. No time zone database ships,
         * so political zones and daylight saving time are ignored.
         */
        fun utcOffsetHours(lonDeg: Double): Int = (lonDeg / 15.0).roundToInt().coerceIn(-12, 12)

        fun localTime(instant: Instant, lonDeg: Double): LocalTime =
            instant.atOffset(ZoneOffset.ofHours(utcOffsetHours(lonDeg))).toLocalTime()
    }
}
