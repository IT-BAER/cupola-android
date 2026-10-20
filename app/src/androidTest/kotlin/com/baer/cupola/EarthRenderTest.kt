package com.baer.cupola

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.NativeRenderer
import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.core.render.scene.Scene
import com.baer.cupola.core.render.scene.SceneGeometry
import com.baer.cupola.core.render.scene.SceneInputs
import com.baer.cupola.core.render.scene.SceneOverride
import com.baer.cupola.core.render.scene.SceneState
import com.baer.cupola.core.render.scene.toSceneState
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import com.baer.cupola.orbit.frames.angleTo
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.frames.plus
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.frames.unaryMinus
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.tle.Tle
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P3.1 Earth at fixed debug overrides (plan P3 acceptance, analytic parts). Needs the app installed from the
 * bundle (bundletool install-apks) so the earthpack textures are present. PNGs go to the app's external files dir
 * under `p3/`.
 */
@RunWith(AndroidJUnit4::class)
class EarthRenderTest {
    private fun scene(instant: Instant, latDeg: Double, lonDeg: Double, azDeg: Double, elDeg: Double): Scene {
        val state = (orbit.stateAt(instant) as OrbitState).toSceneState()
        val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(latDeg), Math.toRadians(lonDeg), ALT_KM))
        val override = SceneOverride(instant, p, SceneGeometry.directionFromAzEl(p, azDeg, elDeg))
        return SceneInputs.build(state, RestView.NADIR, 0.0, 0.0, override)
    }

    private fun render(scene: Scene): ByteArray {
        val inputs = FrameInputs.empty()
        scene.write(inputs)
        return renderer.renderOffscreen(W, H, inputs)
    }

    /** Geocentric lat/lon in degrees of the subsolar point at [instant]. */
    private fun subsolar(instant: Instant): Pair<Double, Double> {
        val s = (orbit.stateAt(instant) as OrbitState).toSceneState().sunDirEcef
        return Math.toDegrees(asin(s.z)) to Math.toDegrees(atan2(s.y, s.x))
    }

    @Test
    fun dayNadirUsesTheRealTexturesNotTheFallback() {
        assertEquals(NativeRenderer.TEXTURE_SET_COMPLETE, renderer.textureSetMask())
        val img = render(scene(DAY, 25.0, 32.0, 0.0, -90.0))
        save("day_nadir", img)
        // The fallback is a uniform grey ground; real textures give colour and contrast across the frame.
        val lum = (0 until H step 8).flatMap { y -> (0 until W step 8).map { x -> luma(img, x, y) } }
        val mean = lum.average()
        val sd = sqrt(lum.sumOf { (it - mean) * (it - mean) } / lum.size)
        val (r, g, b) = channelMeans(img)
        Log.i(TAG, "day nadir mean luma $mean sd $sd rgb $r $g $b")
        assertTrue("contrast sd $sd", sd > 10.0)
        assertTrue("colour, not grey: rgb $r $g $b", maxOf(abs(r - g), abs(g - b), abs(r - b)) > 8.0)
    }

    @Test
    fun limbWithinTwoPixelsOfTheAnalyticHorizon() {
        val (lat, lon) = subsolar(GLINT)
        val scene = scene(GLINT, lat, lon, 0.0, -20.0)
        val img = render(scene)
        save("limb", img)
        var worst = 0.0
        for (x in 0 until W step 54) {
            val analytic = horizonRow(scene, x + 0.5)
            assertNotNull("no horizon in column $x", analytic)
            val rendered = (0 until H).firstOrNull { y -> rgbSum(img, x, y) > 0 }
            assertNotNull("no Earth in column $x", rendered)
            // The first lit row's centre lies within one row of the continuous boundary.
            val d = abs(rendered!! + 0.5 - analytic!!)
            worst = maxOf(worst, d)
        }
        Log.i(TAG, "limb worst |rendered - analytic| = $worst px")
        assertTrue("limb off by $worst px", worst <= 2.0)
    }

    @Test
    fun glintCentreWithinOneDegreeOfTheSpecularPoint() {
        val (lat, lon) = subsolar(GLINT)
        // ISS 6 degrees west of the subsolar point over the open Pacific: the specular point sits between.
        val scene = scene(GLINT, lat, lon - 6.0, 0.0, -90.0)
        val img = render(scene)
        save("glint", img)
        val analytic = specularPixel(scene)
        assertNotNull("specular point not in view", analytic)
        // Rendered centre: luma-weighted centroid of the pixels within 5 % of the brightest.
        val step = 2
        var max = 0.0
        for (y in 0 until H step step) for (x in 0 until W step step) max = maxOf(max, luma(img, x, y))
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        for (y in 0 until H step step) for (x in 0 until W step step) {
            val l = luma(img, x, y)
            if (l >= 0.95 * max) {
                sx += l * (x + 0.5); sy += l * (y + 0.5); sw += l
            }
        }
        val rendered = scene.rayEcef(sx / sw, sy / sw, W, H)
        val expected = scene.rayEcef(analytic!!.first, analytic.second, W, H)
        val angle = Math.toDegrees(rendered.angleTo(expected))
        Log.i(TAG, "glint centre (${sx / sw}, ${sy / sw}) analytic $analytic angle $angle deg max luma $max")
        assertTrue("glint centre $angle deg from the specular point", angle <= 1.0)
    }

    /**
     * On the equator east of the subsolar meridian, looking west (sunward) 50 deg down; the camera longitude puts the
     * terminator (Sun on the horizon) at the frame centre: lit ground above it, night below.
     */
    @Test
    fun terminatorCrossesTheFrameCentre() {
        val (_, lon) = subsolar(GLINT)
        val sceneAt = { off: Double -> scene(GLINT, 0.0, lon + off, 270.0, -50.0) }
        val centreSun = { sc: Scene ->
            val d = sc.rayEcef(W / 2.0, H / 2.0, W, H)
            val hit = sc.cameraEcefKm + d * SceneGeometry.intersectEllipsoid(sc.cameraEcefKm, d)!!
            abs(SceneGeometry.enu(hit).up dot sc.sunDirEcef)
        }
        val best = (0..300).map { 90.0 + it * 0.05 }.minBy { centreSun(sceneAt(it)) }
        val img = render(sceneAt(best))
        save("terminator", img)
        // Day above the centre row, night below: compare 200-row bands either side of a 200-row margin.
        fun band(y0: Int) = (y0 until y0 + 200 step 4).flatMap { y -> (0 until W step 4).map { x -> luma(img, x, y) } }.average()
        val day = band(H / 2 - 300)
        val night = band(H / 2 + 100)
        Log.i(TAG, "terminator lon offset $best day band $day night band $night")
        assertTrue("day band $day", day > 5.0)
        assertTrue("night band $night", night < 0.5)
    }

    /**
     * Equatorial Atlantic over the Mid-Atlantic Ridge fracture zones (no island in the frame), nadir, Sun about 40 deg
     * up. The deep ocean is one colour: the mean luma step between pixels 16 apart stays below 1 level (the smooth
     * shading changes by < 0.3 per 16 px here; BMNG paints the seafloor relief into this area).
     */
    @Test
    fun openOceanIsUniform() {
        val img = render(scene(DAY, 0.0, -20.0, 0.0, -90.0))
        save("ocean", img)
        var sum = 0.0
        var n = 0
        for (y in 0 until H step 16) for (x in 0 until W - 16 step 16) {
            sum += abs(luma(img, x + 16, y) - luma(img, x, y)); n++
        }
        for (y in 0 until H - 16 step 16) for (x in 0 until W step 16) {
            sum += abs(luma(img, x, y + 16) - luma(img, x, y)); n++
        }
        Log.i(TAG, "ocean mean |step16| ${sum / n} mean luma ${channelMeans(img)}")
        assertTrue("ocean step ${sum / n}", sum / n < 1.0)
    }

    /**
     * P3a probe poses from the EOL metadata in reference/dev-set.json (nadir view, no SGP4: the dates lie far from
     * the bundled TLE). FOV = lens focal length on the camera's long sensor axis, mapped to the long screen axis.
     * Atmosphere and clouds are not rendered yet.
     */
    @Test
    fun p3aProbePoses() {
        data class Probe(val id: String, val time: String, val lat: Double, val lon: Double, val altKm: Double, val focalMm: Double, val sensorLongMm: Double)
        for (p in listOf(
            Probe("iss073e0764640", "2025-09-22T05:16:55Z", 19.0, 121.3, 411.0, 24.0, 35.9), // Nikon Z 9
            Probe("iss022e066972", "2010-02-17T08:01:15Z", 14.3, 26.9, 348.0, 12.0, 23.7), // Nikon D2Xs
        )) {
            val pos = Frames.geodeticToEcef(Geodetic(Math.toRadians(p.lat), Math.toRadians(p.lon), p.altKm))
            val fov = Math.toDegrees(2 * atan2(p.sensorLongMm / 2, p.focalMm))
            val scene = SceneInputs.buildPose(SceneOverride(Instant.parse(p.time), pos, fovLongDeg = fov))!!
            val img = render(scene)
            save("p3a_${p.id}", img)
            val lum = (0 until H step 8).flatMap { y -> (0 until W step 8).map { x -> luma(img, x, y) } }
            Log.i(TAG, "p3a ${p.id} fov %.1f deg mean luma %.1f".format(fov, lum.average()))
            assertTrue("${p.id} is dark: ${lum.average()}", lum.average() > 20.0)
        }
    }

    @Test
    fun gpuTimeEarthOnlyFullResolution() {
        val scene = scene(DAY, 25.0, 32.0, 0.0, -20.0)
        val times = (0 until GPU_SAMPLES).map {
            render(scene)
            renderer.lastGpuTimeNanos() / 1e6
        }.filter { it >= 0 }.sorted()
        assertTrue("no GPU timestamps", times.isNotEmpty())
        val p50 = times[(times.size - 1) / 2]
        val p95 = times[((times.size - 1) * 95 + 99) / 100]
        Log.i(TAG, "earth gpu ${W}x$H n=${times.size} p50=%.3f ms p95=%.3f ms max=%.3f ms".format(p50, p95, times.last()))
    }

    /** Continuous y (pixel units) of the hit/miss boundary in column [x], searching from the top. */
    private fun horizonRow(scene: Scene, x: Double): Double? {
        val hits = { y: Double -> SceneGeometry.intersectEllipsoid(scene.cameraEcefKm, scene.rayEcef(x, y, W, H)) != null }
        if (hits(0.0) || !hits(H.toDouble())) return null
        var lo = 0.0
        var hi = H.toDouble()
        repeat(40) {
            val mid = (lo + hi) / 2
            if (hits(mid)) hi = mid else lo = mid
        }
        return hi
    }

    /** Pixel centre whose ellipsoid hit has the geodetic normal closest to the sun-view half vector. */
    private fun specularPixel(scene: Scene): Pair<Double, Double>? {
        fun score(x: Double, y: Double): Double {
            val dir = scene.rayEcef(x, y, W, H)
            val t = SceneGeometry.intersectEllipsoid(scene.cameraEcefKm, dir) ?: return -2.0
            val n = SceneGeometry.enu(scene.cameraEcefKm + dir * t).up
            val h = SceneGeometry.unit(scene.sunDirEcef + -dir)
            return n dot h
        }
        var best: Pair<Double, Double>? = null
        var bestScore = -1.0
        fun scan(x0: Int, x1: Int, y0: Int, y1: Int, step: Int) {
            for (y in y0 until y1 step step) for (x in x0 until x1 step step) {
                val s = score(x + 0.5, y + 0.5)
                if (s > bestScore) {
                    bestScore = s
                    best = (x + 0.5) to (y + 0.5)
                }
            }
        }
        scan(0, W, 0, H, 8)
        val (bx, by) = best ?: return null
        scan((bx - 8).toInt().coerceAtLeast(0), (bx + 8).toInt().coerceAtMost(W), (by - 8).toInt().coerceAtLeast(0), (by + 8).toInt().coerceAtMost(H), 1)
        return best
    }

    private fun at(x: Int, y: Int) = (y * W + x) * 4

    private fun ch(img: ByteArray, i: Int) = img[i].toInt() and 0xFF

    private fun rgbSum(img: ByteArray, x: Int, y: Int) = at(x, y).let { ch(img, it) + ch(img, it + 1) + ch(img, it + 2) }

    private fun luma(img: ByteArray, x: Int, y: Int) =
        at(x, y).let { 0.2126 * ch(img, it) + 0.7152 * ch(img, it + 1) + 0.0722 * ch(img, it + 2) }

    private fun channelMeans(img: ByteArray): Triple<Double, Double, Double> {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        for (y in 0 until H step 8) for (x in 0 until W step 8) {
            val i = at(x, y)
            r += ch(img, i); g += ch(img, i + 1); b += ch(img, i + 2); n++
        }
        return Triple(r / n, g / n, b / n)
    }

    private fun save(name: String, img: ByteArray) {
        val dir = File(context.getExternalFilesDir(null), "p3").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(img))
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i(TAG, "saved ${File(dir, "$name.png")}")
    }

    companion object {
        private const val TAG = "EarthRenderTest"
        private const val W = 1080
        private const val H = 2340
        private const val ALT_KM = 420.0
        private const val GPU_SAMPLES = 30

        // Inside the bundled TLE's validity (epoch 2026-10-18). Egypt near local noon; subsolar point over the Pacific.
        private val DAY = Instant.parse("2026-10-25T09:50:00Z")
        private val GLINT = Instant.parse("2026-10-25T20:25:00Z")

        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var renderer: NativeRenderer
        private lateinit var orbit: OrbitModel

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val lines = context.assets.open("orbit/iss.tle").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            orbit = OrbitModel(Tle.parse(lines[lines.size - 2], lines[lines.size - 1]))
            renderer = NativeRenderer(assets = context.assets)
        }

        @AfterClass
        @JvmStatic
        fun tearDown() = renderer.close()
    }
}
