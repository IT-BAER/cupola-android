package com.baer.cupola.core.render.scene

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import com.baer.cupola.orbit.frames.angleTo
import com.baer.cupola.orbit.frames.cross
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.frames.unaryMinus
import com.baer.cupola.orbit.sgp4.Vector3
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SceneInputsTest {
    private val heightKm = 420.0

    /** Circular-ish state over [latDeg], [lonDeg] with an eastward, 51.6 deg inclined velocity. */
    private fun state(latDeg: Double, lonDeg: Double, instant: Instant = Instant.parse("2026-07-16T12:00:00Z")): SceneState {
        val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(latDeg), Math.toRadians(lonDeg), heightKm))
        val enu = SceneGeometry.enu(p)
        val heading = Math.toRadians(40.0)
        val v = (enu.east * sin(heading) + enu.north * cos(heading)) * 7.66
        return SceneState(instant, p, v, Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0))
    }

    private operator fun Vector3.plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)

    private fun assertVec(expected: Vector3, actual: Vector3, tol: Double) {
        assertEquals("x", expected.x, actual.x, tol)
        assertEquals("y", expected.y, actual.y, tol)
        assertEquals("z", expected.z, actual.z, tol)
    }

    @Test
    fun nadirRestCentreRayHitsTheSubSatellitePoint() {
        for (lat in listOf(0.0, 45.0)) {
            val s = state(lat, -150.0)
            val scene = SceneInputs.build(s, RestView.NADIR, 0.0, 0.0)
            val ray = scene.rayEcef(540.0, 1170.0, 1080, 2340)
            val t = SceneGeometry.intersectEllipsoid(scene.cameraEcefKm, ray)
            assertNotNull(t)
            val hit = scene.cameraEcefKm + ray * t!!
            val geo = Frames.ecefToGeodetic(hit)
            // LVLH nadir is geocentric: the hit has the ISS's geocentric latitude and its longitude.
            val geocentricLatIss = asin(s.positionEcefKm.z / s.positionEcefKm.norm())
            assertEquals(geocentricLatIss, asin(hit.z / hit.norm()), 1e-9)
            assertEquals(Math.toRadians(-150.0), geo.longitudeRad, 1e-9)
            // Against the geodetic sub-satellite point: exact on the equator, within 2 km at 45 deg.
            val sub = Frames.geodeticToEcef(Geodetic(Math.toRadians(lat), Math.toRadians(-150.0), 0.0))
            assertEquals(0.0, (hit - sub).norm(), if (lat == 0.0) 1e-6 else 2.0)
        }
    }

    @Test
    fun horizonAngleMatchesAsinOfRadiusOverDistance() {
        // In the equatorial plane the WGS-84 section is a circle of radius a.
        val s = state(0.0, 30.0)
        val p = s.positionEcefKm
        val nadir = -p * (1.0 / p.norm())
        val east = SceneGeometry.enu(p).east
        var lo = 0.0
        var hi = Math.PI / 2
        repeat(60) {
            val mid = (lo + hi) / 2
            val dir = nadir * cos(mid) + east * sin(mid)
            if (SceneGeometry.intersectEllipsoid(p, dir) != null) lo = mid else hi = mid
        }
        val expected = Math.toDegrees(asin(Frames.WGS84_A_KM / p.norm()))
        assertEquals(expected, Math.toDegrees(lo), 0.01)
        assertNull(SceneGeometry.intersectEllipsoid(p, nadir * cos(hi + 1e-4) + east * sin(hi + 1e-4)))
    }

    /**
     * P4.6 measurement (tools/blender/README.md "Station exterior"): the real window facets sit at 30 + 60k deg from
     * the ISS velocity, so the velocity points at azimuth +30 deg about +Z (nadir) in the Cupola/Blender frame.
     */
    @Test
    fun blenderFrameHasTheVelocityAtAzimuth30AndZAtNadir() {
        val s = state(20.0, 10.0)
        val lvlh = SceneGeometry.lvlh(s.positionEcefKm, s.velocityEcefKmPerSec)
        val m = lvlh * SceneGeometry.LVLH_FROM_BLENDER
        val r = s.positionEcefKm
        val nadir = -r * (1.0 / r.norm())
        assertVec(nadir, m * Vector3(0.0, 0.0, 1.0), 1e-12)
        // Velocity with its radial part removed; LVLH +Y = Z x X (minus the orbit normal).
        val v = s.velocityEcefKmPerSec
        val vh = v - nadir * (v dot nadir)
        val a = Math.toRadians(30.0)
        assertVec(vh * (1.0 / vh.norm()), m * Vector3(cos(a), sin(a), 0.0), 1e-12)
        val h = r cross v
        assertVec(-h * (1.0 / h.norm()), m * Vector3(-sin(a), cos(a), 0.0), 1e-12)
    }

    @Test
    fun restViewsFollowTheBlenderMatrices() {
        val s = state(0.0, 0.0)
        val nadir = SceneInputs.build(s, RestView.NADIR, 0.0, 0.0)
        val ecefFromBlender = SceneGeometry.lvlh(s.positionEcefKm, s.velocityEcefKmPerSec) * SceneGeometry.LVLH_FROM_BLENDER
        // NADIR_REST looks along Blender +Z with image up toward Blender azimuth 30 deg (visible_region.json): the velocity.
        assertVec(ecefFromBlender * Vector3(0.0, 0.0, 1.0), -nadir.ecefFromCamera.c2, 1e-9)
        assertVec(ecefFromBlender * Vector3(cos(Math.PI / 6), sin(Math.PI / 6), 0.0), nadir.ecefFromCamera.c1, 1e-9)
        val diag = SceneInputs.build(s, RestView.DIAGONAL, 0.0, 0.0)
        assertVec(ecefFromBlender * Vector3(0.2896074950695038, 0.16720491647720337, 0.9424277544021606), -diag.ecefFromCamera.c2, 1e-6)
    }

    @Test
    fun lookYawTurnsRightAndPitchTurnsUp() {
        val s = state(0.0, 0.0)
        val rest = SceneInputs.build(s, RestView.DIAGONAL, 0.0, 0.0).ecefFromCamera
        val yawed = SceneInputs.build(s, RestView.DIAGONAL, 10.0, 0.0).ecefFromCamera
        val pitched = SceneInputs.build(s, RestView.DIAGONAL, 0.0, 10.0).ecefFromCamera
        // The Blender rest matrices carry float32 precision.
        assertEquals(10.0, Math.toDegrees((-rest.c2).angleTo(-yawed.c2)), 1e-5)
        assertEquals(10.0, Math.toDegrees((-rest.c2).angleTo(-pitched.c2)), 1e-5)
        assert(((-yawed.c2) dot rest.c0) > 0) { "positive yaw must turn toward camera right" }
        assert(((-pitched.c2) dot rest.c1) > 0) { "positive pitch must turn toward camera up" }
    }

    @Test
    fun fovIsTheLensOnTheLongAxis() {
        val scene = SceneInputs.build(state(0.0, 0.0), RestView.NADIR, 0.0, 0.0)
        assertEquals(96.73, SceneInputs.DEFAULT_FOV_LONG_DEG, 0.01)
        val (h, v) = scene.fovDeg(1080, 2340)
        assertEquals(SceneInputs.DEFAULT_FOV_LONG_DEG, v, 1e-9)
        assertEquals(Math.toDegrees(2 * kotlin.math.atan(kotlin.math.tan(Math.toRadians(v / 2)) * 1080 / 2340)), h, 1e-9)
        val (hl, vl) = scene.fovDeg(2340, 1080)
        assertEquals(SceneInputs.DEFAULT_FOV_LONG_DEG, hl, 1e-9)
        assertEquals(h, vl, 1e-9)
        val narrow = SceneInputs.build(state(0.0, 0.0), RestView.NADIR, 0.0, 0.0, fovLongDeg = 70.0)
        assertEquals(70.0, narrow.fovDeg(1080, 2340).second, 1e-9)
    }

    @Test
    fun monthBlendInterpolatesBetweenMidMonths() {
        assertEquals(MonthBlend(6, 7, 0.0), SceneInputs.monthBlend(Instant.parse("2026-07-16T12:00:00Z")))
        assertEquals(MonthBlend(11, 0, 0.5), SceneInputs.monthBlend(Instant.parse("2026-01-01T00:00:00Z")))
        val b = SceneInputs.monthBlend(Instant.parse("2026-03-01T00:00:00Z")) // Feb 2026 has 28 days
        assertEquals(1, b.layer0)
        assertEquals(2, b.layer1)
        assertEquals(0.5, b.weight, 1e-12)
        val late = SceneInputs.monthBlend(Instant.parse("2026-12-31T23:59:59Z"))
        assertEquals(11, late.layer0)
        assertEquals(0, late.layer1)
        assertEquals(0.5, late.weight, 1e-5)
    }

    @Test
    fun overrideReplacesTimePositionDirectionAndFov() {
        val s = state(0.0, 0.0)
        val p = Frames.geodeticToEcef(Geodetic(0.3, 1.0, 400.0))
        val forward = SceneGeometry.directionFromAzEl(p, 90.0, -10.0)
        val o = SceneOverride(
            instant = Instant.parse("2026-03-20T22:00:00Z"),
            positionEcefKm = p,
            cameraForwardEcef = forward,
            fovLongDeg = 60.0,
        )
        // The caller propagates the orbit at the override instant; build applies position, direction and FOV.
        val atInstant = SceneState(o.instant!!, s.positionEcefKm, s.velocityEcefKmPerSec, Vector3(0.0, 0.0, 1.0), Vector3(1.0, 0.0, 0.0))
        val scene = SceneInputs.build(atInstant, RestView.NADIR, 5.0, 5.0, o)
        assertEquals(o.instant, scene.instant)
        assertVec(p, scene.cameraEcefKm, 1e-12)
        assertVec(forward, -scene.ecefFromCamera.c2, 1e-12)
        assertEquals(60.0, scene.fovLongDeg, 0.0)
        assertVec(Vector3(0.0, 0.0, 1.0), scene.sunDirEcef, 0.0)
        // Camera up stays in the vertical plane through the forward direction (no roll).
        val up = SceneGeometry.enu(p).up
        assertEquals(0.0, (forward cross up) dot scene.ecefFromCamera.c1, 1e-12)
    }

    /** EOL metadata of the P3a dev photos (reference/dev-set.json): Sun at the nadir point, rounded to 1 deg. */
    @Test
    fun poseOverrideComputesSunWithoutSgp4AtAnyDate() {
        data class Eol(val time: String, val lat: Double, val lon: Double, val altKm: Double, val sunEl: Double, val sunAz: Double)
        for (e in listOf(
            Eol("2010-02-17T08:01:15Z", 14.3, 26.9, 348.0, 45.0, 124.0),
            Eol("2025-09-22T05:16:55Z", 19.0, 121.3, 411.0, 61.0, 232.0),
        )) {
            val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(e.lat), Math.toRadians(e.lon), e.altKm))
            val scene = SceneInputs.buildPose(SceneOverride(instant = Instant.parse(e.time), positionEcefKm = p))!!
            val enu = SceneGeometry.enu(p)
            val s = scene.sunDirEcef
            assertEquals(e.time, e.sunEl, Math.toDegrees(asin(s dot enu.up)), 1.0)
            val az = Math.toDegrees(kotlin.math.atan2(s dot enu.east, s dot enu.north)).let { if (it < 0) it + 360 else it }
            assertEquals(e.time, e.sunAz, az, 2.0)
            assertEquals(Instant.parse(e.time), scene.instant)
            assertVec(p, scene.cameraEcefKm, 0.0)
            // No forward given: the camera looks at nadir.
            assertVec(-enu.up, -scene.ecefFromCamera.c2, 1e-12)
            assertEquals(1.0, scene.moonDirEcef.norm(), 1e-12)
        }
        assertNull(SceneInputs.buildPose(SceneOverride(instant = Instant.parse("2010-02-17T08:01:15Z"))))
    }

    @Test
    fun writeFillsTheFrameInputsLayout() {
        val scene = SceneInputs.build(state(10.0, 20.0), RestView.NADIR, 0.0, 0.0)
        val inputs = FrameInputs.empty()
        scene.write(inputs)
        assertEquals(1f, inputs[FrameInputs.SCENE])
        assertEquals(scene.cameraEcefKm.x.toFloat(), inputs[FrameInputs.CAMERA_POS_KM])
        assertEquals(scene.ecefFromCamera.c2.z.toFloat(), inputs[FrameInputs.CAMERA_BACK + 2])
        assertEquals(scene.sunDirEcef.y.toFloat(), inputs[FrameInputs.SUN_DIR + 1])
        assertEquals(6f, inputs[FrameInputs.MONTH_LAYER0])
        val a = Frames.WGS84_A_KM
        val b = a * (1 - Frames.WGS84_F)
        val c = scene.cameraEcefKm
        val q = (c.x / a) * (c.x / a) + (c.y / a) * (c.y / a) + (c.z / b) * (c.z / b) - 1
        assertEquals(q.toFloat(), inputs[FrameInputs.CAMERA_ELLIPSOID_C])
    }

    /**
     * Sky frame of the view LUT in the ellipsoid-scaled space (z x a / b): radial up, the Sun's azimuth axis, and the
     * nadir angles of the ground horizon and of the atmosphere top ([Atmosphere.TOP_KM], same as atmosphere_params.h).
     */
    @Test
    fun writeFillsTheSkyFrameAndDefaultEffects() {
        val st = state(40.0, 20.0).copy(sunDirEcef = SceneGeometry.unit(Vector3(0.3, -0.5, 0.8)))
        val scene = SceneInputs.build(st, RestView.NADIR, 0.0, 0.0)
        val inputs = FrameInputs.empty()
        scene.write(inputs)
        val a = Frames.WGS84_A_KM
        val b = a * (1 - Frames.WGS84_F)
        val c = scene.cameraEcefKm
        val scaled = Vector3(c.x, c.y, c.z * a / b)
        val up = SceneGeometry.unit(scaled)
        fun vec(at: Int) = Vector3(inputs[at].toDouble(), inputs[at + 1].toDouble(), inputs[at + 2].toDouble())
        assertVec(up, vec(FrameInputs.SKY_UP), 1e-6)
        val axis = vec(FrameInputs.SKY_SUN_AXIS)
        assertEquals(1.0, axis.norm(), 1e-6)
        assertEquals(0.0, axis dot up, 1e-6)
        // The Sun lies in the plane of up and the axis, on the axis side.
        val sun = scene.sunDirEcef
        assertEquals(0.0, sun dot (up cross axis), 1e-6)
        assertEquals((sun - up * (sun dot up)).norm(), sun dot axis, 1e-6)
        assertEquals(asin(a / scaled.norm()), inputs[FrameInputs.SKY_HORIZON_NADIR].toDouble(), 1e-6)
        assertEquals(asin((a + 100.0) / scaled.norm()), inputs[FrameInputs.SKY_TOP_NADIR].toDouble(), 1e-6)
        assertEquals(FrameInputs.EFFECTS_ALL.toFloat(), inputs[FrameInputs.EFFECTS])
    }

    @Test
    fun atmosphereConstantsMatchTheNativeHeader() {
        val header = java.io.File("src/main/cpp/atmosphere_params.h").readText()
        fun define(name: String) = Regex("""#define\s+$name\s+([0-9.eE+-]+)""").find(header)!!.groupValues[1].toDouble()
        assertEquals(define("ATMO_BOTTOM_KM"), Atmosphere.BOTTOM_KM, 0.0)
        assertEquals(define("ATMO_TOP_KM"), Atmosphere.TOP_KM, 0.0)
    }
}
