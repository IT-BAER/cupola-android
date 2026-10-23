package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.core.render.scene.SceneInputs
import com.baer.cupola.core.render.scene.toSceneState
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.Settings
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RenderSettingsTest {
    @Test
    fun settingsMapToRenderSettings() {
        val r = Settings(
            restDirection = RestDirection.NADIR,
            lookAroundStrength = 0.25f,
            idleFloat = false,
            bloom = true,
            windowGlare = false,
            reflections = true,
            dust = false,
        ).toRenderSettings()
        assertEquals(RestView.NADIR, r.rest)
        assertEquals(0.25, r.lookStrength, 1e-6)
        assertFalse(r.idleFloat)
        assertEquals(RenderSettings.BLOOM or RenderSettings.REFLECTIONS, r.effects)
    }

    @Test
    fun defaultsMatchTheSettingsDefaults() {
        assertEquals(Settings().toRenderSettings(), RenderSettings())
    }

    @Test
    fun sessionUsesTheRestViewOfTheCurrentSettings() {
        val lines = File("src/main/assets/orbit/iss.tle").readLines().filter { it.isNotBlank() }
        val orbit = OrbitModel(Tle.parse(lines[lines.size - 2], lines[lines.size - 1]))
        var settings = RenderSettings(rest = RestView.DIAGONAL, idleFloat = false)
        val session = RenderSession(orbit, { settings })
        val instant = Instant.now()
        val back = { inputs: FloatArray -> inputs.copyOfRange(FrameInputs.CAMERA_BACK, FrameInputs.CAMERA_BACK + 3) }

        val diagonal = back(session.nextFrameInputs())
        settings = RenderSettings(rest = RestView.NADIR, idleFloat = false)
        val nadir = back(session.nextFrameInputs())

        val state = (orbit.stateAt(instant) as OrbitState).toSceneState()
        val expected = FrameInputs.empty().also { SceneInputs.build(state, RestView.NADIR, 0.0, 0.0).write(it) }
        // The orbit moves about 8 m per ms; the camera axes change by far less than 1e-3 between the two instants.
        assertArrayEquals(back(expected), nadir, 1e-3f)
        assertFalse(diagonal.zip(nadir).all { (a, b) -> kotlin.math.abs(a - b) < 1e-3f })
    }
}
