package com.baer.cupola.wallpaper

import android.os.Bundle
import android.util.Log
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.SceneOverride
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Debug-only P2.6 gate control; the release twin of this object is a no-op. Driven by [GateReceiver]. */
internal object Gate {
    private const val TAG = "CupolaGate"
    private const val OFF = -1L

    @Volatile private var loadIterations = 0
    @Volatile private var override: SceneOverride? = null
    private val traceStartNs = AtomicLong(OFF)
    private val sessions = ConcurrentHashMap<Int, RenderSession>()
    private val stats = ConcurrentHashMap<Int, GateStats>()

    fun opened(id: Int, session: RenderSession) {
        sessions[id] = session
    }

    fun closed(id: Int) {
        sessions.remove(id)
        stats.remove(id)
    }

    fun setLoad(iterations: Int) {
        loadIterations = iterations.coerceAtLeast(0)
        Log.i(TAG, "gate load=$loadIterations")
    }

    fun applyLoad(inputs: FloatArray) {
        val n = loadIterations
        if (n == 0) return
        inputs[FrameInputs.LOAD] = 1f
        inputs[FrameInputs.LOAD_ITERATIONS] = n.toFloat()
    }

    /** P3a / G-PR scene override for every engine; null restores the live scene. */
    fun setSceneOverride(value: SceneOverride?) {
        override = value
        Log.i(TAG, "gate scene override=$value")
    }

    fun sceneOverride(): SceneOverride? = override

    fun startTrace() {
        traceStartNs.set(System.nanoTime())
        Log.i(TAG, "gate trace start")
    }

    fun stopTrace() {
        traceStartNs.set(OFF)
        Log.i(TAG, "gate trace stop")
    }

    /** While a trace runs the sensor is ignored. */
    fun sensorBlocked() = traceStartNs.get() != OFF

    /** The trace orientation now, or null when no trace runs; the trace ends itself after its duration. */
    fun traceOrientation(nowNs: Long): Orientation? {
        val start = traceStartNs.get()
        if (start == OFF) return null
        RotationTrace.sample((nowNs - start) / 1e9)?.let { return it }
        if (traceStartNs.compareAndSet(start, OFF)) Log.i(TAG, "gate trace done")
        return null
    }

    /** Render thread, once per rendered frame of [id]; [gpuNanos] is the renderer's last GPU time or -1. */
    fun onFrame(id: Int, gpuNanos: Long) {
        val now = System.nanoTime()
        val stat = stats.getOrPut(id) { GateStats() }
        stat.record(now, if (gpuNanos >= 0) gpuNanos / 1e6 else -1.0)
        if (now - lastLogNs(id) >= 1_000_000_000L) {
            lastLog[id] = now
            log(id, now)
        }
    }

    private val lastLog = ConcurrentHashMap<Int, Long>()

    private fun lastLogNs(id: Int) = lastLog.getOrPut(id) { 0L }

    fun resetStats() {
        stats.values.forEach { it.reset() }
        Log.i(TAG, "gate stats reset")
    }

    fun dumpStats() {
        val now = System.nanoTime()
        sessions.keys.sorted().forEach { log(it, now) }
    }

    /** Every Engine.onCommand, whatever the action. */
    fun onCommand(action: String, x: Int, y: Int, z: Int, extras: Bundle?) {
        Log.i(TAG, "gate command action=$action x=$x y=$y z=$z extras=${extras?.keySet()?.sorted()}")
    }

    private fun log(id: Int, now: Long) {
        val state = sessions[id]?.engineState ?: EngineState()
        val stat = stats.getOrPut(id) { GateStats() }
        Log.i(TAG, stat.line(id, state.preview, state.visible, now, loadIterations))
    }
}
