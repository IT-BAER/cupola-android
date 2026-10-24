package com.baer.cupola.core.render.scene

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Steady exposure from an analytic estimate of the scene brightness (decision 11), in linear shader units (a
 * white Lambertian surface facing the Sun has radiance 1): the sunlit Earth in view ([Scene.earthLight]), the sunlit
 * air in front of it ([Scene.airLight], which dominates at the terminator), the moonlit share, and a floor for the
 * interior emissive and the night lights that keeps night passes visible.
 */
object Exposure {
    const val MIN = 0.01
    const val MAX = 1.0e6

    /** Linear exposure that maps the estimated mean radiance to this value before the tone map. */
    const val KEY = 0.35

    /** Interior lights and the night side at night (city lights, airglow), shader units. */
    const val NIGHT_FLOOR = 2.5e-5

    fun luminance(scene: Scene): Double =
        scene.earthLight() + scene.airLight() + Scene.EARTH_ALBEDO * 0.5 * scene.moonLight() + scene.glassGlare() + NIGHT_FLOOR

    fun steady(scene: Scene): Double = (KEY / luminance(scene)).coerceIn(MIN, MAX)
}

/**
 * Exposure with slow adaptation: first-order lag of [tauSeconds] in log space. The first update, and any update
 * more than [snapAfterSeconds] after the previous one (the wallpaper was hidden), jumps to the target.
 */
class AutoExposure(val tauSeconds: Double = 4.0, val snapAfterSeconds: Double = 2.0) {
    private var log2Exposure = Double.NaN
    private var lastNanos = 0L

    fun update(target: Double, nowNanos: Long): Double {
        val log2Target = ln(target) / LN2
        val dt = (nowNanos - lastNanos) / 1e9
        lastNanos = nowNanos
        log2Exposure = if (log2Exposure.isNaN() || dt !in 0.0..snapAfterSeconds) {
            log2Target
        } else {
            log2Exposure + (log2Target - log2Exposure) * (1 - exp(-dt / tauSeconds))
        }
        return 2.0.pow(log2Exposure)
    }

    private companion object {
        val LN2 = ln(2.0)
    }
}
