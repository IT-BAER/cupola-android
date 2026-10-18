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
    private val rateVote = FrameRateVote<Surface> { surface, fps ->
        if (surface.isValid) surface.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
    }

    /** Last state applied on the render thread; read by the debug gate. */
    @Volatile var engineState = EngineState()
        private set

    var surface: Surface?
        get() = rateVote.surface
        set(value) {
            rateVote.surface = value
        }

    /** Last [FrameScheduler.moving] pushed to the scheduler. */
    var scheduledMoving = false
        private set

    var moving = false
        private set

    fun apply(state: EngineState, scheduler: FrameScheduler) {
        engineState = state
        if (state.visible && !wasVisible) look.onVisible()
        wasVisible = state.visible
        clock.setPreview(state.preview)
        look.xOffset = state.xOffset.toDouble()
        look.reducedMotion = state.reducedMotion
        scheduler.setVisible(state.visible)
        scheduler.batterySaver = state.batterySaver
        syncRate(scheduler)
    }

    fun onOrientation(orientation: Orientation) {
        if (!Gate.sensorBlocked()) look.setOrientation(orientation.yawRad, orientation.pitchRad)
    }

    private var tracing = false

    /** Feeds the gate trace, if one runs, like a sensor sample; the reference is recaptured at both ends. */
    private fun feedTrace() {
        val traced = Gate.traceOrientation(System.nanoTime())
        if ((traced != null) != tracing) look.onVisible()
        tracing = traced != null
        if (traced != null) look.setOrientation(traced.yawRad, traced.pitchRad)
    }

    /** Frame inputs in the [FrameInputs] layout; unused slots stay zero. */
    fun nextFrameInputs(): FloatArray {
        feedTrace()
        val view = look.update()
        moving = view.isMoving
        val inputs = FloatArray(FrameInputs.COUNT)
        inputs[FrameInputs.TIME_SECONDS] = (clock.nowUtcMillis() % MILLIS_PER_DAY / 1000.0).toFloat()
        inputs[FrameInputs.LOOK_YAW_DEG] = view.yawDeg.toFloat()
        inputs[FrameInputs.LOOK_PITCH_DEG] = view.pitchDeg.toFloat()
        Gate.applyLoad(inputs)
        return inputs
    }

    fun applyMoving(scheduler: FrameScheduler) {
        scheduledMoving = moving
        scheduler.moving = moving
        syncRate(scheduler)
    }

    private fun syncRate(scheduler: FrameScheduler) = rateVote.sync(scheduler.targetFps)

    private companion object {
        // Until P3 loads the bundled TLE.
        const val ISS_MEAN_MOTION_REV_PER_DAY = 15.5
        const val MILLIS_PER_DAY = 86_400_000L
    }
}

/**
 * Sends a frame-rate vote when the rate changes or [surface] is set. WallpaperService reuses one Surface
 * object across recreation, so a set (main thread) bumps a generation instead of relying on identity.
 */
internal class FrameRateVote<S : Any>(private val send: (surface: S, fps: Int) -> Unit) {
    @Volatile private var generation = 0

    @Volatile var surface: S? = null
        set(value) {
            field = value
            generation++
        }

    // Render thread only.
    private var sentGeneration = -1
    private var sentFps = -1

    fun sync(fps: Int) {
        val current = generation
        if (fps == sentFps && current == sentGeneration) return
        sentFps = fps
        sentGeneration = current
        surface?.let { send(it, fps) }
    }
}
