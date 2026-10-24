package com.baer.cupola.core.render.scene

import java.time.Instant
import kotlin.math.floor

/**
 * Cloud time for earth.frag ([com.baer.cupola.core.render.FrameInputs.CLOUD_FLOW]): two flow-map phases half a
 * [PERIOD_HOURS] apart, each `cycle + fraction`, and a day counter for slow coverage change. Computed in double so
 * the shader's floats stay small; a phase's cycle (which seeds its flow noise) changes only where its fraction wraps,
 * where the shader gives that phase weight 0, so the counter wraps at [CYCLES] without a visible jump.
 */
data class CloudFlow(val phaseA: Float, val phaseB: Float, val days: Float) {
    companion object {
        const val PERIOD_HOURS = 6.0
        const val CYCLES = 1024
        const val DAYS_WRAP = 1024

        fun of(instant: Instant): CloudFlow {
            val seconds = instant.epochSecond + instant.nano / 1e9
            val t = seconds / (PERIOD_HOURS * 3600.0)
            return CloudFlow(phase(t), phase(t + 0.5), (seconds / 86_400.0).mod(DAYS_WRAP.toDouble()).toFloat())
        }

        private fun phase(t: Double): Float {
            val cycle = floor(t)
            return ((cycle.mod(CYCLES.toDouble())) + (t - cycle)).toFloat()
        }
    }
}
