package com.baer.cupola.core.render.scene

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import com.baer.cupola.orbit.sgp4.Vector3
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SceneInputsInteriorTest {
    private fun state(): SceneState {
        val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(20.0), Math.toRadians(10.0), 420.0))
        return SceneState(Instant.parse("2026-07-16T12:00:00Z"), p, Vector3(-1.0, 6.0, 4.0), Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
    }

    @Test
    fun withoutInteriorTheCameraStaysAndTheShaderFlagIsZero() {
        val with = SceneInputs.build(state(), RestView.DIAGONAL, 12.0, -5.0, interior = true)
        val without = SceneInputs.build(state(), RestView.DIAGONAL, 12.0, -5.0, interior = false)
        assertNotNull(with.blenderFromCamera)
        assertNull(without.blenderFromCamera)
        assertEquals(with.ecefFromCamera, without.ecefFromCamera)
        val on = FloatArray(FrameInputs.COUNT).also { with.write(it) }
        val off = FloatArray(FrameInputs.COUNT).also { without.write(it) }
        assertEquals(1f, on[FrameInputs.INTERIOR], 0f)
        assertEquals(0f, off[FrameInputs.INTERIOR], 0f)
    }
}
