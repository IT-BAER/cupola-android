package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.NativeRenderer
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

    @Test fun inputsLineIsOneGrepableLogLine() {
        val inputs = FloatArray(FrameInputs.COUNT) { it.toFloat() }
        inputs[FrameInputs.EFFECTS] = 15f
        assertEquals(
            "gate inputs id=2 time=2.0 yaw=3.0 pitch=4.0 scene=5.0 scale=6.0 effects=0xf cam_km=8.0,9.0,10.0 c=11.0 " +
                "right=12.0,13.0,14.0 up=16.0,17.0,18.0 back=20.0,21.0,22.0 month=15.0,19.0,23.0 sun=24.0,25.0,26.0 " +
                "tanfov=27.0 moon=28.0,29.0,30.0 moonlight=31.0 interior=35.0 int_right=32.0,33.0,34.0 " +
                "int_up=36.0,37.0,38.0 int_back=40.0,41.0,42.0 earthlight=39.0 exposure=43.0 sky_up=44.0,45.0,46.0 " +
                "sky_hn=47.0 sky_sun=48.0,49.0,50.0 sky_tn=51.0 cloud=52.0,53.0,54.0 star_x=56.0,57.0,58.0 " +
                "star_y=60.0,61.0,62.0 star_z=64.0,65.0,66.0 sunlight=68.0,69.0,70.0,71.0 home=72.0,73.0,74.0,75.0",
            Gate.inputsLine(2, inputs),
        )
        assertEquals("gate inputs id=3 none", Gate.inputsLine(3, null))
    }

    @Test fun textureLineNamesTheFallbackSlots() {
        assertEquals("gate textures mask=0x7ff missing=-", Gate.textureLine(NativeRenderer.TEXTURE_SET_COMPLETE))
        assertEquals("gate textures mask=0x7fc missing=day_detail,monthly_colour", Gate.textureLine(0x7fc))
        assertEquals("gate textures mask=0x0 missing=${Gate.TEXTURE_SLOTS.joinToString(",")}", Gate.textureLine(0))
    }

    /** The slot order is declared twice (texture_set.cpp kSlots and Gate); this keeps both in lockstep. */
    @Test fun textureSlotNamesMatchTheCppTable() {
        val cpp = File("../core/render/src/main/cpp/texture_set.cpp").readText()
        val table = Regex("""kSlots\[kTextureSlots]\s*=\s*\{(.*?)\n};""", RegexOption.DOT_MATCHES_ALL).find(cpp)
        val names = Regex("""\{"(\w+)",""").findAll(checkNotNull(table).groupValues[1]).map { it.groupValues[1] }.toList()
        assertEquals(names, Gate.TEXTURE_SLOTS)
    }

    private fun sceneBlock(inputs: FloatArray) = inputs.copyOfRange(FrameInputs.SCENE_BLOCK, FrameInputs.COUNT)
}
