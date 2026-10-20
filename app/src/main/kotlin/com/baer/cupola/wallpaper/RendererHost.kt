package com.baer.cupola.wallpaper

import android.content.Context
import com.baer.cupola.core.render.NativeRenderer
import com.baer.cupola.core.render.platform.ThermalHeadroomSource
import com.baer.cupola.core.render.scene.QualityGovernor
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.tle.Tle
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One [NativeRenderer] (and so one render thread) per process, shared by every wallpaper engine
 * (home, lock, preview). Started by the first [acquire], quit after the last [release].
 */
@Singleton
class RendererHost @Inject constructor(@ApplicationContext context: Context) {
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
    private val governor = QualityGovernor(QUALITY_STEPS, QUALITY_STEPS - 1, clockNs = System::nanoTime)
    private var governedFrameNanos = Long.MIN_VALUE
    private var lastGpuNanos = -1L

    private var visibleEngines = 0

    internal fun acquire(): NativeRenderer = shared.acquire()

    internal fun release() = shared.release()

    /** A session not bound to a surface (one-shot offscreen renders). */
    internal fun detachedSession() = RenderSession(orbit)

    internal fun openSession(): Pair<Int, RenderSession> {
        val id = nextId.incrementAndGet()
        return id to RenderSession(orbit).also { sessions[id] = it; Gate.opened(id, it) }
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
            if (lastGpuNanos >= 0) governor.onFrame(lastGpuNanos / 1e6)
            governor.onThermalHeadroom(thermal.current())
        }
        Gate.onFrame(surfaceId, lastGpuNanos)
        val inputs = session.nextFrameInputs()
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

        // The governor's step is not consumed until P3 maps steps to render quality.
        const val QUALITY_STEPS = 3
    }
}
