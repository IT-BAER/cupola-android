package com.baer.cupola.wallpaper

import kotlin.math.PI
import kotlin.math.cos

/**
 * Deterministic 60 s synthetic orientation, a pure function of elapsed time. Keyframes are eased
 * (zero speed at each key), so it is continuous. Angles are degrees from the first sample (0, 0).
 */
internal object RotationTrace {
    const val DURATION_SECONDS = 60.0

    private class Key(val t: Double, val yawDeg: Double, val pitchDeg: Double)

    private val keys = listOf(
        Key(0.0, 0.0, 0.0),
        Key(6.0, 45.0, 0.0), // slow yaw sweep past the +-20 clamp
        Key(8.0, 45.0, 0.0), // hold 1: spring-back
        Key(14.0, -30.0, 25.0), // wide diagonal sweep
        Key(20.0, -30.0, -25.0), // pitch sweep past the clamp
        Key(23.0, 20.0, -25.0), // faster yaw sweep
        Key(30.0, 0.0, 0.0), // return
        Key(37.0, 0.0, 0.0), // idle (floating) rest
        Key(39.0, 0.0, 0.0), // hold 2: idle
        Key(45.0, 30.0, 15.0), // medium sweep
        Key(45.4, -40.0, -10.0), // fast flick
        Key(52.0, 10.0, 10.0),
        Key(60.0, 0.0, 0.0),
    )

    /** Orientation at [elapsedSeconds], or null outside 0..[DURATION_SECONDS]. */
    fun sample(elapsedSeconds: Double): Orientation? {
        if (elapsedSeconds < 0.0 || elapsedSeconds > DURATION_SECONDS) return null
        val i = keys.indexOfLast { it.t <= elapsedSeconds }.coerceAtMost(keys.size - 2)
        val a = keys[i]
        val b = keys[i + 1]
        val u = (1.0 - cos(PI * (elapsedSeconds - a.t) / (b.t - a.t))) / 2.0
        return Orientation(
            Math.toRadians(a.yawDeg + (b.yawDeg - a.yawDeg) * u),
            Math.toRadians(a.pitchDeg + (b.pitchDeg - a.pitchDeg) * u),
        )
    }
}
