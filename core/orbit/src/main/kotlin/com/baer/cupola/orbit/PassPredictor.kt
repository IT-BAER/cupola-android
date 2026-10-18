package com.baer.cupola.orbit

import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.sgp4.Vector3
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/** One visible pass; [maxElevationDeg] is the peak elevation inside the window, [minRangeKm] the slant range at [closestApproach]. */
data class Pass(
    val start: Instant,
    val closestApproach: Instant,
    val end: Instant,
    val maxElevationDeg: Double,
    val minRangeKm: Double,
)

/** Passes over a city on the app's own orbit ([OrbitModel]). */
class PassPredictor(private val model: OrbitModel) {

    /**
     * Next pass whose visibility window (ISS elevation >= [minElevationDeg] above the city's WGS-84 local
     * horizon, height 0) ends after [after] and starts within [searchDays] of it. A pass already in progress
     * at [after] is returned with its true [Pass.start], which may be before [after]. Null when none is found.
     */
    fun nextPass(
        cityLatDeg: Double,
        cityLonDeg: Double,
        after: Instant,
        minElevationDeg: Double = 10.0,
        searchDays: Int = 7,
    ): Pass? {
        val site = Site(cityLatDeg, cityLonDeg)
        // Positive means visible; a failed propagation counts as not visible.
        val f = { sec: Double -> site.elevationDeg(model.ecefPositionKm(at(after, sec))) - minElevationDeg }
        val searchEnd = searchDays * 86400.0

        val v = visiblePoint(f, searchEnd) ?: return null
        return build(site, after, edge(v, -SCAN_STEP_S, f), edge(v, SCAN_STEP_S, f))
    }

    /**
     * A time inside a window: [f] >= 0 at a sample, or at the refined peak of a sampled local maximum that is
     * within [PEAK_MARGIN_DEG] below the threshold (a window shorter than the step), never before time 0.
     * Null past [searchEnd].
     */
    private fun visiblePoint(f: (Double) -> Double, searchEnd: Double): Double? {
        if (f(0.0) >= 0.0) return 0.0
        var before = Double.NEGATIVE_INFINITY
        var here = f(0.0)
        var t = 0.0
        while (t <= searchEnd) {
            val next = f(t + SCAN_STEP_S)
            if (next >= 0.0) return t + SCAN_STEP_S
            if (here > before && here >= next && here > -PEAK_MARGIN_DEG) {
                val peak = goldenMin(maxOf(0.0, t - SCAN_STEP_S), t + SCAN_STEP_S) { -f(it) }
                if (f(peak) >= 0.0) return peak
            }
            before = here
            here = next
            t += SCAN_STEP_S
        }
        return null
    }

    /** Crossing of [f] walking from the visible time [v] in steps of [step] seconds (signed) to the first non-visible sample. */
    private fun edge(v: Double, step: Double, f: (Double) -> Double): Double {
        var visible = v
        var out = v + step
        while (f(out) >= 0.0) {
            visible = out
            out += step
        }
        return bisect(out, visible, f)
    }

    /** Crossing of [f] between a non-visible and a visible time (either order), to [TOLERANCE_S]. */
    private fun bisect(notVisible: Double, visible: Double, f: (Double) -> Double): Double {
        var a = notVisible
        var b = visible
        while (abs(b - a) > TOLERANCE_S) {
            val m = 0.5 * (a + b)
            if (f(m) >= 0.0) b = m else a = m
        }
        return 0.5 * (a + b)
    }

    private fun build(site: Site, after: Instant, startSec: Double, endSec: Double): Pass {
        fun range(s: Double) = site.rangeKm(model.ecefPositionKm(at(after, s)))
        fun elevation(s: Double) = site.elevationDeg(model.ecefPositionKm(at(after, s)))
        val closest = goldenMin(startSec, endSec, ::range)
        val peak = goldenMin(startSec, endSec) { -elevation(it) }
        return Pass(at(after, startSec), at(after, closest), at(after, endSec), elevation(peak), range(closest))
    }

    private fun goldenMin(lo: Double, hi: Double, f: (Double) -> Double): Double {
        var a = lo
        var b = hi
        var c = b - GOLDEN * (b - a)
        var d = a + GOLDEN * (b - a)
        var fc = f(c)
        var fd = f(d)
        while (b - a > TOLERANCE_S) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - GOLDEN * (b - a); fc = f(c)
            } else {
                a = c; c = d; fc = fd
                d = a + GOLDEN * (b - a); fd = f(d)
            }
        }
        return 0.5 * (a + b)
    }

    private fun at(base: Instant, sec: Double): Instant = base.plusNanos(round(sec * 1e9).toLong())

    private class Site(latDeg: Double, lonDeg: Double) {
        private val lat = Math.toRadians(latDeg)
        private val lon = Math.toRadians(lonDeg)
        private val ecef = Frames.geodeticToEcef(Geodetic(lat, lon, 0.0))
        private val up = Vector3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))

        fun rangeKm(sat: Vector3?): Double = if (sat == null) Double.POSITIVE_INFINITY else (sat - ecef).norm()

        fun elevationDeg(sat: Vector3?): Double {
            if (sat == null) return -90.0
            val d = sat - ecef
            return Math.toDegrees(asin(((d dot up) / d.norm()).coerceIn(-1.0, 1.0)))
        }
    }

    private companion object {
        // A window shorter than the step (ISS ~7 km/s over a ~1400 km cap) peaks <~0.15 deg above the threshold,
        // so the sampled local maximum within PEAK_MARGIN_DEG is refined and no pass is skipped.
        const val SCAN_STEP_S = 30.0
        const val TOLERANCE_S = 1e-3
        const val PEAK_MARGIN_DEG = 1.0
        val GOLDEN = (sqrt(5.0) - 1.0) / 2.0
    }
}
