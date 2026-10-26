package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.FrameScheduler
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Battery settings that act on the session and its scheduler rather than on the shader. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderSessionSettingsTest {
    private val orbit = File("src/main/assets/orbit/iss.tle").readLines().filter { it.isNotBlank() }
        .let { OrbitModel(Tle.parse(it[it.size - 2], it[it.size - 1])) }

    private fun session(settings: () -> RenderSettings) =
        RenderSession(orbit, settings, { orbit.epoch.toEpochMilli() + 3_600_000 })

    /** Look-around strength 0 releases the rotation sensor (23.7 mA measured); a strength above 0 wants it back. */
    @Test
    fun zeroLookStrengthReleasesTheSensor() {
        var strength = 0.0
        val wanted = mutableListOf<Boolean>()
        val session = session { RenderSettings(lookStrength = strength) }.apply {
            apply(EngineState(visible = true), FrameScheduler())
            onSensorWanted = { wanted += it }
        }
        session.nextFrameInputs()
        session.nextFrameInputs()
        strength = 0.5
        session.nextFrameInputs()
        session.nextFrameInputs()
        assertEquals(listOf(false, true), wanted)
    }

    /** Clouds off sets the shader's no-clouds bit (-0.68 ms GPU in daylight, measured); on leaves it clear. */
    @Test
    fun cloudsSwitchReachesTheShader() {
        fun effects(settings: RenderSettings) = session { settings }
            .apply { apply(EngineState(visible = true), FrameScheduler()) }.nextFrameInputs()[FrameInputs.EFFECTS].toInt()
        assertEquals(FrameInputs.DEBUG_NO_CLOUDS, effects(RenderSettings(clouds = false)) and FrameInputs.DEBUG_NO_CLOUDS)
        assertEquals(0, effects(RenderSettings()) and FrameInputs.DEBUG_NO_CLOUDS)
    }

    /** The Settings rest frame rate reaches the scheduler whenever the engine state is applied. */
    @Test
    fun stillFpsReachesTheScheduler() {
        val scheduler = FrameScheduler()
        session { RenderSettings(stillFps = 5) }.apply(EngineState(visible = true), scheduler)
        assertEquals(5, scheduler.targetFps)
    }
}
