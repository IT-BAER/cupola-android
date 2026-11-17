package com.baer.cupola.wallpaper

import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.ceil

/** Frame total, last-second frame rate, GPU-time percentiles and render scale (last, minimum since the reset) of one surface. Thread-safe. */
internal class GateStats {
    private val stamps = ArrayDeque<Long>()
    private var gpuMs = DoubleArray(INITIAL_CAPACITY)
    private var gpuCount = 0

    /** Frames since creation; [reset] keeps it so a hidden engine's total stays comparable. */
    var frames = 0L
        @Synchronized get
        private set

    /** Earth render scale of the last recorded frame; 0 before the first. */
    var scale = 0f
        @Synchronized get
        private set
    private var scaleMin = Float.NaN

    /** [gpuMs] below zero means unknown: the frame counts, the percentiles skip it. [renderScale] is the frame's Earth scale. */
    @Synchronized
    fun record(nowNs: Long, gpuMs: Double, renderScale: Float) {
        frames++
        stamps.addLast(nowNs)
        trim(nowNs)
        scale = renderScale
        scaleMin = if (scaleMin.isNaN()) renderScale else minOf(scaleMin, renderScale)
        if (gpuMs < 0) return
        if (gpuCount == this.gpuMs.size) this.gpuMs = this.gpuMs.copyOf(gpuCount * 2)
        this.gpuMs[gpuCount++] = gpuMs
    }

    @Synchronized
    fun reset() {
        gpuCount = 0
        scaleMin = Float.NaN
    }

    @Synchronized
    fun fps1s(nowNs: Long): Int {
        trim(nowNs)
        return stamps.count { it >= nowNs - WINDOW_NS }
    }

    @Synchronized
    fun line(id: Int, preview: Boolean, visible: Boolean, nowNs: Long, load: Int): String {
        val sorted = gpuMs.copyOf(gpuCount).also { it.sort() }
        fun percentile(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[ceil(p * sorted.size).toInt().coerceAtLeast(1) - 1]
        return String.format(
            Locale.ROOT,
            "gate id=%d preview=%b visible=%b frames=%d fps1s=%d gpu_p50_ms=%.2f gpu_p95_ms=%.2f gpu_max_ms=%.2f load=%d scale=%.2f scale_min=%.2f",
            id, preview, visible, frames, fps1s(nowNs), percentile(0.5), percentile(0.95), sorted.lastOrNull() ?: 0.0, load,
            scale, scaleMin,
        )
    }

    private fun trim(nowNs: Long) {
        while (stamps.isNotEmpty() && stamps.first < nowNs - WINDOW_NS) stamps.removeFirst()
    }

    private companion object {
        const val WINDOW_NS = 1_000_000_000L
        const val INITIAL_CAPACITY = 4096
    }
}
