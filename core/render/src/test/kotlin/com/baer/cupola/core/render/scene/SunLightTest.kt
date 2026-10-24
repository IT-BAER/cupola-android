package com.baer.cupola.core.render.scene

import com.baer.cupola.orbit.sgp4.Vector3
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SunLightTest {
    private val r = SceneGeometry.A_KM + 420.0

    /** Camera over 0 N 0 E; the Sun [nadirDeg] from the nadir in the x-z plane. */
    private fun scene(nadirDeg: Double): Scene {
        val a = Math.toRadians(nadirDeg)
        return Scene(
            instant = Instant.parse("2026-10-25T00:00:00Z"),
            cameraEcefKm = Vector3(r, 0.0, 0.0),
            ecefFromCamera = Mat3.IDENTITY,
            fovLongDeg = 104.0,
            sunDirEcef = Vector3(-cos(a), 0.0, sin(a)),
            moonDirEcef = Vector3(0.0, 1.0, 0.0),
            month = MonthBlend(9, 10, 0.5),
        )
    }

    /** Nadir angle at which the Sun's ray grazes the ground at tangent height [hKm]. */
    private fun grazing(hKm: Double) = Math.toDegrees(asin((SceneGeometry.A_KM + hKm) / r))

    @Test fun sunAboveTheAirIsUnattenuated() {
        val t = scene(150.0).sunLight()
        assertEquals(1.0, t.x, 1e-12)
        assertEquals(1.0, t.z, 1e-12)
        val above = scene(grazing(150.0)).sunLight()
        assertEquals(1.0, above.y, 1e-6)
    }

    @Test fun sunBehindTheEarthIsDark() {
        val t = scene(30.0).sunLight()
        assertEquals(0.0, t.x + t.y + t.z, 0.0)
    }

    /** Through the limb the light reddens and dims towards the ground (Rayleigh blue first). */
    @Test fun grazingSunlightReddens() {
        val high = scene(grazing(30.0)).sunLight()
        val low = scene(grazing(8.0)).sunLight()
        assertTrue("reddened: $low", low.x > low.y && low.y > low.z)
        assertTrue("dimmer lower: $low vs $high", low.x < high.x && low.z < high.z)
        assertTrue("blue mostly gone at 8 km: $low", low.z < 0.2)
    }

    /** The cupola's outer side (cabin +Z): lit when the Sun stands on it, not when it is behind the cabin. */
    @Test fun glassFacingFollowsTheCabinAxis() {
        val sunrise = scene(grazing(30.0)).copy(blenderFromCamera = Mat3.IDENTITY)
        assertEquals(1.0, sunrise.glassFacing(), 1e-9) // Sun z = sin(grazing) > 0.1
        val behind = sunrise.copy(blenderFromCamera = Mat3(Vector3(1.0, 0.0, 0.0), Vector3(0.0, -1.0, 0.0), Vector3(0.0, 0.0, -1.0)))
        assertEquals(0.0, behind.glassFacing(), 1e-9)
        assertEquals(0.0, sunrise.copy(blenderFromCamera = null).glassFacing(), 0.0)
    }
}
