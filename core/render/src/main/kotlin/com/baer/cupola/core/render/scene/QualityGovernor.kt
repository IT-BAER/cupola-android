package com.baer.cupola.core.render.scene

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Adapts the quality step (higher index is higher quality) to frame load and thermal headroom.
 * Frames at [REFERENCE_INTERVAL_MS] (motion) are judged by missed vsync intervals, because the GPU clock ramps
 * with load and GPU time then measures the clock, not the work. Slower frames (still mode) are judged by GPU time:
 * [budgetMs] is the p95 allowed for a frame of [REFERENCE_INTERVAL_MS].
 * [clockNs] must be the frame (vsync) time. Headroom follows Android semantics: 1.0 means severe throttling is
 * expected, NaN when unsupported.
 */
class QualityGovernor(
    steps: Int,
    private val defaultStep: Int,
    private val budgetMs: Double = 12.0,
    private val clockNs: () -> Long,
    private val onChange: (from: Int, to: Int) -> Unit = { _, _ -> },
) {
    init {
        require(defaultStep in 0 until steps) { "defaultStep must be within 0 until steps" }
    }

    var step = defaultStep
        private set

    private val window = DoubleArray(MOVING_WINDOW_FRAMES)
    private var count = 0
    private var next = 0
    private var headroom = Float.NaN
    private var lastChangeNs = NONE
    private var calmSinceNs = NONE
    private var lastFrameNs = NONE
    private var moving = false

    fun onThermalHeadroom(value: Float) {
        headroom = value
    }

    /**
     * A frame scheduled every [intervalMs] that took [gpuMs]. In still mode the GPU time is scaled to the 60 fps
     * interval, so a GPU that lowers its clock to fill a longer interval does not read as overloaded.
     */
    fun onFrame(gpuMs: Double, intervalMs: Double = REFERENCE_INTERVAL_MS) {
        val now = clockNs()
        val nowMoving = intervalMs <= REFERENCE_INTERVAL_MS
        if (nowMoving != moving) {
            moving = nowMoving
            restartWindow()
        }
        window[next] = if (moving) missedIntervals(now, intervalMs) else gpuMs * REFERENCE_INTERVAL_MS / intervalMs
        lastFrameNs = now
        val frames = if (moving) MOVING_WINDOW_FRAMES else WINDOW_FRAMES
        next = (next + 1) % frames
        if (count < frames) count++

        val hot = headroom > HEADROOM_DOWN
        val full = count == frames
        val over: Boolean
        val light: Boolean
        if (moving) {
            val missed = if (full) window.sum() else 0.0
            over = missed > MISSED_DOWN
            light = missed <= MISSED_CALM
        } else {
            val p95 = if (full) p95() else Double.NaN
            over = p95 > budgetMs
            light = p95 < budgetMs * CALM_BUDGET_FRACTION
        }
        val calm = full && light && (headroom.isNaN() || headroom < HEADROOM_UP)
        if (!calm) calmSinceNs = NONE else if (calmSinceNs == NONE) calmSinceNs = now

        if (lastChangeNs != NONE && now - lastChangeNs < HYSTERESIS_NS) return
        if ((hot || over) && step > 0) {
            change(step - 1, now)
        } else if (calmSinceNs != NONE && now - calmSinceNs >= CALM_NS && step < defaultStep) {
            change(step + 1, now)
        }
    }

    private fun change(to: Int, now: Long) {
        val from = step
        step = to
        onChange(from, to)
        lastChangeNs = now
        restartWindow()
    }

    private fun restartWindow() {
        calmSinceNs = NONE
        count = 0
        next = 0
        lastFrameNs = NONE
    }

    /** Vsync intervals skipped before this frame; a gap over [PAUSE_NS] is a pause (hidden, idle), not a miss. */
    private fun missedIntervals(now: Long, intervalMs: Double): Double {
        if (lastFrameNs == NONE) return 0.0
        val intervals = (now - lastFrameNs) / 1e6 / intervalMs
        if (now - lastFrameNs > PAUSE_NS || intervals <= MISSED_RATIO) return 0.0
        return (intervals.roundToInt() - 1).coerceAtLeast(1).toDouble()
    }

    private fun p95(): Double = window.copyOf(WINDOW_FRAMES).also { it.sort() }[ceil(WINDOW_FRAMES * 0.95).toInt() - 1]

    companion object {
        /** The frame interval [budgetMs] applies to (60 fps). */
        const val REFERENCE_INTERVAL_MS = 1000.0 / 60

        private const val NONE = Long.MIN_VALUE
        private const val WINDOW_FRAMES = 120
        private const val MOVING_WINDOW_FRAMES = 240
        private const val HEADROOM_DOWN = 0.85f
        private const val HEADROOM_UP = 0.6f
        private const val CALM_BUDGET_FRACTION = 0.75

        /** A 90 Hz panel alternates 11.1 and 22.2 ms gaps at 60 fps (1.33 intervals); a 120 Hz slip is 1.5. */
        private const val MISSED_RATIO = 1.4

        /**
         * A35 traces miss 2-4 % of vsyncs in motion at every render scale (more just after the GPU clock ramps up),
         * so only a rate a lower scale can fix counts: over 10 % (about 54 fps).
         */
        private const val MISSED_DOWN = 24.0
        private const val MISSED_CALM = 6.0
        private const val PAUSE_NS = 250_000_000L
        private const val CALM_NS = 10_000_000_000L
        private const val HYSTERESIS_NS = 5_000_000_000L
    }
}
