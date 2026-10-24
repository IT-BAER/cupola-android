package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.data.OrbitFixtures
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.tle.Tle
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderSessionOrbitSwapTest {
    private val bundled = OrbitModel(Tle.parse(OrbitFixtures.LINE1, OrbitFixtures.LINE2))
    private val updated = OrbitFixtures.tle(epoch = "26291.96721112").lines().let { OrbitModel(Tle.parse(it[1], it[2])) }
    private val settings = RenderSettings(rest = RestView.NADIR, idleFloat = false)
    private val now = Instant.parse("2026-10-18T20:00:00Z")

    private fun assertShows(orbit: OrbitModel, scene: SceneDump?) {
        val state = orbit.stateAt(now) as OrbitState
        assertEquals(state.latitudeDeg, scene!!.latDeg, 1e-6)
        assertEquals(state.longitudeDeg, scene.lonDeg, 1e-6)
    }

    @Test
    fun aRunningSessionPropagatesTheOrbitInUseOnTheNextFrame() {
        val a = bundled.stateAt(now) as OrbitState
        val b = updated.stateAt(now) as OrbitState
        assertTrue("fixtures differ", Math.abs(a.latitudeDeg - b.latitudeDeg) + Math.abs(a.longitudeDeg - b.longitudeDeg) > 1.0)
        var orbit = bundled
        val session = RenderSession({ orbit }, { settings }, { now.toEpochMilli() })
        session.nextFrameInputs()
        assertShows(bundled, session.lastScene)
        orbit = updated
        session.nextFrameInputs()
        assertShows(updated, session.lastScene)
    }
}
