package com.baer.cupola.core.render.scene

/**
 * Simulated UTC for the picker preview: one orbit of [meanMotionRevPerDay] per
 * [previewOrbitSeconds] of real time; outside preview it is the real UTC.
 */
class PreviewClock(
    private val monotonicNs: () -> Long,
    private val utcMillis: () -> Long,
    meanMotionRevPerDay: Double,
    val previewOrbitSeconds: Double = 25.0,
) {
    private val speedup: Double

    init {
        require(previewOrbitSeconds in 20.0..30.0) { "previewOrbitSeconds must be 20..30" }
        require(meanMotionRevPerDay > 0.0) { "meanMotionRevPerDay must be positive" }
        speedup = 86_400.0 / meanMotionRevPerDay / previewOrbitSeconds
    }

    private var startMonoNs = 0L
    private var startUtcMs = 0L

    var isPreview = false
        private set

    fun setPreview(value: Boolean) {
        if (value && !isPreview) {
            startMonoNs = monotonicNs()
            startUtcMs = utcMillis()
        }
        isPreview = value
    }

    fun nowUtcMillis(): Long {
        if (!isPreview) return utcMillis()
        val elapsedMs = (monotonicNs() - startMonoNs) / 1e6
        return startUtcMs + (elapsedMs * speedup).toLong()
    }
}
