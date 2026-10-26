package com.baer.cupola.wallpaper

import android.util.Log
import android.view.Surface
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.AutoExposure
import com.baer.cupola.core.render.scene.Exposure
import com.baer.cupola.core.render.scene.FrameScheduler
import com.baer.cupola.core.render.scene.LookAroundController
import com.baer.cupola.core.render.scene.PreviewClock
import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.core.render.scene.Scene
import com.baer.cupola.core.render.scene.SceneInputs
import com.baer.cupola.core.render.scene.toSceneState
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.OrbitStateResult
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import com.baer.cupola.orbit.sgp4.Sgp4Error
import java.time.Instant

/** Engine-side facts the render thread needs; an immutable snapshot is posted on every change. */
internal data class EngineState(
    val visible: Boolean = false,
    val preview: Boolean = false,
    val xOffset: Float = 0.5f,
    val reducedMotion: Boolean = false,
    val batterySaver: Boolean = false,
)

/**
 * What a session's last frame showed; [source] is live, preview, override (gate time), pose (gate time and position),
 * last_good or epoch (orbit failure). [sunlit] is null for a gate pose.
 */
internal data class SceneDump(
    val source: String,
    val instant: Instant,
    val latDeg: Double,
    val lonDeg: Double,
    val altKm: Double,
    val sunlit: Double?,
    val yawDeg: Double,
    val pitchDeg: Double,
)

/**
 * Per-engine scene state. Everything except [surface] is touched on the render thread only. [settings] and [orbit]
 * return the latest snapshot and are read once per frame, so a new TLE reaches a running session on its next frame.
 */
internal class RenderSession(
    private val orbit: () -> OrbitModel,
    private val settings: () -> RenderSettings = { RenderSettings() },
    utcMillis: () -> Long = System::currentTimeMillis,
) {
    /** A fixed orbit (tests and one-shot renders). */
    constructor(
        orbit: OrbitModel,
        settings: () -> RenderSettings = { RenderSettings() },
        utcMillis: () -> Long = System::currentTimeMillis,
    ) : this({ orbit }, settings, utcMillis)

    private val look = LookAroundController(System::nanoTime)
    // The preview lapse rate keeps the mean motion of the session's first orbit; it barely changes between TLEs.
    private val clock = PreviewClock(System::nanoTime, utcMillis, orbit().meanMotionRevPerDay)
    private val exposure = AutoExposure()
    private var orbitFailureLogged = false
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

    /** Called on the render thread when look-around starts or stops needing the rotation sensor; strength 0 needs none. */
    var onSensorWanted: ((Boolean) -> Unit)? = null
    private var lastSensorWanted: Boolean? = null

    fun apply(state: EngineState, scheduler: FrameScheduler) {
        engineState = state
        if (state.visible && !wasVisible) look.onVisible()
        wasVisible = state.visible
        clock.setPreview(state.preview)
        look.xOffset = state.xOffset.toDouble()
        look.reducedMotion = state.reducedMotion
        scheduler.setVisible(state.visible)
        scheduler.batterySaver = state.batterySaver
        scheduler.stillFps = settings().stillFps
        syncRate(scheduler)
    }

    /** Debug gate: the look-around controller's arming and still state. */
    fun lookState(): String = look.stateLine()

    fun onOrientation(orientation: Orientation, timestampNs: Long) {
        if (!Gate.sensorBlocked()) look.setOrientation(orientation.yawRad, orientation.pitchRad, timestampNs)
    }

    private var tracing = false

    /** Feeds the gate trace, if one runs, like a sensor sample; the reference is recaptured at both ends. */
    private fun feedTrace() {
        val traced = Gate.traceOrientation(System.nanoTime())
        if ((traced != null) != tracing) look.onVisible()
        tracing = traced != null
        if (traced != null) look.setOrientation(traced.yawRad, traced.pitchRad)
    }

    // Render thread writes a fresh copy per frame; the debug gate reads it.
    @Volatile private var lastInputs: FloatArray? = null

    /** Scene of the last frame; read by the debug gate. */
    @Volatile var lastScene: SceneDump? = null
        private set

    /** Copy of the last frame inputs, before the host's render scale; null before the first frame. */
    fun lastFrameInputs(): FloatArray? = lastInputs?.copyOf()

    /** Frame inputs in the [FrameInputs] layout; unused slots stay zero. A frozen gate repeats the last ones. */
    fun nextFrameInputs(): FloatArray {
        if (Gate.frozen()) lastInputs?.let { return it.copyOf() }
        feedTrace()
        val current = settings()
        val sensorWanted = current.lookStrength > 0.0
        if (sensorWanted != lastSensorWanted) {
            // The sensor was off: its first sample must not swing the view from the stale reference.
            if (sensorWanted && lastSensorWanted == false) look.onVisible()
            lastSensorWanted = sensorWanted
            onSensorWanted?.invoke(sensorWanted)
        }
        look.strength = current.lookStrength
        look.idleFloat = current.idleFloat
        val view = look.update()
        moving = view.isMoving
        val inputs = FloatArray(FrameInputs.COUNT)
        inputs[FrameInputs.TIME_SECONDS] = (System.nanoTime() / 1_000_000 % MILLIS_PER_DAY / 1000.0).toFloat()
        inputs[FrameInputs.LOOK_YAW_DEG] = view.yawDeg.toFloat()
        inputs[FrameInputs.LOOK_PITCH_DEG] = view.pitchDeg.toFloat()
        writeScene(inputs, current.rest, view.yawDeg, view.pitchDeg, current.interior, current.fovLongDeg)
        val reduced = if (engineState.reducedMotion) FrameInputs.MOTION_REDUCED else 0
        // The clouds switch reuses the shader's debug bit; its GPU saving was measured with it.
        val noClouds = if (current.clouds) 0 else FrameInputs.DEBUG_NO_CLOUDS
        val other = inputs[FrameInputs.EFFECTS].toInt() and FrameInputs.EFFECTS_ALL.inv()
        inputs[FrameInputs.EFFECTS] = (other or (current.effects and FrameInputs.EFFECTS_ALL) or reduced or noClouds).toFloat()
        current.homeCity?.let { (lat, lon) ->
            val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(lat), Math.toRadians(lon), 0.0))
            inputs[FrameInputs.HOME_CITY] = p.x.toFloat()
            inputs[FrameInputs.HOME_CITY + 1] = p.y.toFloat()
            inputs[FrameInputs.HOME_CITY + 2] = p.z.toFloat()
            inputs[FrameInputs.HOME_CITY + 3] = 1f
        }
        Gate.applyLoad(inputs)
        lastInputs = inputs.copyOf()
        return inputs
    }

    /**
     * Real UTC (preview: time-lapse) or the debug override; an override with instant and position skips SGP4.
     * An SGP4 failure (wrong device clock, decayed TLE) keeps the last good state, or the state at the TLE epoch.
     */
    private fun writeScene(
        inputs: FloatArray,
        rest: RestView,
        yawDeg: Double,
        pitchDeg: Double,
        interior: Boolean,
        fovLongDeg: Double,
    ) {
        val override = Gate.sceneOverride()
        val poseInstant = override?.instant
        val posePosition = override?.positionEcefKm
        if (poseInstant != null && posePosition != null) {
            SceneInputs.buildPose(override)?.let { pose ->
                val at = Frames.ecefToGeodetic(posePosition)
                lastScene = SceneDump(
                    "pose", poseInstant, Math.toDegrees(at.latitudeRad), Math.toDegrees(at.longitudeRad), at.heightKm,
                    null, yawDeg, pitchDeg,
                )
                return adapted(pose).write(inputs)
            }
        }
        val instant = override?.instant ?: Instant.ofEpochMilli(clock.nowUtcMillis())
        val orbit = orbit()
        val result = if (Gate.orbitFault()) OrbitStateResult.Failure(Sgp4Error.DECAYED) else orbit.stateAt(instant)
        var source = when {
            override?.instant != null -> "override"
            engineState.preview -> "preview"
            else -> "live"
        }
        val state = when (result) {
            is OrbitState -> result.also { lastGoodState = it }
            is OrbitStateResult.Failure -> {
                if (!orbitFailureLogged) {
                    orbitFailureLogged = true
                    Log.w("Cupola", "orbit failed at $instant: ${result.error}")
                }
                lastGoodState?.also { source = "last_good" }
                    ?: (orbit.stateAt(orbit.epoch) as? OrbitState)?.also { source = "epoch" }
                    ?: return
            }
        }
        lastScene = SceneDump(
            source, state.instant, state.latitudeDeg, state.longitudeDeg, state.heightKm, state.sunlitFraction, yawDeg, pitchDeg,
        )
        adapted(SceneInputs.build(state.toSceneState(), rest, yawDeg, pitchDeg, override, interior, fovLongDeg)).write(inputs)
    }

    private var lastGoodState: OrbitState? = null

    /** The preview time-lapses through day and night in seconds, faster than any adaptation: it shows steady exposure. */
    private fun adapted(base: Scene): Scene {
        val scene = base.copy(effects = (base.effects and FrameInputs.EFFECTS_ALL.inv()) or (settings().effects and FrameInputs.EFFECTS_ALL))
        val steady = Exposure.steady(scene)
        return scene.copy(exposure = if (engineState.preview) steady else exposure.update(steady, System.nanoTime()))
    }

    fun applyMoving(scheduler: FrameScheduler) {
        scheduledMoving = moving
        scheduler.moving = moving
        syncRate(scheduler)
    }

    private fun syncRate(scheduler: FrameScheduler) = rateVote.sync(scheduler.voteFps)

    private companion object {
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
