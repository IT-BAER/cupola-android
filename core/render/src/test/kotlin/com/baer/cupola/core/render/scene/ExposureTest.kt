package com.baer.cupola.core.render.scene

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.orbit.sgp4.Vector3
import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureTest {
    private val r = SceneGeometry.A_KM + 420.0

    /** ISS over the equator at 0 deg E; the Sun at zenith angle [sunZenithDeg] of the sub-ISS point, the Moon opposite or beside it. */
    private fun scene(sunZenithDeg: Double, moonOppositeSun: Boolean = false): Scene {
        val z = Math.toRadians(sunZenithDeg)
        val sun = Vector3(cos(z), 0.0, sin(z))
        val moon = if (moonOppositeSun) Vector3(-sun.x, -sun.y, -sun.z) else Vector3(0.0, 1.0, 0.0)
        return Scene(
            instant = Instant.parse("2026-10-25T00:00:00Z"),
            cameraEcefKm = Vector3(r, 0.0, 0.0),
            ecefFromCamera = Mat3.IDENTITY,
            fovLongDeg = 104.0,
            sunDirEcef = sun,
            moonDirEcef = moon,
            month = MonthBlend(9, 10, 0.5),
        )
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)

    /**
     * Sunrise from the cupola: the ground below is in night (Sun zenith 101 deg there), but the Sun stands 8 deg above
     * the limb on the cupola's outer side (cabin +Z, here the camera z axis), lighting the window glass. With window
     * glare on, the exposure follows the sunlit glass instead of the night floor; off, or without the cabin, it does not.
     */
    @Test
    fun sunlitGlassLowersTheSunriseExposure() {
        val sunrise = scene(101.0).copy(blenderFromCamera = Mat3.IDENTITY, sunDirEcef = SceneGeometry.unit(Vector3(-0.196, 0.0, 0.98)))
        val lit = Exposure.steady(sunrise)
        val off = Exposure.steady(sunrise.copy(effects = FrameInputs.EFFECTS_ALL and FrameInputs.EFFECT_WINDOW_GLARE.inv()))
        val noCabin = Exposure.steady(sunrise.copy(blenderFromCamera = null))
        assertTrue("lit $lit off $off", lit < off * 0.5)
        assertEquals(off, noCabin, off * 1e-9)
        val behindEarth = sunrise.copy(sunDirEcef = SceneGeometry.unit(Vector3(-0.5, 0.0, 0.8)))
        assertEquals("Sun below the limb", Exposure.steady(behindEarth.copy(effects = 0)), Exposure.steady(behindEarth), 1e-9)
    }

    @Test
    fun earthLightIsTheMeanSunCosineOverTheVisibleCap() {
        val capDeg = Math.toDegrees(acos(SceneGeometry.A_KM / r))
        // Noon: every visible ground point sees the Sun within capDeg of its zenith.
        val noon = scene(0.0).earthLight() / Scene.EARTH_ALBEDO
        assertTrue("noon $noon", noon in cos(Math.toRadians(capDeg))..1.0)
        assertEquals(0.0, scene(90.0 + capDeg + 1.0).earthLight(), 1e-12)
        val terminator = scene(90.0).earthLight() / Scene.EARTH_ALBEDO
        assertTrue("terminator $terminator", terminator in 0.01..0.2)
    }

    /**
     * Cap-mean single-scatter airlight luma from 420 km (tools/atmosphere/airlight.py) by sun elevation; the model
     * adds [Scene.AIRLIGHT_MULTI_SCATTER] for multiple scattering.
     */
    @Test
    fun airlightFollowsTheSingleScatterIntegration() {
        val table = mapOf(
            -6.0 to 1.0200e-04, -4.0 to 6.8747e-04, -2.0 to 3.2992e-03, -1.0 to 5.8999e-03, 0.0 to 9.2146e-03,
            1.0 to 1.2711e-02, 3.0 to 1.9136e-02, 8.0 to 2.9403e-02, 20.0 to 3.7918e-02, 90.0 to 4.1976e-02,
        )
        for ((elevationDeg, singleScatter) in table) {
            val model = Scene.airlight(sin(Math.toRadians(elevationDeg))) / Scene.AIRLIGHT_MULTI_SCATTER
            assertEquals("elevation $elevationDeg", 1.0, model / singleScatter, 0.12)
        }
    }

    /** Sun 3.25 deg below the sub-ISS horizon (EarthRenderTest terminator pose): the sunlit air keeps exposure low. */
    @Test
    fun airlightBoundsTheTerminatorExposure() {
        val scene = scene(93.25)
        assertTrue("airlight ${scene.airLight()} earthLight ${scene.earthLight()}", scene.airLight() > scene.earthLight())
        assertTrue("exposure ${Exposure.steady(scene)}", Exposure.steady(scene) < 40.0)
    }

    @Test
    fun nightExposureLiftsTheShadowPassFarAboveDay() {
        val day = Exposure.steady(scene(0.0))
        val night = Exposure.steady(scene(150.0))
        assertTrue("day $day night $night", night / day >= 1000.0)
        assertTrue("bounded: day $day night $night", day in Exposure.MIN..Exposure.MAX && night in Exposure.MIN..Exposure.MAX)
    }

    @Test
    fun exposureIsSmoothAndMonotonicThroughTheTerminator() {
        var previous = log2(Exposure.steady(scene(0.0)))
        for (i in 1..1800) {
            val ev = log2(Exposure.steady(scene(i * 0.1)))
            assertTrue("drops at ${i * 0.1} deg", ev >= previous - 1e-9)
            assertTrue("jumps ${ev - previous} EV at ${i * 0.1} deg", ev - previous <= 0.1)
            previous = ev
        }
    }

    @Test
    fun fullMoonLowersTheNightExposure() {
        val newMoon = Exposure.steady(scene(150.0, moonOppositeSun = false))
        val fullMoon = Exposure.steady(scene(150.0, moonOppositeSun = true))
        assertTrue("full $fullMoon new $newMoon", fullMoon < newMoon * 0.99)
    }

    @Test
    fun autoExposureSnapsFirstThenAdaptsWithItsTimeConstant() {
        val auto = AutoExposure(tauSeconds = 4.0)
        assertEquals(2.0, auto.update(2.0, 0L), 1e-12)
        // Step to 32 (5 EV) at 60 fps: 63.2 % of the way in log space after tau, within 1 % after 5 tau.
        val frameNs = 1_000_000_000L / 60
        var t = 0L
        var e = 0.0
        repeat(240) { t += frameNs; e = auto.update(32.0, t) }
        assertEquals(1 - exp(-1.0), (log2(e) - 1) / 4, 0.01)
        repeat(960) { t += frameNs; e = auto.update(32.0, t) }
        assertTrue("after 5 tau $e", abs(log2(e) - 5) < 0.04)
    }

    @Test
    fun autoExposureIsDeterministicAndSnapsAfterAGap() {
        val a = AutoExposure()
        val b = AutoExposure()
        val targets = (0 until 50).map { 1.0 + it * it }
        val outA = targets.mapIndexed { i, x -> a.update(x, i * 16_666_667L) }
        val outB = targets.mapIndexed { i, x -> b.update(x, i * 16_666_667L) }
        assertEquals(outA, outB)
        assertTrue("adapting, not snapped", outA.last() < targets.last())
        assertEquals(7.0, a.update(7.0, 50 * 16_666_667L + 3_000_000_000L), 1e-12)
    }
}
