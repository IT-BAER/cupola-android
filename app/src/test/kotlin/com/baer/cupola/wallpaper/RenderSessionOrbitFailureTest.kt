package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.core.render.scene.SceneInputs
import com.baer.cupola.core.render.scene.toSceneState
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.OrbitStateResult
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderSessionOrbitFailureTest {
    private val orbit = File("src/main/assets/orbit/iss.tle").readLines().filter { it.isNotBlank() }
        .let { OrbitModel(Tle.parse(it[it.size - 2], it[it.size - 1])) }
    private val settings = RenderSettings(rest = RestView.NADIR, idleFloat = false)
    private val decayed = Instant.parse("2001-01-01T12:00:00Z")

    private fun sceneBlock(inputs: FloatArray) = inputs.copyOfRange(FrameInputs.SCENE_BLOCK, FrameInputs.COUNT)

    private fun expectedAt(instant: Instant): FloatArray {
        val state = (orbit.stateAt(instant) as OrbitState).toSceneState()
        return sceneBlock(FrameInputs.empty().also { SceneInputs.build(state, RestView.NADIR, 0.0, 0.0).write(it) })
    }

    @Test
    fun failureWithoutEarlierStateShowsTheSceneAtTheTleEpoch() {
        check(orbit.stateAt(decayed) is OrbitStateResult.Failure)
        val inputs = RenderSession(orbit, { settings }, { decayed.toEpochMilli() }).nextFrameInputs()

        assertTrue("scene flag", inputs[FrameInputs.SCENE] != 0f)
        assertArrayEquals(expectedAt(orbit.epoch), sceneBlock(inputs), 1e-4f)
    }

    @Test
    fun failureKeepsTheLastGoodScene() {
        // The session clock has millisecond resolution; the TLE epoch has sub-millisecond digits.
        val good = orbit.epoch.plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
        var now = good
        val session = RenderSession(orbit, { settings }, { now.toEpochMilli() })
        session.nextFrameInputs()
        now = decayed
        val inputs = session.nextFrameInputs()

        assertTrue("scene flag", inputs[FrameInputs.SCENE] != 0f)
        assertArrayEquals(expectedAt(good), sceneBlock(inputs), 1e-4f)
    }
}
