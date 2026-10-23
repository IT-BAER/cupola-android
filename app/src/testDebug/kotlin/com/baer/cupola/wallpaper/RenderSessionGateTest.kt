package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderSessionGateTest {
    private val orbit = File("src/main/assets/orbit/iss.tle").readLines().filter { it.isNotBlank() }
        .let { OrbitModel(Tle.parse(it[it.size - 2], it[it.size - 1])) }
    private val settings = RenderSettings(rest = RestView.NADIR, idleFloat = false)

    // The session clock has millisecond resolution; the TLE epoch has sub-millisecond digits.
    private val good = orbit.epoch.plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS)
    private var now = good
    private val session = RenderSession(orbit, { settings }, { now.toEpochMilli() })

    @After fun clearGate() {
        Gate.setFrozen(false)
        Gate.setOrbitFault(false)
    }

    @Test fun freezeRepeatsTheLastFrameUntilReleased() {
        val before = session.nextFrameInputs()
        Gate.setFrozen(true)
        now = good.plusSeconds(600)

        assertArrayEquals(before, session.nextFrameInputs(), 0f)
        Gate.setFrozen(false)
        assertFalse("moves again", before.contentEquals(session.nextFrameInputs()))
    }

    @Test fun orbitFaultKeepsTheLastGoodScene() {
        val before = sceneBlock(session.nextFrameInputs())
        Gate.setOrbitFault(true)
        now = good.plusSeconds(600)

        assertArrayEquals(before, sceneBlock(session.nextFrameInputs()), 0f)
        Gate.setOrbitFault(false)
        assertFalse("live again", before.contentEquals(sceneBlock(session.nextFrameInputs())))
    }

    @Test fun sceneDumpNamesWhatTheLastFrameShowed() {
        session.nextFrameInputs()
        val state = orbit.stateAt(good) as OrbitState
        assertEquals(
            SceneDump("live", good, state.latitudeDeg, state.longitudeDeg, state.heightKm, state.sunlitFraction, 0.0, 0.0),
            session.lastScene,
        )

        Gate.setOrbitFault(true)
        now = good.plusSeconds(600)
        session.nextFrameInputs()
        assertEquals("last_good" to good, session.lastScene?.let { it.source to it.instant })
    }

    @Test fun sceneLineIsOneGrepableLogLine() {
        val dump = SceneDump("live", Instant.parse("2026-10-23T13:25:58Z"), -20.7252, 139.4973, 418.23, 1.0, 12.34, -90.0)
        assertEquals(
            "gate scene id=2 source=live instant=2026-10-23T13:25:58Z lat=-20.725 lon=139.497 alt_km=418.2 sunlit=1.00 yaw=12.3 pitch=-90.0",
            Gate.sceneLine(2, dump),
        )
        assertEquals("gate scene id=3 none", Gate.sceneLine(3, null))
    }

    private fun sceneBlock(inputs: FloatArray) = inputs.copyOfRange(FrameInputs.SCENE_BLOCK, FrameInputs.COUNT)
}
