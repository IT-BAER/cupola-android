package com.baer.cupola.wallpaper

import android.view.Surface
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.FrameScheduler
import com.baer.cupola.core.render.scene.LookAroundController
import com.baer.cupola.core.render.scene.PreviewClock

/** Engine-side facts the render thread needs; an immutable snapshot is posted on every change. */
internal data class EngineState(
    val visible: Boolean = false,
    val preview: Boolean = false,
    val xOffset: Float = 0.5f,
    val reducedMotion: Boolean = false,
    val batterySaver: Boolean = false,
)

/** Per-engine scene state. Everything except [surface] is touched on the render thread only. */
internal class RenderSession {
    private val look = LookAroundController(System::nanoTime)
    private val clock = PreviewClock(System::nanoTime, System::currentTimeMillis, ISS_MEAN_MOTION_REV_PER_DAY)
    private var wasVisible = false
    private var rateSurface: Surface? = null
    private var rateFps = -1

    @Volatile var surface: Surface? = null

    /** Last [FrameScheduler.moving] pushed to the scheduler. */
    var scheduledMoving = false
        private set

    var moving = false
        private set

    fun apply(state: EngineState, scheduler: FrameScheduler) {
        if (state.visible && !wasVisible) look.onVisible()
        wasVisible = state.visible
        clock.setPreview(state.preview)
        look.xOffset = state.xOffset.toDouble()
        look.reducedMotion = state.reducedMotion
        scheduler.setVisible(state.visible)
        scheduler.batterySaver = state.batterySaver
        syncRate(scheduler)
    }

    fun onOrientation(orientation: Orientation) = look.setOrientation(orientation.yawRad, orientation.pitchRad)

    /**
     * Frame inputs in the [FrameInputs] layout. The layout has no look-around slots yet, so yaw and pitch
     * drive only [moving]; unused slots stay zero.
     */
    fun nextFrameInputs(): FloatArray {
        moving = look.update().isMoving
        val inputs = FloatArray(FrameInputs.COUNT)
        inputs[FrameInputs.TIME_SECONDS] = (clock.nowUtcMillis() % MILLIS_PER_DAY / 1000.0).toFloat()
        return inputs
    }

    fun applyMoving(scheduler: FrameScheduler) {
        scheduledMoving = moving
        scheduler.moving = moving
        syncRate(scheduler)
    }

    private fun syncRate(scheduler: FrameScheduler) {
        val fps = scheduler.targetFps
        val current = surface
        if (fps == rateFps && current === rateSurface) return
        rateFps = fps
        rateSurface = current
        if (current != null && current.isValid) {
            current.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
        }
    }

    private companion object {
        // Until P3 loads the bundled TLE.
        const val ISS_MEAN_MOTION_REV_PER_DAY = 15.5
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
