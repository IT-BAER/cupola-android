package com.baer.cupola.wallpaper

import android.content.Context
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.NativeRenderer
import com.baer.cupola.core.render.platform.ThermalHeadroomSource
import com.baer.cupola.core.render.scene.FrameScheduler
import com.baer.cupola.core.render.scene.QualityGovernor
import com.baer.cupola.feature.settings.SettingsRepository
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.tle.Tle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The process [RendererHost] for code without injection; in-process tests must share its renderer (one per process). */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface RendererHostEntryPoint {
    fun rendererHost(): RendererHost
}

/**
 * One [NativeRenderer] (and so one render thread) per process, shared by every wallpaper engine
 * (home, lock, preview). Started by the first [acquire], quit after the last [release].
 * Settings are observed for the life of the process; a change redraws every session once.
 */
@Singleton
class RendererHost @Inject constructor(@ApplicationContext context: Context, settingsRepository: SettingsRepository) {
    private val thermal = ThermalHeadroomSource.create(context)
    private val assets = context.assets
    private val orbit: OrbitModel by lazy { loadOrbit(context) }
    private val sessions = ConcurrentHashMap<Int, RenderSession>()
    private val nextId = AtomicInteger()
    private val shared = RefCounted(
        create = { NativeRenderer(::inputsFor, assets).also { renderer = it } },
        destroy = { renderer = null; it.close() },
    )

    // Read by the render thread, which must never take the RefCounted lock (destroy waits for that thread).
    @Volatile private var renderer: NativeRenderer? = null

    // Render thread only.
    private val governor = QualityGovernor(QUALITY_STEPS, QUALITY_STEPS - 1, clockNs = System::nanoTime) { from, to ->
        Gate.onGovernorStep(from, to, RENDER_SCALES[to], lastGpuNanos, thermal.current())
    }
    private var governedFrameNanos = Long.MIN_VALUE
    private var lastGpuNanos = -1L

    private var visibleEngines = 0

    @Volatile internal var settings = RenderSettings()
        private set

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            settingsRepository.settings.map { it.toRenderSettings() }.distinctUntilChanged().collect { value ->
                settings = value
                Gate.onSettings(value)
                val r = renderer ?: return@collect
                for (id in sessions.keys) r.updateScheduler(id) { requestFrame() }
            }
        }
    }

    internal fun acquire(): NativeRenderer = shared.acquire()

    internal fun release() = shared.release()

    /** A session not bound to a surface (one-shot offscreen renders). */
    internal fun detachedSession() = RenderSession(orbit, ::settings)

    internal fun openSession(): Pair<Int, RenderSession> {
        val id = nextId.incrementAndGet()
        return id to RenderSession(orbit, ::settings).also { sessions[id] = it; Gate.opened(id, it) }
    }

    internal fun closeSession(id: Int) {
        sessions.remove(id)
        Gate.closed(id)
    }

    /** Thermal updates run only while at least one engine is visible. */
    @Synchronized
    internal fun engineVisible(visible: Boolean) {
        if (visible) {
            if (visibleEngines++ == 0) thermal.start()
        } else if (--visibleEngines == 0) {
            thermal.stop()
        }
    }

    private fun inputsFor(surfaceId: Int, frameTimeNanos: Long): FloatArray? {
        val session = sessions[surfaceId] ?: return null
        if (frameTimeNanos != governedFrameNanos) {
            governedFrameNanos = frameTimeNanos
            lastGpuNanos = renderer?.lastGpuTimeNanos() ?: return null
            val fps = if (session.scheduledMoving) FrameScheduler.MOVING_FPS else FrameScheduler.STILL_FPS
            if (lastGpuNanos >= 0) governor.onFrame(lastGpuNanos / 1e6, 1000.0 / fps)
            governor.onThermalHeadroom(thermal.current())
        }
        Gate.onFrame(surfaceId, lastGpuNanos)
        val inputs = session.nextFrameInputs()
        inputs[FrameInputs.RENDER_SCALE] = RENDER_SCALES[governor.step]
        if (session.moving != session.scheduledMoving) {
            renderer?.updateScheduler(surfaceId) { session.applyMoving(this) }
        }
        return inputs
    }

    private companion object {
        /** The bundled TLE (`assets/orbit/iss.tle`: name line, line 1, line 2), refreshed per feature release (P7). */
        fun loadOrbit(context: Context): OrbitModel {
            val lines = context.assets.open("orbit/iss.tle").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            return OrbitModel(Tle.parse(lines[lines.size - 2], lines[lines.size - 1]))
        }

        /** Earth render scale per governor step; the default (highest) step is 0.6. */
        val RENDER_SCALES = floatArrayOf(0.4f, 0.5f, 0.6f)
        val QUALITY_STEPS = RENDER_SCALES.size
    }
}
