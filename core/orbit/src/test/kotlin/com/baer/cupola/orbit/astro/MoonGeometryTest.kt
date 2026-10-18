package com.baer.cupola.orbit.astro

import com.baer.cupola.orbit.frames.angleTo
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.sgp4.Vector3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

class MoonGeometryTest {

    private val d = 384400.0
    private val r = 6800.0
    private val moon = Vector3(d, 0.0, 0.0)
    private val sunDistance = Sun.AU_KM

    @Test
    fun observerAlongMoonDirectionHasNoParallax() {
        val topo = Topocentric.vector(moon, Vector3(r, 0.0, 0.0))
        assertEquals(0.0, topo.angleTo(moon), 1e-12)
        assertEquals(d - r, topo.norm(), 1e-9)
    }

    @Test
    fun moonOnObserverHorizonHasParallaxArcsinROverD() {
        // Observer position perpendicular to the topocentric Moon direction (horizontal parallax).
        val observer = Vector3(r * r / d, r * sqrt(1 - (r / d) * (r / d)), 0.0)
        val parallax = Topocentric.vector(moon, observer).angleTo(moon)
        assertEquals(asin(r / d), parallax, 1e-6)
    }

    @Test
    fun observerPerpendicularToGeocentricMoonHasParallaxArctanROverD() {
        val parallax = Topocentric.vector(moon, Vector3(0.0, r, 0.0)).angleTo(moon)
        assertEquals(atan(r / d), parallax, 1e-12)
    }

    @Test
    fun newMoonIsDark() {
        val sun = Vector3(sunDistance, 0.0, 0.0)
        assertEquals(PI, MoonPhase.phaseAngleRad(sun, moon), 1e-12)
        assertEquals(0.0, MoonPhase.illuminatedFraction(sun, moon), 1e-12)
    }

    @Test
    fun fullMoonIsLit() {
        val sun = Vector3(-sunDistance, 0.0, 0.0)
        assertEquals(0.0, MoonPhase.phaseAngleRad(sun, moon), 1e-12)
        assertEquals(1.0, MoonPhase.illuminatedFraction(sun, moon), 1e-12)
    }

    @Test
    fun quarterMoonIsHalfLit() {
        val sun = Vector3(0.0, sunDistance, 0.0)
        // Finite Sun distance shifts the phase angle from 90 deg by atan(d / sunDistance).
        val expected = PI / 2 - atan(d / sunDistance)
        assertEquals(expected, MoonPhase.phaseAngleRad(sun, moon), 1e-12)
        assertEquals(0.5, MoonPhase.illuminatedFraction(sun, moon), 2e-3)
    }

    @Test
    fun phaseUsesObserverPosition() {
        val sun = Vector3(-sunDistance, 0.0, 0.0)
        val observer = Vector3(0.0, r, 0.0)
        assertEquals(atan(r / d), MoonPhase.phaseAngleRad(sun, moon, observer), 1e-6)
    }
}
