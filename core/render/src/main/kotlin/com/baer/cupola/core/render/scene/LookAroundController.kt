package com.baer.cupola.core.render.scene

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tanh

/** Camera angles relative to the rest direction. */
data class LookAroundOutput(val yawDeg: Double, val pitchDeg: Double, val isMoving: Boolean)

/**
 * Device-orientation look-around with launcher pan, spring-back and idle float.
 * Orientation is yaw/pitch in radians (derived from TYPE_GAME_ROTATION_VECTOR by the adapter).
 * The offset from the reference is soft-clamped as `strength * 20 * tanh(offset / 20)`, so it
 * never exceeds 20 deg times strength and is linear near the rest direction.
 */
class LookAroundController(private val clockNs: () -> Long) {
    var strength = 1.0
    var xOffset = 0.5
    var idleFloat = false
    var reducedMotion = false

    private var hasRef = false
    private var needsRef = true
    private var refYaw = 0.0
    private var refPitch = 0.0
    private var oriYaw = 0.0
    private var oriPitch = 0.0
    private var sampleNs = NONE
    private var sampleYaw = 0.0
    private var samplePitch = 0.0
    private var lastMotionNs = 0L
    private var velYaw = 0.0
    private var velPitch = 0.0
    private var springMoving = false

    private var lastUpdateNs = NONE
    private var smoothYaw = 0.0
    private var smoothPitch = 0.0
    private var moving = false

    /** Marks the reference to be recaptured from the next orientation sample. */
    fun onVisible() {
        needsRef = true
        hasRef = false
        sampleNs = NONE
        lastMotionNs = clockNs()
        velYaw = 0.0
        velPitch = 0.0
        springMoving = false
    }

    /**
     * [timestampNs] is the sample time (any monotonic base, e.g. SensorEvent.timestamp). Speed uses it rather
     * than the delivery time, because a busy render thread delivers queued samples about 1 ms apart.
     */
    fun setOrientation(yawRad: Double, pitchRad: Double, timestampNs: Long = clockNs()) {
        oriYaw = Math.toDegrees(yawRad)
        oriPitch = Math.toDegrees(pitchRad)
        if (needsRef) {
            refYaw = oriYaw
            refPitch = oriPitch
            needsRef = false
            hasRef = true
        }
        if (sampleNs == NONE) {
            sampleNs = timestampNs
        } else if (timestampNs - sampleNs >= MIN_SAMPLE_NS) {
            val dtS = (timestampNs - sampleNs) / NS
            val speed = angle(wrap(oriYaw - sampleYaw), oriPitch - samplePitch) / dtS
            if (speed >= STILL_DEG_PER_S) lastMotionNs = clockNs()
            sampleNs = timestampNs
        } else {
            return
        }
        sampleYaw = oriYaw
        samplePitch = oriPitch
    }

    fun update(): LookAroundOutput {
        val now = clockNs()
        val dtS = if (lastUpdateNs == NONE) 0.0 else (now - lastUpdateNs) / NS
        if (dtS > 0.0 || lastUpdateNs == NONE) lastUpdateNs = now
        if (reducedMotion) {
            smoothYaw = 0.0
            smoothPitch = 0.0
            moving = false
            return LookAroundOutput(0.0, 0.0, false)
        }
        if (dtS > 0.0) step(now, dtS)
        val t = now / NS
        return LookAroundOutput(
            smoothYaw + if (idleFloat) floatOffset(t, YAW_FLOAT) else 0.0,
            smoothPitch + if (idleFloat) floatOffset(t, PITCH_FLOAT) else 0.0,
            moving,
        )
    }

    private fun step(now: Long, dtS: Double) {
        if (hasRef && now - lastMotionNs >= STILL_NS) {
            val k = exp(-OMEGA * dtS)
            var x = wrap(refYaw - oriYaw)
            var y = refPitch - oriPitch
            val ax = velYaw + OMEGA * x
            val ay = velPitch + OMEGA * y
            val nx = (x + ax * dtS) * k
            val ny = (y + ay * dtS) * k
            velYaw = (velYaw - OMEGA * ax * dtS) * k
            velPitch = (velPitch - OMEGA * ay * dtS) * k
            x = nx
            y = ny
            springMoving = angle(x, y) >= SETTLE_DEG || angle(velYaw, velPitch) >= SETTLE_DEG_PER_S
            if (!springMoving) {
                x = 0.0
                y = 0.0
                velYaw = 0.0
                velPitch = 0.0
            }
            refYaw = oriYaw + x
            refPitch = oriPitch + y
        } else {
            velYaw = 0.0
            velPitch = 0.0
            springMoving = false
        }
        val lookYaw = if (hasRef) softClamp(wrap(oriYaw - refYaw)) else 0.0
        val lookPitch = if (hasRef) softClamp(oriPitch - refPitch) else 0.0
        val targetYaw = lookYaw + (xOffset - 0.5) * PAN_RANGE_DEG
        val a = 1.0 - exp(-dtS / TAU_S)
        val dy = (targetYaw - smoothYaw) * a
        val dp = (lookPitch - smoothPitch) * a
        smoothYaw += dy
        smoothPitch += dp
        moving = springMoving || hypot(dy, dp) / dtS > MOVING_DEG_PER_S
    }

    /**
     * Length in degrees on the view sphere of a small yaw/pitch step at the current pitch. Near +-90 deg pitch
     * (phone flat) sensor noise swings yaw by degrees while the view direction moves by hundredths.
     */
    private fun angle(yawDeg: Double, pitchDeg: Double) = hypot(yawDeg * cos(Math.toRadians(oriPitch)), pitchDeg)

    private fun softClamp(offsetDeg: Double) = strength * LIMIT_DEG * tanh(offsetDeg / LIMIT_DEG)

    private fun wrap(deg: Double): Double {
        val m = (deg + 180.0) % 360.0
        return (if (m < 0) m + 360.0 else m) - 180.0
    }

    /** One sine component: frequency in Hz, amplitude in degrees, phase in radians. */
    private class Sine(val hz: Double, val amp: Double, val phase: Double)

    private fun floatOffset(t: Double, sines: List<Sine>) =
        sines.sumOf { it.amp * sin(2.0 * PI * it.hz * t + it.phase) }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val NS = 1e9
        const val MIN_SAMPLE_NS = 1_000_000L
        const val STILL_NS = 1_000_000_000L
        const val STILL_DEG_PER_S = 0.5
        const val MOVING_DEG_PER_S = 0.2
        const val LIMIT_DEG = 20.0
        const val PAN_RANGE_DEG = 8.0
        const val TAU_S = 0.08
        const val OMEGA = 3.9
        const val SETTLE_DEG = 0.1
        const val SETTLE_DEG_PER_S = 0.5

        // Amplitudes of each set sum to 2.0 deg.
        val YAW_FLOAT = listOf(Sine(0.07, 1.0, 0.0), Sine(0.13, 0.6, 1.3), Sine(0.19, 0.4, 2.6))
        val PITCH_FLOAT = listOf(Sine(0.06, 1.0, 0.7), Sine(0.11, 0.6, 2.1), Sine(0.17, 0.4, 0.4))
    }
}
