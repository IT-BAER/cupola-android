package com.baer.cupola.core.render.scene

import kotlin.math.ceil

/**
 * Adapts the quality step (higher index is higher quality) to GPU frame time and thermal headroom.
 * Headroom follows Android semantics: 1.0 means severe throttling is expected, NaN when unsupported.
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

    private val window = DoubleArray(WINDOW_FRAMES)
    private var count = 0
    private var next = 0
    private var headroom = Float.NaN
    private var lastChangeNs = NONE
    private var calmSinceNs = NONE

    fun onThermalHeadroom(value: Float) {
        headroom = value
    }

    fun onFrame(gpuMs: Double) {
        val now = clockNs()
        window[next] = gpuMs
        next = (next + 1) % WINDOW_FRAMES
        if (count < WINDOW_FRAMES) count++

        val hot = headroom > HEADROOM_DOWN
        val p95 = if (count == WINDOW_FRAMES) p95() else Double.NaN
        val calm = count == WINDOW_FRAMES && p95 < budgetMs * CALM_BUDGET_FRACTION &&
            (headroom.isNaN() || headroom < HEADROOM_UP)
        if (!calm) calmSinceNs = NONE else if (calmSinceNs == NONE) calmSinceNs = now

        if (lastChangeNs != NONE && now - lastChangeNs < HYSTERESIS_NS) return
        if ((hot || p95 > budgetMs) && step > 0) {
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
        calmSinceNs = NONE
        count = 0
        next = 0
    }

    private fun p95(): Double = window.sortedArray()[ceil(WINDOW_FRAMES * 0.95).toInt() - 1]

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val WINDOW_FRAMES = 120
        const val HEADROOM_DOWN = 0.85f
        const val HEADROOM_UP = 0.6f
        const val CALM_BUDGET_FRACTION = 0.75
        const val CALM_NS = 10_000_000_000L
        const val HYSTERESIS_NS = 5_000_000_000L
    }
}
