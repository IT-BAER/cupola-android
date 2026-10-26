package com.baer.cupola.core.render.scene

/**
 * Chooses the target frame rate and decides, per Choreographer tick, whether to render.
 * Battery saver renders one frame per hidden-to-visible transition and nothing else.
 */
class FrameScheduler {
    private var visible = false
    private var anchorNs = NO_ANCHOR

    var batterySaver = false
    var moving = false

    /** Frame rate while visible and not moving; the user's Settings choice. */
    var stillFps = STILL_FPS

    /** True from a hidden-to-visible transition until [shouldRender] consumes it. */
    var renderOnce = false
        private set

    val targetFps: Int
        get() = when {
            !visible || batterySaver -> 0
            moving -> MOVING_FPS
            else -> stillFps
        }

    /**
     * Display frame-rate vote: 60 whenever anything renders. A 15 vote lets SurfaceFlinger lower the render
     * rate, which on the A35 delayed the switch back to 60 fps by 116-284 ms; still mode skips vsyncs itself.
     */
    val voteFps: Int
        get() = if (targetFps > 0) 60 else 0

    fun setVisible(value: Boolean) {
        if (value && !visible) renderOnce = true
        if (!value) renderOnce = false
        visible = value
    }

    /** One extra frame while visible (a settings change), also in battery saver; ignored while hidden. */
    fun requestFrame() {
        if (visible) renderOnce = true
    }

    /** Call on every vsync; [vsyncNs] is the display refresh period. */
    fun shouldRender(nowNs: Long, vsyncNs: Long): Boolean {
        if (!visible) return false
        if (renderOnce) {
            renderOnce = false
            anchorNs = nowNs
            return true
        }
        val fps = targetFps
        if (fps == 0) return false
        val intervalNs = 1_000_000_000L / fps
        if (anchorNs != NO_ANCHOR && nowNs + vsyncNs / 2 < anchorNs + intervalNs) return false
        anchorNs = if (anchorNs == NO_ANCHOR) nowNs else anchorNs + intervalNs
        if (nowNs - anchorNs > intervalNs) anchorNs = nowNs
        return true
    }

    companion object {
        const val MOVING_FPS = 60
        const val STILL_FPS = 15
        private const val NO_ANCHOR = Long.MIN_VALUE
    }
}
