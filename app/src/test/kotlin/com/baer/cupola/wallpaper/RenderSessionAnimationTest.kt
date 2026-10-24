package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.FrameScheduler
import com.baer.cupola.orbit.OrbitModel
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

    @Test
    fun reducedMotionReachesTheShader() {
        assertTrue(session(EngineState(visible = true, reducedMotion = true)).nextFrameInputs().effects() and FrameInputs.MOTION_REDUCED != 0)
        assertEquals(0, session(EngineState(visible = true)).nextFrameInputs().effects() and FrameInputs.MOTION_REDUCED)
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
