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
    private var armed = false
    private var peakSpeed = 0.0

    private var lastUpdateNs = NONE
    private var smoothYaw = 0.0
    private var smoothPitch = 0.0
    private var moving = false
    private var lastFastNs = NONE

    /** Marks the reference to be recaptured from the next orientation sample. */
    fun onVisible() {
        needsRef = true
        hasRef = false
        sampleNs = NONE
        lastMotionNs = clockNs()
        velYaw = 0.0
        velPitch = 0.0
        springMoving = false
        armed = false
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
            if (speed > peakSpeed) peakSpeed = speed
            if (speed >= STILL_DEG_PER_S) lastMotionNs = clockNs()
            sampleNs = timestampNs
        } else {
            return
        }
        sampleYaw = oriYaw
        samplePitch = oriPitch
    }

    /** Debug: arming, time since the last fast sample, the peak sample speed since the previous call, and the offset. */
    fun stateLine(): String {
        val peak = peakSpeed
        peakSpeed = 0.0
        return String.format(
            java.util.Locale.ROOT, "armed=%b ref=%b still_ms=%d peak_deg_s=%.2f offset_deg=%.2f",
            armed, hasRef, (clockNs() - lastMotionNs) / 1_000_000, peak, angle(wrap(oriYaw - refYaw), oriPitch - refPitch),
        )
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
        // Until the phone has been held still once after becoming visible, the reference follows the hand: picking
        // the phone up from the unlock pose must not swing the view.
        if (hasRef && !armed) {
            if (now - lastMotionNs >= STILL_NS) {
                armed = true
            } else {
                refYaw = oriYaw
                refPitch = oriPitch
            }
        }
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
        val offYaw = if (hasRef) wrap(oriYaw - refYaw) else 0.0
        val offPitch = if (hasRef) oriPitch - refPitch else 0.0
        // Dead band on the view-sphere offset: tremor and small tilts stay put; beyond it the view eases in
        // (quadratic over EASE_DEG, so the first movement has no kink) and then follows the tilt one to one.
        val m = angle(offYaw, offPitch)
        val d = m - DEAD_DEG
        val eased = if (d <= 0.0) 0.0 else if (d < EASE_DEG) d * d / (2.0 * EASE_DEG) else d - EASE_DEG / 2.0
        val band = if (m > 0.0) eased / m else 0.0
        val lookYaw = softClamp(offYaw * band)
        val lookPitch = softClamp(offPitch * band)
        val targetYaw = lookYaw + (xOffset - 0.5) * PAN_RANGE_DEG
        val a = 1.0 - exp(-dtS / TAU_S)
        val dy = (targetYaw - smoothYaw) * a
        val dp = (lookPitch - smoothPitch) * a
        smoothYaw += dy
        smoothPitch += dp
        if (hypot(dy, dp) / dtS > MOVING_DEG_PER_S) lastFastNs = now
        moving = springMoving || (lastFastNs != NONE && now - lastFastNs < MOVING_HOLD_NS)
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
        // Below this view speed the phone counts as still: hand tremor peaks at about 4 deg/s (Pixel 7 Pro, measured), so
        // the spring and the arming work in the hand; slow drift counts as still too.
        const val STILL_DEG_PER_S = 6.0
        const val MOVING_DEG_PER_S = 0.2

        /** Moving holds this long after the last fast frame, so a drift at the threshold keeps one frame rate. */
        const val MOVING_HOLD_NS = 500_000_000L
        const val LIMIT_DEG = 20.0
        const val DEAD_DEG = 3.0
        const val EASE_DEG = 3.0
        const val PAN_RANGE_DEG = 8.0
        // Output filter and spring-back rate: slow enough that sensor noise and the return do not distract on the home screen.
        const val TAU_S = 0.35
        const val OMEGA = 2.0
        const val SETTLE_DEG = 0.1
        const val SETTLE_DEG_PER_S = 0.5

        // Amplitudes of each set sum to 1.0 deg; against a star-only night window a larger sway reads as fast motion.
        val YAW_FLOAT = listOf(Sine(0.07, 0.5, 0.0), Sine(0.13, 0.3, 1.3), Sine(0.19, 0.2, 2.6))
        val PITCH_FLOAT = listOf(Sine(0.06, 0.5, 0.7), Sine(0.11, 0.3, 2.1), Sine(0.17, 0.2, 0.4))
    }
}
