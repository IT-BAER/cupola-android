package com.baer.cupola.wallpaper

import android.os.Bundle
import android.util.Log
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.SceneOverride
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Debug-only P2.6 gate control; the release twin of this object is a no-op. Driven by [GateReceiver]. */
internal object Gate {
    private const val TAG = "CupolaGate"
    private const val OFF = -1L

    @Volatile private var loadIterations = 0
    @Volatile private var override: SceneOverride? = null
    @Volatile private var frozen = false
    @Volatile private var orbitFault = false
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

    /** While frozen every session repeats its last frame inputs: time, orbit and look-around stop. */
    fun setFrozen(value: Boolean) {
        frozen = value
        Log.i(TAG, "gate freeze=$value")
    }

    fun frozen() = frozen

    /** While set every orbit propagation fails like a decayed TLE (the F21 last-good-scene path). */
    fun setOrbitFault(value: Boolean) {
        orbitFault = value
        Log.i(TAG, "gate fault orbit=$value")
    }

    fun orbitFault() = orbitFault

    /** Open sessions by id, for the receiver's capture and surface fault. */
    fun sessions(): Map<Int, RenderSession> = sessions.toSortedMap()

    fun dumpScenes() {
        sessions.keys.sorted().forEach { Log.i(TAG, sceneLine(it, sessions[it]?.lastScene)) }
    }

    fun sceneLine(id: Int, dump: SceneDump?): String {
        if (dump == null) return "gate scene id=$id none"
        val sunlit = dump.sunlit?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "n/a"
        return String.format(
            Locale.ROOT, "gate scene id=%d source=%s instant=%s lat=%.3f lon=%.3f alt_km=%.1f sunlit=%s yaw=%.1f pitch=%.1f",
            id, dump.source, dump.instant, dump.latDeg, dump.lonDeg, dump.altKm, sunlit, dump.yawDeg, dump.pitchDeg,
        )
    }

    fun dumpInputs() {
        sessions.keys.sorted().forEach { id ->
            Log.i(TAG, inputsLine(id, sessions[id]?.lastFrameInputs()))
            Log.i(TAG, "gate look id=$id ${sessions[id]?.lookState()}")
        }
    }

    /** Every [FrameInputs] slot of the last frame by name, one line; what `gate capture` would render. */
    fun inputsLine(id: Int, inputs: FloatArray?): String {
        if (inputs == null) return "gate inputs id=$id none"
        fun f(i: Int) = inputs[i].toString()
        fun v3(i: Int) = "${f(i)},${f(i + 1)},${f(i + 2)}"
        fun v4(i: Int) = "${v3(i)},${f(i + 3)}"
        return "gate inputs id=$id time=${f(FrameInputs.TIME_SECONDS)} yaw=${f(FrameInputs.LOOK_YAW_DEG)}" +
            " pitch=${f(FrameInputs.LOOK_PITCH_DEG)} scene=${f(FrameInputs.SCENE)} scale=${f(FrameInputs.RENDER_SCALE)}" +
            " effects=0x${inputs[FrameInputs.EFFECTS].toInt().toString(16)} cam_km=${v3(FrameInputs.CAMERA_POS_KM)}" +
            " c=${f(FrameInputs.CAMERA_ELLIPSOID_C)} right=${v3(FrameInputs.CAMERA_RIGHT)} up=${v3(FrameInputs.CAMERA_UP)}" +
            " back=${v3(FrameInputs.CAMERA_BACK)}" +
            " month=${f(FrameInputs.MONTH_WEIGHT)},${f(FrameInputs.MONTH_LAYER0)},${f(FrameInputs.MONTH_LAYER1)}" +
            " sun=${v3(FrameInputs.SUN_DIR)} tanfov=${f(FrameInputs.TAN_HALF_FOV_LONG)} moon=${v3(FrameInputs.MOON_DIR)}" +
            " moonlight=${f(FrameInputs.MOON_LIGHT)} interior=${f(FrameInputs.INTERIOR)}" +
            " int_right=${v3(FrameInputs.INTERIOR_RIGHT)} int_up=${v3(FrameInputs.INTERIOR_UP)}" +
            " int_back=${v3(FrameInputs.INTERIOR_BACK)} earthlight=${f(FrameInputs.EARTH_LIGHT)}" +
            " exposure=${f(FrameInputs.EXPOSURE)} sky_up=${v3(FrameInputs.SKY_UP)} sky_hn=${f(FrameInputs.SKY_HORIZON_NADIR)}" +
            " sky_sun=${v3(FrameInputs.SKY_SUN_AXIS)} sky_tn=${f(FrameInputs.SKY_TOP_NADIR)} cloud=${v3(FrameInputs.CLOUD_FLOW)}" +
            " star_x=${v3(FrameInputs.STAR_ROW_X)} star_y=${v3(FrameInputs.STAR_ROW_Y)} star_z=${v3(FrameInputs.STAR_ROW_Z)}" +
            " sunlight=${v4(FrameInputs.SUN_LIGHT)} home=${v4(FrameInputs.HOME_CITY)}"
    }

    /** Slot names by mask bit, in the order of `kSlots` in core/render/src/main/cpp/texture_set.cpp. */
    val TEXTURE_SLOTS = listOf(
        "day_detail", "monthly_colour", "clouds", "city_lights", "terrain_normal", "stars", "water_mask",
        "interior_earth", "interior_cabin", "interior_emissive", "interior_ambient",
    )

    /** The renderer's texture-set mask and the slots it replaced by their neutral fallback. */
    fun textureLine(mask: Int): String {
        val missing = TEXTURE_SLOTS.filterIndexed { bit, _ -> mask and (1 shl bit) == 0 }
        return "gate textures mask=0x${mask.toString(16)} missing=${if (missing.isEmpty()) "-" else missing.joinToString(",")}"
    }

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

    /** Every settings snapshot the host applies (the P6 "within 1 s" check reads its logcat time). */
    fun onSettings(settings: RenderSettings) {
        Log.i(TAG, "gate settings $settings")
    }

    /** Render thread, on every quality step change (W12). */
    fun onGovernorStep(from: Int, to: Int, renderScale: Float, gpuNanos: Long, headroom: Float) {
        Log.i(TAG, String.format(Locale.ROOT, "gate governor step=%d->%d render_scale=%.2f gpu_ms=%.2f headroom=%.2f",
            from, to, renderScale, if (gpuNanos >= 0) gpuNanos / 1e6 else -1.0, headroom))
    }

    private fun log(id: Int, now: Long) {
        val state = sessions[id]?.engineState ?: EngineState()
        val stat = stats.getOrPut(id) { GateStats() }
        Log.i(TAG, stat.line(id, state.preview, state.visible, now, loadIterations))
    }
}
