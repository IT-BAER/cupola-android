package com.baer.cupola.wallpaper

import android.os.Bundle
import com.baer.cupola.core.render.scene.SceneOverride

/** Release twin of the debug gate control: every hook is a no-op. */
internal object Gate {
    fun opened(id: Int, session: RenderSession) = Unit

    fun closed(id: Int) = Unit

    fun applyLoad(inputs: FloatArray) = Unit

    fun sceneOverride(): SceneOverride? = null

    fun frozen() = false

    fun orbitFault() = false

    fun sensorBlocked() = false

    fun traceOrientation(nowNs: Long): Orientation? = null

    fun onFrame(id: Int, gpuNanos: Long) = Unit

    fun onCommand(action: String, x: Int, y: Int, z: Int, extras: Bundle?) = Unit

    fun onSettings(settings: RenderSettings) = Unit

    fun onGovernorStep(from: Int, to: Int, renderScale: Float, gpuNanos: Long, headroom: Float) = Unit
}
