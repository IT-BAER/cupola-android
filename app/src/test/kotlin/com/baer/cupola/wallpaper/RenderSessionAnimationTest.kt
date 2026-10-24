package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.Exposure
import com.baer.cupola.core.render.scene.FrameScheduler
import com.baer.cupola.core.render.scene.SceneInputs
import com.baer.cupola.core.render.scene.toSceneState
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderSessionAnimationTest {
    private val orbit = File("src/main/assets/orbit/iss.tle").readLines().filter { it.isNotBlank() }
        .let { OrbitModel(Tle.parse(it[it.size - 2], it[it.size - 1])) }

    private fun session(state: EngineState) = RenderSession(orbit, { RenderSettings() }, { orbit.epoch.toEpochMilli() + 3_600_000 })
        .apply { apply(state, FrameScheduler()) }

    private fun FloatArray.effects() = this[FrameInputs.EFFECTS].toInt()

    /** ST4-ST7: the Settings effect switches reach the shader's EFFECTS bits. */
    @Test
    fun settingsEffectsReachTheShader() {
        val only = RenderSettings(effects = RenderSettings.BLOOM or RenderSettings.DUST)
        val inputs = RenderSession(orbit, { only }, { orbit.epoch.toEpochMilli() + 3_600_000 })
            .apply { apply(EngineState(visible = true), FrameScheduler()) }.nextFrameInputs()
        val all = FrameInputs.EFFECT_BLOOM or FrameInputs.EFFECT_WINDOW_GLARE or FrameInputs.EFFECT_REFLECTIONS or FrameInputs.EFFECT_DUST
        assertEquals(FrameInputs.EFFECT_BLOOM or FrameInputs.EFFECT_DUST, inputs.effects() and all)
    }

    @Test
    fun reducedMotionReachesTheShader() {
        assertTrue(session(EngineState(visible = true, reducedMotion = true)).nextFrameInputs().effects() and FrameInputs.MOTION_REDUCED != 0)
        assertEquals(0, session(EngineState(visible = true)).nextFrameInputs().effects() and FrameInputs.MOTION_REDUCED)
    }

    /**
     * F26: the preview crosses day and night every few seconds; a 4 s exposure lag in wall time blew the day out
     * after each night. In preview the exposure is the scene's steady value, also right after a terminator crossing.
     */
    @Test
    fun previewExposureFollowsTheTimeLapseWithoutLag() {
        // 30 s of orbit before the ISS enters the Earth's shadow; 0.4 s of preview is about 90 s of orbit.
        val entry = generateSequence(orbit.epoch.plusSeconds(3600)) { it.plusSeconds(5) }.zipWithNext().first { (a, b) ->
            (orbit.stateAt(a) as OrbitState).sunlitFraction > 0.99 && (orbit.stateAt(b) as OrbitState).sunlitFraction < 0.99
        }.first
        val start = entry.minusSeconds(30).toEpochMilli()
        val preview = RenderSession(orbit, { RenderSettings() }, { start }).apply { apply(EngineState(visible = true, preview = true), FrameScheduler()) }
        preview.nextFrameInputs()
        Thread.sleep(400)
        val inputs = preview.nextFrameInputs()
        val instant = preview.lastScene!!.instant
        check(instant.isAfter(entry.plusSeconds(30))) { "preview did not cross the terminator: $instant" }
        val steady = Exposure.steady(SceneInputs.build((orbit.stateAt(instant) as OrbitState).toSceneState(), RenderSettings().rest, 0.0, 0.0))
        assertEquals(steady, inputs[FrameInputs.EXPOSURE].toDouble(), steady * 1e-3)
    }

    /** The preview time-lapses the orbit (about 220x); animation time (lightning, aurora) stays real time. */
    @Test
    fun animationTimeIgnoresThePreviewTimeLapse() {
        val preview = session(EngineState(visible = true, preview = true))
        val t0 = preview.nextFrameInputs()[FrameInputs.TIME_SECONDS]
        Thread.sleep(200)
        val t1 = preview.nextFrameInputs()[FrameInputs.TIME_SECONDS]
        assertEquals(0.2, (t1 - t0).toDouble(), 0.15)
    }
}
