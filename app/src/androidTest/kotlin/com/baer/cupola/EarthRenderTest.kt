package com.baer.cupola

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.NativeRenderer
import com.baer.cupola.core.render.scene.Exposure
import com.baer.cupola.core.render.scene.Mat3
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
import com.baer.cupola.orbit.frames.cross
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.frames.plus
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.frames.unaryMinus
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.tle.Tle
import com.baer.cupola.wallpaper.RendererHost
import com.baer.cupola.wallpaper.RendererHostEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
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

    /** [debug] = FrameInputs.DEBUG_* bits added to the scene's effects. */
    private fun render(
        scene: Scene,
        renderScale: Float = 1f,
        interior: Boolean = true,
        debug: Int = 0,
        animSeconds: Float = 0f,
        effects: Int = 0, // user effects off: the analytic checks see the bare scene
        homeCity: Vector3? = null,
    ): ByteArray = renderer.renderOffscreen(W, H, inputs(scene, renderScale, interior, debug, animSeconds, effects, homeCity))

    private fun inputs(
        scene: Scene,
        renderScale: Float = 1f,
        interior: Boolean = true,
        debug: Int = 0,
        animSeconds: Float = 0f,
        effects: Int = 0,
        homeCity: Vector3? = null,
    ): FloatArray {
        val inputs = FrameInputs.empty()
        scene.write(inputs)
        if (homeCity != null) {
            inputs[FrameInputs.HOME_CITY] = homeCity.x.toFloat()
            inputs[FrameInputs.HOME_CITY + 1] = homeCity.y.toFloat()
            inputs[FrameInputs.HOME_CITY + 2] = homeCity.z.toFloat()
            inputs[FrameInputs.HOME_CITY + 3] = 1f
        }
        inputs[FrameInputs.TIME_SECONDS] = animSeconds
        inputs[FrameInputs.RENDER_SCALE] = renderScale
        inputs[FrameInputs.EFFECTS] = ((inputs[FrameInputs.EFFECTS].toInt() and FrameInputs.EFFECTS_ALL.inv()) or effects or debug).toFloat()
        if (!interior) inputs[FrameInputs.INTERIOR] = 0f
        return inputs
    }

    private fun restScene(instant: Instant, rest: RestView) =
        SceneInputs.build((orbit.stateAt(instant) as OrbitState).toSceneState(), rest, 0.0, 0.0)

    /** First instant after [DAY] (1 min steps) with the ISS deep in the Earth's shadow. */
    private fun nightInstant(): Instant = (0L until 180L).map { DAY.plusSeconds(60 * it) }.first {
        val s = (orbit.stateAt(it) as OrbitState).toSceneState()
        (SceneGeometry.unit(s.positionEcefKm) dot s.sunDirEcef) < -0.5
    }

    /** Logical pixel (x, y) where the Blender-frame direction [d] lands, or null behind the camera. */
    private fun project(scene: Scene, d: Vector3): Pair<Double, Double>? {
        val m = scene.blenderFromCamera!!
        val z = -(d dot m.c2)
        if (z <= 0) return null
        val (fx, fy) = scene.fovDeg(W, H)
        val tx = tan(Math.toRadians(fx / 2))
        val ty = tan(Math.toRadians(fy / 2))
        return ((d dot m.c0) / z / tx + 1) / 2 * W to (1 - (d dot m.c1) / z / ty) / 2 * H
    }

    /**
     * Interior panorama at a fixed pose: the camera looks 12 deg beside the laptop screen on the -X face (its bright
     * emissive centre at texel (604, 1274), P4.4 controller check); the screen's bright centroid lands within 25 px of
     * the pixel the contract predicts. ISS in shadow so the screen is the brightest feature.
     */
    @Test
    fun interiorLaptopScreenLandsOnItsPredictedPixel() {
        val laptop = SceneGeometry.unit(Vector3(-1.0, 2 * 604.5 / 2048 - 1, 2 * 1274.5 / 2048 - 1))
        val down = Vector3(0.0, 0.0, 1.0) // Blender +Z = nadir
        val side = SceneGeometry.unit(laptop cross down)
        val forward = SceneGeometry.unit(laptop * cos(Math.toRadians(12.0)) + side * sin(Math.toRadians(12.0)))
        val right = SceneGeometry.unit(forward cross (-down))
        val camera = Mat3(right, right cross forward, -forward)
        val night = nightInstant()
        val state = (orbit.stateAt(night) as OrbitState).toSceneState()
        val scene = restScene(night, RestView.DIAGONAL).copy(
            blenderFromCamera = camera,
            ecefFromCamera = SceneGeometry.lvlh(state.positionEcefKm, state.velocityEcefKmPerSec) *
                SceneGeometry.LVLH_FROM_BLENDER * camera,
        )
        val (px, py) = project(scene, laptop)!!
        val img = render(scene)
        save("interior_laptop", img)
        val x0 = (px - 150).toInt().coerceIn(0, W - 1)
        val x1 = (px + 150).toInt().coerceIn(0, W - 1)
        val y0 = (py - 150).toInt().coerceIn(0, H - 1)
        val y1 = (py + 150).toInt().coerceIn(0, H - 1)
        var max = 0.0
        for (y in y0..y1) for (x in x0..x1) max = maxOf(max, luma(img, x, y))
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in y0..y1) for (x in x0..x1) if (luma(img, x, y) >= 0.9 * max) {
            sx += x + 0.5; sy += y + 0.5; n++
        }
        val dist = sqrt((sx / n - px) * (sx / n - px) + (sy / n - py) * (sy / n - py))
        Log.i(TAG, "laptop predicted ($px, $py) bright centroid (${sx / n}, ${sy / n}) n $n max luma $max dist $dist")
        assertTrue("laptop screen too dark: max luma $max", max > 100.0)
        assertTrue("laptop centroid $dist px from the prediction", dist <= 25.0)
    }

    /**
     * Nadir rest view by day: the nadir window centre (Blender +Z, frame centre) shows the Earth as the Earth-only render,
     * and the interior covers part of the frame.
     */
    @Test
    fun windowShowsTheEarthAndTheInteriorCoversTheRest() {
        val scene = restScene(DAY, RestView.NADIR)
        val composite = render(scene)
        val earth = render(scene.copy(blenderFromCamera = null))
        save("rest_nadir_day", composite)
        save("rest_diagonal_day", render(restScene(DAY, RestView.DIAGONAL)))
        val (px, py) = project(scene, Vector3(0.0, 0.0, 1.0))!!
        var diff = 0.0
        var lum = 0.0
        for (y in py.toInt() - 2..py.toInt() + 2) for (x in px.toInt() - 2..px.toInt() + 2) {
            diff += abs(luma(composite, x, y) - luma(earth, x, y)); lum += luma(earth, x, y)
        }
        var covered = 0
        var total = 0
        for (y in 0 until H step 8) for (x in 0 until W step 8) {
            total++
            if (abs(luma(composite, x, y) - luma(earth, x, y)) > 2.0) covered++
        }
        val share = covered.toDouble() / total
        Log.i(TAG, "window pixel ($px, $py) mean |diff| ${diff / 25} earth luma ${lum / 25} interior share $share")
        assertTrue("window shows no Earth: luma ${lum / 25}", lum / 25 > 20.0)
        assertTrue("window differs from the Earth: ${diff / 25}", diff / 25 <= 2.0)
        assertTrue("interior share $share", share in 0.2..0.95)
    }

    /**
     * Plan P3 acceptance "Night passes are never black": both rest views in the Earth's shadow at the default render
     * scale; the frame's mean luma (0..255) lies in [NIGHT_BAND] (lower edge about half the P4.7 night photo's interior median of 33.4).
     */
    @Test
    fun nightRestViewsStayInsideTheLuminanceBand() {
        val night = nightInstant()
        for (rest in RestView.entries) {
            val img = render(restScene(night, rest), 0.6f)
            save("night_band_${rest.name.lowercase()}", img)
            val mean = meanLuma(img)
            Log.i(TAG, "night $rest mean luma $mean")
            assertTrue("night $rest mean luma $mean outside $NIGHT_BAND", mean in NIGHT_BAND)
        }
    }

    /**
     * Day interior brightness: median luma of the pixels where the interior covers the Earth (composite differs from
     * the Earth-only render by > 2 levels), DIAGONAL rest view; not below the P3.1 level [DAY_INTERIOR_MEDIAN_MIN].
     */
    @Test
    fun dayInteriorIsNotDarkerThanP31() {
        for (rest in RestView.entries) {
            val scene = restScene(DAY, rest)
            val composite = render(scene, 0.6f)
            val earth = render(scene.copy(blenderFromCamera = null), 0.6f)
            val inside = ArrayList<Double>()
            for (y in 0 until H step 4) for (x in 0 until W step 4) {
                if (abs(luma(composite, x, y) - luma(earth, x, y)) > 2.0) inside += luma(composite, x, y)
            }
            inside.sort()
            val median = inside[inside.size / 2]
            Log.i(TAG, "day interior $rest median luma $median of ${inside.size} samples, frame mean ${meanLuma(composite)}")
            if (rest == RestView.DIAGONAL) assertTrue("day interior median $median", median >= DAY_INTERIOR_MEDIAN_MIN)
        }
    }

    /** The scaled Earth pass composites to nearly the full-resolution frame (mean |luma difference| < 3 levels). */
    @Test
    fun renderScaleMatchesFullResolution() {
        val scene = restScene(DAY, RestView.DIAGONAL)
        val full = render(scene)
        val scaled = render(scene, 0.6f)
        save("rest_diagonal_day_s060", scaled)
        save("rest_nadir_day_s060", render(restScene(DAY, RestView.NADIR), 0.6f))
        save("rest_diagonal_night", render(restScene(nightInstant(), RestView.DIAGONAL), 0.6f))
        var sum = 0.0
        var n = 0
        for (y in 0 until H step 4) for (x in 0 until W step 4) {
            sum += abs(luma(full, x, y) - luma(scaled, x, y)); n++
        }
        Log.i(TAG, "render scale 0.6 vs 1.0 mean |luma diff| ${sum / n}")
        assertTrue("scaled differs by ${sum / n}", sum / n < 3.0)
    }

    /**
     * Stars keep full resolution when the Earth renders scaled: at every star peak of the full-resolution frame the
     * scale 0.6 frame is within [STAR_PEAK_TOLERANCE] luma. One-texel stars upscaled from the scaled pass twinkle as
     * the view moves by fractions of a pixel (owner report 24 Oct, "static flickering").
     */
    @Test
    fun starsKeepFullResolutionAtRenderScale() {
        val scene = scene(Instant.parse("2026-10-25T21:00:00Z"), 48.0, 10.0, 0.0, 20.0)
        val full = render(scene, interior = false)
        val scaled = render(scene, 0.6f, interior = false)
        save("stars_night_s060", scaled)
        var peaks = 0
        var diff = 0.0
        for (y in 2 until H - 2) for (x in 2 until W - 2) {
            val l = luma(full, x, y)
            if (l > 60 && l > luma(full, x - 2, y) + 25 && l > luma(full, x + 2, y) + 25 &&
                l > luma(full, x, y - 2) + 25 && l > luma(full, x, y + 2) + 25
            ) {
                peaks++
                diff += abs(luma(scaled, x, y) - l)
            }
        }
        Log.i(TAG, "star peaks $peaks mean |scaled - full| ${diff / peaks}")
        assertTrue("star peaks $peaks", peaks > 40)
        assertTrue("scaled star peaks differ by ${diff / peaks}", diff / peaks < STAR_PEAK_TOLERANCE)
    }

    /**
     * A render-scale change on a live surface rebinds the new scaled Earth target. A35 24 Oct: after a governor step
     * the composite kept sampling the destroyed target (the new view reused its handle) and showed garbage blocks.
     * Each step renders another instant, so a stale binding shows the previous Earth or garbage, not this frame.
     */
    @Test
    fun renderScaleChangeRebindsTheScaledEarthTarget() {
        val steps = listOf(0.6f, 0.5f, 0.4f, 0.6f).mapIndexed { i, scale ->
            val inputs = inputs(restScene(DAY.plusSeconds(300L * i), RestView.NADIR), scale, effects = FrameInputs.EFFECTS_ALL)
            Triple(scale, inputs, renderer.renderOffscreen(W, H, inputs))
        }
        val reader = ImageReader.newInstance(
            W, H, PixelFormat.RGBA_8888, 3, HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
        val failures = mutableListOf<String>()
        try {
            renderer.attachSurface(SURFACE_ID, reader.surface)
            // Keeps the Choreographer loop from filling the reader's queue while the test reads it.
            renderer.updateScheduler(SURFACE_ID) { setVisible(false) }
            for ((scale, inputs, expected) in steps) {
                renderer.setFrameInputs(SURFACE_ID, inputs)
                var presented = ByteArray(0)
                repeat(FRAMES_PER_STEP) { presented = presentAndRead(reader) }
                var off = 0
                var n = 0
                for (y in 0 until H step 4) for (x in 0 until W step 4) {
                    n++
                    if (abs(luma(presented, x, y) - luma(expected, x, y)) > 24) off++
                }
                Log.i(TAG, "scale $scale: presented frame off the offscreen render at $off of $n samples")
                if (off > n / 200) {
                    failures += "scale $scale: $off of $n"
                    save("scale_change_${(scale * 10).toInt()}", presented)
                }
            }
        } finally {
            renderer.detachSurface(SURFACE_ID)
            reader.close()
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    /** Renders and presents one frame of [SURFACE_ID] and returns it as read back from [reader] (RGBA8, top row first). */
    private fun presentAndRead(reader: ImageReader): ByteArray {
        var presented = false
        for (attempt in 0 until 3) presented = presented || renderer.renderFrame(SURFACE_ID)
        assertTrue("frame not presented", presented)
        val deadline = SystemClock.uptimeMillis() + 5_000
        var image = reader.acquireLatestImage()
        while (image == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10)
            image = reader.acquireLatestImage()
        }
        assertNotNull("no frame reached the ImageReader", image)
        return image!!.use {
            val plane = it.planes[0]
            val out = ByteArray(W * H * 4)
            for (y in 0 until H) {
                plane.buffer.position(y * plane.rowStride)
                plane.buffer.get(out, y * W * 4, W * 4)
            }
            out
        }
    }

    /** Full frame GPU time (interior + Earth) per rest view and render scale; logged, the budget is checked by hand. */
    @Test
    fun gpuTimeFullFrame() {
        // The DIAGONAL runs without the interior split the composite cost into interior lookups and the rest.
        val configs = RestView.entries.flatMap { rest -> listOf(1f, 0.6f, 0.5f).map { Triple(rest, it, true) } } +
            listOf(1f, 0.5f).map { Triple(RestView.DIAGONAL, it, false) }
        for ((rest, scale, interior) in configs) {
            val scene = restScene(DAY, rest)
            repeat(GPU_WARMUP) { render(scene, scale, interior, effects = FrameInputs.EFFECTS_ALL) }
            val samples = (0 until GPU_SAMPLES).map {
                render(scene, scale, interior, effects = FrameInputs.EFFECTS_ALL) // the default settings
                Triple(renderer.lastGpuTimeNanos() / 1e6, renderer.lastEarthPassGpuTimeNanos() / 1e6, renderer.lastSkyPassGpuTimeNanos() / 1e6)
            }.filter { it.first >= 0 && it.second >= 0 && it.third >= 0 }
            assertTrue("no GPU timestamps", samples.isNotEmpty())
            fun pct(values: List<Double>, p: Int) = values.sorted()[((values.size - 1) * p + 99) / 100]
            val total = samples.map { it.first }
            val earth = samples.map { it.second }
            val sky = samples.map { it.third }
            val composite = samples.map { it.first - it.second - it.third }
            Log.i(
                TAG,
                "frame gpu $rest scale $scale interior $interior ${W}x$H n=${samples.size} p50=%.3f ms p95=%.3f ms sky p50=%.3f earth p50=%.3f composite p50=%.3f"
                    .format(pct(total, 50), pct(total, 95), pct(sky, 50), pct(earth, 50), pct(composite, 50)),
            )
        }
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

    /** Gulf of Guinea at nadir with the Sun high: the ITCZ cloud band crosses the view. */
    private fun cloudScene() = scene(DAY, 0.0, 10.0, 0.0, -90.0)

    /** P3.2: clouds cover part of a sunlit view, look white, and only add light. */
    @Test
    fun cloudsCoverPartOfTheDayEarthInWhite() {
        val clear = render(cloudScene(), interior = false, debug = FrameInputs.DEBUG_NO_CLOUDS)
        val cloudy = render(cloudScene(), interior = false)
        save("clouds_day", cloudy)
        var cloudPx = 0
        var n = 0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        for (y in 0 until H step 6) for (x in 0 until W step 6) {
            n++
            if (luma(cloudy, x, y) - luma(clear, x, y) > 20) {
                cloudPx++
                val i = at(x, y)
                r += ch(cloudy, i); g += ch(cloudy, i + 1); b += ch(cloudy, i + 2)
            }
        }
        val fraction = cloudPx.toDouble() / n
        Log.i(TAG, "clouds fraction $fraction rgb ${r / cloudPx} ${g / cloudPx} ${b / cloudPx}")
        assertTrue("cloud fraction $fraction", fraction in 0.05..0.95)
        val spread = (maxOf(r, g, b) - minOf(r, g, b)) / maxOf(r, g, b)
        assertTrue("clouds white, not tinted: rgb $r $g $b", spread < 0.15)
        assertTrue("clouds add light", meanLuma(cloudy) > meanLuma(clear) + 5)
    }

    /** Clouds drift: a minute changes almost nothing, six hours (one flow period) change the field clearly. */
    @Test
    fun cloudsMoveSmoothlyAndChangeOverHours() {
        val base = cloudScene()
        fun at(seconds: Long) = render(base.copy(instant = DAY.plusSeconds(seconds)), interior = false)
        val a = at(0)
        val minute = meanAbsDiff(a, at(60))
        val hours = meanAbsDiff(a, at(6 * 3600))
        Log.i(TAG, "cloud change 1 min $minute, 6 h $hours")
        assertTrue("1 min changes little: $minute", minute < 1.5)
        assertTrue("6 h change clearly: $hours vs $minute", hours > 3.0 && hours > 4 * minute)
    }

    /**
     * At low sun (north India, Sun zenith 61 deg) cloud shadows darken the view, also on clear ground beside the
     * clouds; most darkening lies under thin cloud, which its own shadow shows through. A35 24 Oct: mean -1.89 luma,
     * 1209 darkened samples on clear ground (of 70200).
     */
    @Test
    fun cloudShadowsDarkenClearGroundAtLowSun() {
        val (sLat, sLon) = subsolar(DAY)
        val scene = scene(DAY, 30.0, 75.0, 0.0, -90.0)
        val clear = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_CLOUDS)
        val noShadow = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_CLOUD_SHADOWS)
        val full = render(scene, interior = false)
        save("clouds_low_sun", full)
        var darkened = 0
        var darkenedClear = 0
        for (y in 0 until H step 6) for (x in 0 until W step 6) {
            if (luma(full, x, y) < luma(noShadow, x, y) - 4) {
                darkened++
                if (luma(noShadow, x, y) - luma(clear, x, y) < 10) darkenedClear++
            }
        }
        Log.i(TAG, "low sun (subsolar $sLat $sLon): shadowed samples $darkened, on clear ground $darkenedClear")
        assertTrue("shadows darken the view: ${meanLuma(full)} vs ${meanLuma(noShadow)}", meanLuma(full) < meanLuma(noShadow) - 1.0)
        assertTrue("shadows on clear ground: $darkenedClear of $darkened", darkenedClear > 500)
    }

    /** Night clouds get no sunlight: without moonlight the night side stays as dark as without clouds. */
    @Test
    fun nightCloudsGetNoSunlight() {
        val scene = restScene(nightInstant(), RestView.NADIR)
        val clear = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_CLOUDS or FrameInputs.DEBUG_NO_MOONLIGHT)
        val cloudy = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_MOONLIGHT)
        assertTrue("night ${meanLuma(cloudy)} vs ${meanLuma(clear)}", meanLuma(cloudy) < meanLuma(clear) + 1.0)
    }

    /** Europe at 22:00 local time, nadir. */
    private fun europeNight() = scene(Instant.parse("2026-10-25T21:00:00Z"), 48.0, 10.0, 0.0, -90.0)

    /** P3.4: city lights (warm, bright points) on the night side. */
    @Test
    fun cityLightsShowOnTheNightSide() {
        val lit = render(europeNight(), interior = false)
        val dark = render(europeNight(), interior = false, debug = FrameInputs.DEBUG_NO_CITY_LIGHTS)
        save("night_europe", lit)
        var warm = 0
        var n = 0
        for (y in 0 until H step 4) for (x in 0 until W step 4) {
            n++
            val i = at(x, y)
            if (luma(lit, x, y) > 60 && ch(lit, i) > ch(lit, i + 2) + 10) warm++
        }
        Log.i(TAG, "night europe warm lights ${warm.toDouble() / n} mean ${meanLuma(lit)} vs ${meanLuma(dark)}")
        assertTrue("warm light fraction ${warm.toDouble() / n}", warm.toDouble() / n > 0.003)
        assertTrue("lights add light", meanLuma(lit) > meanLuma(dark) + 1.0)
    }

    /** City lights are off in daylight: the sunlit Nile valley looks the same with and without them. */
    @Test
    fun cityLightsAreOffInDaylight() {
        val scene = scene(DAY, 25.0, 32.0, 0.0, -90.0)
        val diff = meanAbsDiff(render(scene, interior = false), render(scene, interior = false, debug = FrameInputs.DEBUG_NO_CITY_LIGHTS))
        assertTrue("day diff $diff", diff < 0.3)
    }

    /** Stars and the Milky Way fill night space (looking 20 deg up), and vanish at day exposure. */
    @Test
    fun starsShowInNightSpaceOnly() {
        fun points(img: ByteArray): Int {
            var count = 0
            for (y in 2 until H - 2 step 2) for (x in 2 until W - 2 step 2) {
                val l = luma(img, x, y)
                if (l > 60 && l > luma(img, x - 2, y) + 25 && l > luma(img, x + 2, y) + 25 &&
                    l > luma(img, x, y - 2) + 25 && l > luma(img, x, y + 2) + 25
                ) count++
            }
            return count
        }
        val night = render(scene(Instant.parse("2026-10-25T21:00:00Z"), 48.0, 10.0, 0.0, 20.0), interior = false)
        val day = render(scene(DAY, 25.0, 32.0, 0.0, 20.0), interior = false)
        save("stars_night", night)
        Log.i(TAG, "star points night ${points(night)} day ${points(day)}")
        assertTrue("night stars ${points(night)}", points(night) > 40)
        assertTrue("day stars ${points(day)}", points(day) == 0)
    }

    /**
     * W3: the Moon at the zenith of its sub-lunar point, 2 deg field: a disc of the right size whose lit share matches
     * the phase (1 + cos i) / 2 within 0.12.
     */
    @Test
    fun moonDiscShowsItsPhase() {
        for (instant in listOf(DAY, DAY.plusSeconds(6 * 86_400L), DAY.plusSeconds(12 * 86_400L))) {
            val state = (orbit.stateAt(instant) as OrbitState).toSceneState()
            val m = state.moonDirEcef
            val lat = Math.toDegrees(asin(m.z))
            val lon = Math.toDegrees(atan2(m.y, m.x))
            val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(lat), Math.toRadians(lon), ALT_KM))
            val scene = SceneInputs.build(state, RestView.NADIR, 0.0, 0.0, SceneOverride(instant, p, m, fovLongDeg = 2.0))
            val img = render(scene, interior = false)
            val radiusPx = tan(Math.toRadians(0.2725)) / tan(Math.toRadians(1.0)) * H / 2
            var disc = 0
            var lit = 0
            val ring = mutableListOf<Double>()
            val background = mutableListOf<Double>()
            for (y in 0 until H step 3) for (x in 0 until W step 3) {
                val r = sqrt((x - W / 2.0) * (x - W / 2.0) + (y - H / 2.0) * (y - H / 2.0))
                if (r < radiusPx * 0.9) {
                    disc++
                    if (luma(img, x, y) > 60) lit++
                }
                // The 2 deg field magnifies the star map to a dim glow: the ring just outside matches the sky beyond.
                if (r > radiusPx * 1.15 && r < radiusPx * 1.4) ring += luma(img, x, y)
                if (r > radiusPx * 1.6 && r < radiusPx * 2.2) background += luma(img, x, y)
            }
            assertTrue("disc edge: ring ${ring.average()} vs sky ${background.average()}", ring.average() < background.average() + 15)
            val cosI = -(state.sunDirEcef dot m)
            val expected = (1 + cosI) / 2
            Log.i(TAG, "moon $instant lit ${lit.toDouble() / disc} expected $expected radius $radiusPx")
            if (expected > 0.05) assertEquals("lit share at $instant", expected, lit.toDouble() / disc, 0.12)
        }
    }

    /** The airglow shell (85-100 km): a green line above the night limb, brighter than the space above it. */
    @Test
    fun airglowLinesTheNightLimb() {
        val scene = scene(Instant.parse("2026-10-25T21:00:00Z"), 48.0, 10.0, 0.0, -15.0)
        val img = render(scene, interior = false)
        save("airglow", img)
        val x = W / 2.0
        val ground = horizonRow(scene, x)!!
        val top = shellRow(scene, x, 6378.137 + 100.0)!!
        var g = 0.0
        var r = 0.0
        var lumaBand = 0.0
        var n = 0
        for (y in top.toInt() + 1 until ground.toInt() - 1) for (xx in W / 2 - 100 until W / 2 + 100 step 4) {
            val i = at(xx, y)
            r += ch(img, i); g += ch(img, i + 1); lumaBand += luma(img, xx, y); n++
        }
        val above = (top.toInt() - 60 until top.toInt() - 30).flatMap { y -> (W / 2 - 100 until W / 2 + 100 step 4).map { luma(img, it, y) } }.average()
        Log.i(TAG, "airglow rows ${top}..$ground r ${r / n} g ${g / n} luma ${lumaBand / n} above $above")
        assertTrue("green ${g / n} vs red ${r / n}", g > 1.3 * r)
        assertTrue("brighter than space above: ${lumaBand / n} vs $above", lumaBand / n > above + 5)
    }

    /** Moonlight lights the night ground and clouds: at full Moon, nadir under the Moon. */
    @Test
    fun fullMoonLightsTheNightSide() {
        val instant = (0L until 30L).map { DAY.plusSeconds(it * 86_400L) }.maxBy {
            val st = (orbit.stateAt(it) as OrbitState).toSceneState()
            -(st.sunDirEcef dot st.moonDirEcef)
        }
        val state = (orbit.stateAt(instant) as OrbitState).toSceneState()
        val m = state.moonDirEcef
        val scene = scene(instant, Math.toDegrees(asin(m.z)), Math.toDegrees(atan2(m.y, m.x)), 0.0, -90.0)
        val moonlit = render(scene, interior = false)
        val dark = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_MOONLIGHT)
        save("moonlit", moonlit)
        Log.i(TAG, "full moon $instant moonlit ${meanLuma(moonlit)} without ${meanLuma(dark)}")
        assertTrue("moonlight ${meanLuma(moonlit)} vs ${meanLuma(dark)}", meanLuma(moonlit) > meanLuma(dark) + 5)
    }

    /** South of Australia (magnetic latitude about -60 deg) looking south 15 deg down: the auroral oval rises above the limb. */
    private fun auroraScene(instant: Instant) = scene(instant, -50.0, 140.0, 180.0, -15.0)

    /** P3.4: aurora over the southern oval at local midnight; none in daylight. */
    @Test
    fun auroraGlowsOverTheNightOval() {
        val night = auroraScene(Instant.parse("2026-10-25T14:00:00Z"))
        val with = render(night, interior = false)
        val without = render(night, interior = false, debug = FrameInputs.DEBUG_NO_AURORA)
        save("aurora", with)
        val x = W / 2.0
        val top = shellRow(night, x, 6378.137 + 300.0)!!.toInt()
        val ground = horizonRow(night, x)!!.toInt()
        fun band(img: ByteArray) = (maxOf(top - 40, 0) until ground).flatMap { y -> (0 until W step 8).map { luma(img, it, y) } }.average()
        Log.i(TAG, "aurora band rows $top..$ground with ${band(with)} without ${band(without)}")
        assertTrue("aurora band ${band(with)} vs ${band(without)}", band(with) > band(without) + 3)
        val day = auroraScene(Instant.parse("2026-10-25T02:00:00Z"))
        val dayDiff = meanAbsDiff(render(day, interior = false), render(day, interior = false, debug = FrameInputs.DEBUG_NO_AURORA))
        assertTrue("aurora by day $dayDiff", dayDiff < 0.3)
    }

    /**
     * P3.4: lightning flashes in thick tropical night cloud over 6 s of animation time, a few at a time; none when
     * the system reduces motion (photosensitivity).
     */
    @Test
    fun lightningFlashesInNightStormsUnlessMotionIsReduced() {
        val scene = scene(Instant.parse("2026-10-25T22:00:00Z"), 0.0, 10.0, 0.0, -90.0)
        val dark = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_LIGHTNING)
        save("lightning_dark", dark)
        var maxDiff = 0.0
        fun flashPixels(img: ByteArray): Int {
            var count = 0
            for (y in 0 until H step 4) for (x in 0 until W step 4) {
                val d = luma(img, x, y) - luma(dark, x, y)
                if (d > maxDiff) maxDiff = d
                if (d > 40) count++
            }
            return count
        }
        var slotsWithFlash = 0
        var reducedFlashes = 0
        for (k in 0 until 30) {
            val t = 1000f + 0.2f * k + 0.05f
            val img = render(scene, interior = false, animSeconds = t)
            if (flashPixels(img) > 10) {
                if (slotsWithFlash == 0) save("lightning_flash", img)
                slotsWithFlash++
            }
            reducedFlashes += flashPixels(render(scene, interior = false, debug = FrameInputs.MOTION_REDUCED, animSeconds = t))
        }
        Log.i(TAG, "lightning slots with a flash $slotsWithFlash of 30, max diff $maxDiff, reduced motion pixels $reducedFlashes")
        assertTrue("flashes $slotsWithFlash", slotsWithFlash in 1..25)
        assertEquals(0, reducedFlashes)
    }

    /** Camera at 420 km over the point whose zenith is [dir], looking along [dir] with a [fovDeg] field. */
    private fun lookAlong(instant: Instant, dir: Vector3, fovDeg: Double): Scene {
        val state = (orbit.stateAt(instant) as OrbitState).toSceneState()
        val p = Frames.geodeticToEcef(Geodetic(asin(dir.z), atan2(dir.y, dir.x), ALT_KM))
        return SceneInputs.build(state, RestView.NADIR, 0.0, 0.0, SceneOverride(instant, p, dir, fovLongDeg = fovDeg))
    }

    private fun sunDir(instant: Instant) = (orbit.stateAt(instant) as OrbitState).toSceneState().sunDirEcef

    /** P3.5: the Sun disc is saturated; bloom (EFFECT_BLOOM) halos it, without bloom space beside it stays black. */
    @Test
    fun sunDiscIsSaturatedAndBloomHalosIt() {
        val scene = lookAlong(DAY, sunDir(DAY), 10.0)
        val plain = render(scene, interior = false, effects = 0)
        val bloom = render(scene, interior = false, effects = FrameInputs.EFFECT_BLOOM)
        save("sun_bloom", bloom)
        val radiusPx = tan(Math.toRadians(0.2666)) / tan(Math.toRadians(5.0)) * H / 2
        fun ring(img: ByteArray) = (0 until H step 3).flatMap { y -> (0 until W step 3).mapNotNull { x ->
            val r = sqrt((x - W / 2.0) * (x - W / 2.0) + (y - H / 2.0) * (y - H / 2.0))
            if (r > 2 * radiusPx && r < 4 * radiusPx) luma(img, x, y) else null
        } }.average()
        Log.i(TAG, "sun centre ${luma(plain, W / 2, H / 2)} ring plain ${ring(plain)} bloom ${ring(bloom)} radius $radiusPx")
        assertTrue("disc saturated: ${luma(plain, W / 2, H / 2)}", luma(plain, W / 2, H / 2) >= 250)
        assertTrue("no halo without bloom: ${ring(plain)}", ring(plain) < 15)
        assertTrue("bloom halo: ${ring(bloom)} vs ${ring(plain)}", ring(bloom) > ring(plain) + 30)
    }

    /** W3 eclipse: from the anti-solar point the Sun is behind the Earth: no disc and no glare. */
    @Test
    fun sunBehindTheEarthGivesNoGlare() {
        val s = sunDir(DAY)
        val state = (orbit.stateAt(DAY) as OrbitState).toSceneState()
        val anti = Frames.geodeticToEcef(Geodetic(asin(-s.z), atan2(-s.y, -s.x), ALT_KM))
        val scene = SceneInputs.build(state, RestView.NADIR, 0.0, 0.0, SceneOverride(DAY, anti, s, fovLongDeg = 60.0))
        val all = render(scene, interior = false, effects = FrameInputs.EFFECTS_ALL)
        val none = render(scene, interior = false, effects = 0)
        save("sun_eclipse", all)
        val diff = meanAbsDiff(all, none)
        Log.i(TAG, "eclipse glare diff $diff centre ${luma(all, W / 2, H / 2)}")
        assertTrue("glare behind the Earth: $diff", diff < 0.3)
        assertTrue("no disc: ${luma(all, W / 2, H / 2)}", luma(all, W / 2, H / 2) < 250)
    }

    /**
     * Window glare (EFFECT_WINDOW_GLARE): sunlit glass scatters forwards. Diagonal rest views at sunrise and sunset of
     * one day (Sun 0-14 deg above the limb, so it lights the outer glass); none at night.
     */
    @Test
    fun windowGlareHazesSunlitGlass() {
        val instants = (0L until 24 * 30L).map { DAY.plusSeconds(120 * it) }.filter {
            val st = (orbit.stateAt(it) as OrbitState).toSceneState()
            val up = SceneGeometry.unit(st.positionEcefKm)
            Math.acos(-(st.sunDirEcef dot up)) - asin(6378.137 / st.positionEcefKm.norm()) in 0.0..0.25
        }.take(6)
        val diffs = instants.map { instant ->
            val scene = restScene(instant, RestView.DIAGONAL)
            val glare = render(scene, effects = FrameInputs.EFFECT_WINDOW_GLARE)
            if (instant == instants.first()) save("window_glare", glare)
            meanLuma(glare) - meanLuma(render(scene, effects = 0))
        }
        val night = restScene(nightInstant(), RestView.DIAGONAL)
        val nightDiff = meanAbsDiff(render(night, effects = FrameInputs.EFFECT_WINDOW_GLARE), render(night, effects = 0))
        Log.i(TAG, "window glare at ${instants.size} sunrise/sunset views: $diffs, night $nightDiff")
        assertTrue("window glare $diffs", diffs.isNotEmpty() && diffs.all { it > 0.5 })
        assertTrue("window glare at night $nightDiff", nightDiff < 0.3)
    }
    /** P4.5 reflections (EFFECT_REFLECTIONS): at night the lit cabin (laptop, LEDs) shows in the dark window glass. */
    @Test
    fun reflectionsShowTheCabinInNightGlass() {
        val scene = restScene(nightInstant(), RestView.DIAGONAL)
        val on = render(scene, effects = FrameInputs.EFFECT_REFLECTIONS)
        val off = render(scene, effects = 0)
        save("reflections_night", on)
        var brighter = 0
        var n = 0
        for (y in 0 until H step 4) for (x in 0 until W step 4) {
            n++
            if (luma(on, x, y) > luma(off, x, y) + 8) brighter++
        }
        Log.i(TAG, "night reflections: mean +${meanLuma(on) - meanLuma(off)}, brighter samples $brighter of $n")
        assertTrue("reflections add light: ${meanLuma(on)} vs ${meanLuma(off)}", meanLuma(on) > meanLuma(off) + 0.2)
        assertTrue("reflected detail: $brighter", brighter > 50)
    }

    /** P4.5 dust (EFFECT_DUST): sparse bright specks on sunlit glass at sunrise; none at night. */
    @Test
    fun dustSparklesOnSunlitGlass() {
        val instant = (0L until 24 * 30L).map { DAY.plusSeconds(120 * it) }.first {
            val st = (orbit.stateAt(it) as OrbitState).toSceneState()
            val up = SceneGeometry.unit(st.positionEcefKm)
            Math.acos(-(st.sunDirEcef dot up)) - asin(6378.137 / st.positionEcefKm.norm()) in 0.0..0.25
        }
        val scene = restScene(instant, RestView.DIAGONAL)
        val on = render(scene, effects = FrameInputs.EFFECT_DUST)
        val off = render(scene, effects = 0)
        save("dust", on)
        var specks = 0
        var n = 0
        for (y in 0 until H step 2) for (x in 0 until W step 2) {
            n++
            if (luma(on, x, y) > luma(off, x, y) + 12) specks++
        }
        val night = restScene(nightInstant(), RestView.DIAGONAL)
        val nightDiff = meanAbsDiff(render(night, effects = FrameInputs.EFFECT_DUST), render(night, effects = 0))
        Log.i(TAG, "dust at $instant: speck samples $specks of $n, night $nightDiff")
        assertTrue("dust specks $specks", specks > 200)
        assertTrue("dust is sparse: $specks of $n", specks < n / 10)
        assertTrue("dust at night $nightDiff", nightDiff < 0.3)
    }

    /**
     * P6.7: the home-city marker sits on the city's projected pixel (a ring around a dot, same brightness by day and
     * night) and is absent when the city is behind the Earth.
     */
    @Test
    fun homeCityMarkerSitsOnTheCity() {
        val vienna = Frames.geodeticToEcef(Geodetic(Math.toRadians(48.209), Math.toRadians(16.372), 0.0))
        for (instant in listOf(DAY, Instant.parse("2026-10-25T21:00:00Z"))) {
            val scene = scene(instant, 47.0, 14.0, 0.0, -90.0)
            // At night the city's own lights would mask the ring geometry; the colour keeps it apart on screen.
            val debug = if (instant == DAY) 0 else FrameInputs.DEBUG_NO_CITY_LIGHTS
            val with = render(scene, interior = false, homeCity = vienna, debug = debug)
            val without = render(scene, interior = false, debug = debug)
            save("home_city_${instant.epochSecond}", with)
            val ray = SceneGeometry.unit(Vector3(vienna.x - scene.cameraEcefKm.x, vienna.y - scene.cameraEcefKm.y, vienna.z - scene.cameraEcefKm.z))
            val c = scene.ecefFromCamera
            val z = -(ray dot c.c2)
            val (fx, fy) = scene.fovDeg(W, H)
            val px = ((ray dot c.c0) / z / tan(Math.toRadians(fx / 2)) + 1) / 2 * W
            val py = (1 - (ray dot c.c1) / z / tan(Math.toRadians(fy / 2))) / 2 * H
            var cx = 0.0
            var cy = 0.0
            var w = 0.0
            for (y in 0 until H step 2) for (x in 0 until W step 2) {
                val d = luma(with, x, y) - luma(without, x, y)
                if (d > 30) { cx += x * d; cy += y * d; w += d }
            }
            Log.i(TAG, "home city at $instant: predicted ($px, $py) marker centroid (${cx / w}, ${cy / w}) weight $w")
            assertTrue("marker drawn at $instant", w > 0)
            assertTrue("marker at the city: (${cx / w}, ${cy / w}) vs ($px, $py)", abs(cx / w - px) < 4 && abs(cy / w - py) < 4)
        }
        val away = scene(DAY, -40.0, -120.0, 0.0, -90.0)
        assertTrue("no marker with the city behind the Earth", meanAbsDiff(render(away, interior = false, homeCity = vienna), render(away, interior = false)) < 0.01)
    }

    /** The 10 P3 goldens (plan P3 acceptance) as (name, scene, animation seconds), all effects on. */
    private fun goldenScenes(): List<Triple<String, Scene, Float>> {
        val (gLat, gLon) = subsolar(GLINT)
        val fullMoon = (0L until 30L).map { DAY.plusSeconds(it * 86_400L) }.maxBy {
            val st = (orbit.stateAt(it) as OrbitState).toSceneState()
            -(st.sunDirEcef dot st.moonDirEcef)
        }
        val m = (orbit.stateAt(fullMoon) as OrbitState).toSceneState().moonDirEcef
        val stormNight = Instant.parse("2026-10-25T22:00:00Z")
        return listOf(
            Triple("day_nadir", scene(DAY, 25.0, 32.0, 0.0, -90.0), 0f),
            Triple("limb", scene(GLINT, gLat, gLon, 0.0, -20.0), 0f),
            Triple("terminator", terminatorScene(subsolar(GLINT).second), 0f),
            Triple("sun_glint", scene(GLINT, gLat, gLon - 6.0, 0.0, -90.0), 0f),
            Triple("sun_in_view", lookAlong(DAY, sunDir(DAY), 60.0), 0f),
            Triple("city_lights", europeNight(), 0f),
            Triple("aurora", auroraScene(Instant.parse("2026-10-25T14:00:00Z")), 0f),
            Triple("lightning", scene(stormNight, 0.0, 10.0, 0.0, -90.0), 1000.25f),
            Triple("moonlit_clouds", scene(fullMoon, Math.toDegrees(asin(m.z)), Math.toDegrees(atan2(m.y, m.x)), 0.0, -90.0), 0f),
            Triple("stars", scene(stormNight.minusSeconds(3600), 48.0, 10.0, 0.0, 20.0), 0f),
        )
    }

    private fun psnr(a: ByteArray, b: ByteArray): Double {
        var se = 0.0
        var n = 0
        for (i in a.indices step 4) for (c in 0 until 3) {
            val d = (a[i + c].toInt() and 0xFF) - (b[i + c].toInt() and 0xFF)
            se += d * d; n++
        }
        return if (se == 0.0) Double.POSITIVE_INFINITY else 10 * kotlin.math.log10(255.0 * 255.0 / (se / n))
    }

    /**
     * R7 goldens: each scene renders at half resolution (540 x 1170) and matches its committed golden
     * (androidTest assets goldens/<name>.png, rendered on this device model) at PSNR >= 40 dB. The candidates are
     * saved to files/goldens; a missing golden fails with its path, for the owner's one-time approval.
     */
    @Test
    fun goldensMatchAtFortyDecibels() {
        val gw = 540
        val gh = 1170
        val dir = File(context.getExternalFilesDir(null), "goldens").apply { mkdirs() }
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val failures = mutableListOf<String>()
        for ((name, scene, anim) in goldenScenes()) {
            val inputs = FrameInputs.empty()
            scene.write(inputs)
            inputs[FrameInputs.TIME_SECONDS] = anim
            inputs[FrameInputs.EFFECTS] = (inputs[FrameInputs.EFFECTS].toInt() or FrameInputs.EFFECTS_ALL).toFloat()
            val img = renderer.renderOffscreen(gw, gh, inputs)
            val bitmap = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(img))
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val golden = runCatching { testAssets.open("goldens/$name.png").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            if (golden == null) {
                failures += "$name: no golden (candidate ${File(dir, "$name.png")})"
                continue
            }
            val ref = ByteArray(gw * gh * 4).also { golden.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
            val db = psnr(img, ref)
            Log.i(TAG, "golden $name PSNR $db dB")
            if (db < 40.0) failures += "$name: PSNR $db dB"
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    /**
     * R7 seam sweep: at every day-detail tile boundary (meridians -180, -90, 0, 90 and the equator) the luma jump
     * across the boundary line through the image centre is no larger than 2x the median jump across parallel lines
     * 20-80 px away (bare ground: no atmosphere or clouds, Sun high).
     */
    @Test
    fun noSeamAtTileBoundariesOrTheDateLine() {
        val (sLat, sLon) = subsolar(DAY)
        fun jump(img: ByteArray, x0: Int, vertical: Boolean): Double = if (vertical) {
            (H / 4 until 3 * H / 4 step 2).map { y -> abs(luma(img, x0 + 1, y) - luma(img, x0 - 1, y)) }.average()
        } else {
            (W / 4 until 3 * W / 4 step 2).map { x -> abs(luma(img, x, x0 + 1) - luma(img, x, x0 - 1)) }.average()
        }
        val cases = listOf(-180.0, -90.0, 0.0, 90.0).map { lon -> Triple("lon $lon", 0.0 + 20.0, lon) } + Triple("equator", 0.0, sLon)
        val failures = mutableListOf<String>()
        for ((name, lat, lon) in cases) {
            // Instant with the Sun high over the boundary: shift DAY by the longitude difference to the subsolar point.
            val instant = DAY.plusSeconds(((sLon - lon) / 360.0 * 86_400).toLong())
            val scene = scene(instant, lat, lon, 0.0, -90.0)
            val img = render(scene, interior = false, debug = FrameInputs.DEBUG_NO_ATMOSPHERE or FrameInputs.DEBUG_NO_CLOUDS)
            save("seam_" + name.replace(" ", "_"), img)
            val vertical = name != "equator"
            // The boundary through the image centre: the meridian runs north-south, the equator east-west, at nadir
            // over the line; find the exact pixel line from the projection of a point on it.
            val p = Frames.geodeticToEcef(Geodetic(Math.toRadians(if (vertical) lat + 1.0 else 0.0), Math.toRadians(if (vertical) lon else lon + 1.0), 0.0))
            val ray = SceneGeometry.unit(Vector3(p.x - scene.cameraEcefKm.x, p.y - scene.cameraEcefKm.y, p.z - scene.cameraEcefKm.z))
            val c = scene.ecefFromCamera
            val z = -(ray dot c.c2)
            val (fx, fy) = scene.fovDeg(W, H)
            val line = if (vertical) (((ray dot c.c0) / z / tan(Math.toRadians(fx / 2)) + 1) / 2 * W).toInt()
                       else ((1 - (ray dot c.c1) / z / tan(Math.toRadians(fy / 2))) / 2 * H).toInt()
            val at = jump(img, line, vertical)
            val near = (20..80 step 4).flatMap { listOf(line - it, line + it) }.map { jump(img, it, vertical) }.sorted()
            val median = near[near.size / 2]
            Log.i(TAG, "seam $name (sun $sLat $sLon) line $line jump $at median nearby $median")
            if (at > 2 * median + 0.5) failures += "$name: jump $at vs median $median"
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    /** Geometry of the ground limb; the atmosphere (which glows above it) is off. */
    @Test
    fun limbWithinTwoPixelsOfTheAnalyticHorizon() {
        val (lat, lon) = subsolar(GLINT)
        val scene = scene(GLINT, lat, lon, 0.0, -20.0)
        val img = render(scene, debug = FrameInputs.DEBUG_NO_ATMOSPHERE)
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

    /**
     * P3.3 limb by day: the atmosphere glows bright and blue 3 px above the analytic ground horizon in every sampled
     * column, and ends at its top: black from 3 px above the row where rays graze the 100 km shell (the shader's
     * sphere in the ellipsoid-scaled space) upwards.
     */
    @Test
    fun atmosphereGlowsAboveTheLimbAndEndsAtItsTop() {
        val (lat, lon) = subsolar(GLINT)
        val scene = scene(GLINT, lat, lon, 0.0, -20.0)
        val img = render(scene)
        save("limb_atmosphere", img)
        var aboveTop = 0.0
        var dimmest = Double.MAX_VALUE
        var blueOverRed = 0
        var columns = 0
        for (x in 0 until W step 54) {
            val ground = horizonRow(scene, x + 0.5)!!
            val top = shellRow(scene, x + 0.5, TOP_KM)!!
            for (y in 0..(top - 3).toInt()) aboveTop = maxOf(aboveTop, luma(img, x, y))
            val y = (ground - 3).toInt()
            dimmest = minOf(dimmest, luma(img, x, y))
            val i = at(x, y)
            if (ch(img, i + 2) > ch(img, i)) blueOverRed++
            columns++
            if (x == 540) {
                val profile = (0 until 16).map { luma(img, x, (ground - 1 - it * 4).toInt()).toInt() }
                Log.i(TAG, "limb column $x ground row $ground top row $top glow every 4 px up: $profile")
            }
        }
        Log.i(TAG, "limb atmosphere: max luma above the top $aboveTop, dimmest glow $dimmest, blue over red $blueOverRed of $columns")
        assertTrue("light above the atmosphere top: $aboveTop", aboveTop < 1.0)
        assertTrue("limb glow too dim: $dimmest", dimmest > 20.0)
        assertTrue("limb glow not blue in ${columns - blueOverRed} columns", blueOverRed == columns)
    }

    /**
     * P3.3 aerial perspective: an oblique day view over Egypt towards the horizon. The atmosphere adds blue haze to the
     * ground, most along the long paths just below the horizon, less in the frame's bottom rows (short paths).
     */
    @Test
    fun aerialPerspectiveHazesTheGroundMostNearTheHorizon() {
        val scene = scene(DAY, 25.0, 32.0, 0.0, -20.0)
        val on = render(scene)
        val off = render(scene, debug = FrameInputs.DEBUG_NO_ATMOSPHERE)
        save("haze_oblique", on)
        save("haze_oblique_off", off)
        var near = 0.0
        var nearN = 0
        var far = 0.0
        var farN = 0
        for (x in 0 until W step 8) {
            val h = horizonRow(scene, x + 0.5)!!.toInt()
            for (y in h + 5 until h + 60 step 2) {
                near += ch(on, at(x, y) + 2) - ch(off, at(x, y) + 2); nearN++
            }
            for (y in H - 200 until H step 8) {
                far += ch(on, at(x, y) + 2) - ch(off, at(x, y) + 2); farN++
            }
        }
        Log.i(TAG, "haze blue gain near the horizon ${near / nearN} bottom rows ${far / farN}")
        assertTrue("no haze near the horizon: ${near / nearN}", near / nearN > 15.0)
        assertTrue("haze near ${near / nearN} not above bottom ${far / farN}", near / nearN > far / farN + 5.0)
    }

    /**
     * P3.3 terminator colours: in the terminator scene the reddened low sunlight warms the ground of the lit band
     * beside the terminator (red/blue ratio of the render without airlight at least 1.2 x the atmosphere-free one; over
     * this dark ocean the blue airlight outshines that ground light), and twilight lights the band beyond it.
     */
    @Test
    fun terminatorIsWarmAndTwilightLit() {
        val (_, lon) = subsolar(GLINT)
        val scene = terminatorScene(lon)
        val on = render(scene)
        val ground = render(scene, debug = FrameInputs.DEBUG_NO_INSCATTER)
        val off = render(scene, debug = FrameInputs.DEBUG_NO_ATMOSPHERE)
        save("terminator_atmosphere", on)
        save("terminator_ground_light", ground)
        fun ratio(img: ByteArray): Double {
            var r = 0.0
            var b = 0.0
            for (y in H / 2 - 300 until H / 2 - 100 step 4) for (x in 0 until W step 4) {
                r += ch(img, at(x, y)); b += ch(img, at(x, y) + 2)
            }
            return r / maxOf(b, 1.0)
        }
        fun night(img: ByteArray) =
            (H / 2 + 100 until H / 2 + 300 step 4).flatMap { y -> (0 until W step 4).map { x -> luma(img, x, y) } }.average()
        Log.i(
            TAG,
            "terminator r/b with atmosphere ${ratio(on)} ground light ${ratio(ground)} without ${ratio(off)}, " +
                "night band ${night(on)} vs ${night(off)}",
        )
        assertTrue("terminator ground not warm: ${ratio(ground)} vs ${ratio(off)}", ratio(ground) >= 1.2 * ratio(off))
        assertTrue("no twilight: ${night(on)} vs ${night(off)}", night(on) > night(off) + 0.5)
    }

    /**
     * Decision 11 steady exposure: the analytic luminance estimate lies within a factor 3 of the mean linear radiance
     * of the Earth pixels actually rendered (atmosphere on), by day at nadir and at the terminator, where the sunlit
     * air outshines the ground. The frame is rendered 2 EV under the steady exposure and decoded (sRGB, ACES fit).
     */
    @Test
    fun steadyExposureMatchesTheRenderedEarth() {
        val (_, lon) = subsolar(GLINT)
        for ((name, scene) in listOf("day nadir" to scene(DAY, 25.0, 32.0, 0.0, -90.0), "terminator" to terminatorScene(lon))) {
            val exposure = Exposure.steady(scene) / 4
            val img = render(scene.copy(exposure = exposure))
            var sum = 0.0
            var n = 0
            for (y in 0 until H step 8) for (x in 0 until W step 8) {
                if (SceneGeometry.intersectEllipsoid(scene.cameraEcefKm, scene.rayEcef(x + 0.5, y + 0.5, W, H)) == null) continue
                sum += linearLuma(img, x, y); n++
            }
            val rendered = sum / n / exposure
            val estimate = Exposure.luminance(scene)
            Log.i(TAG, "exposure $name: estimate $estimate rendered $rendered (ratio ${rendered / estimate}, steady ${Exposure.steady(scene)})")
            assertTrue("$name: estimate $estimate vs rendered $rendered", rendered / estimate in (1.0 / 3)..3.0)
        }
    }

    @Test
    fun glintCentreWithinOneDegreeOfTheSpecularPoint() {
        val (lat, lon) = subsolar(GLINT)
        // ISS 6 degrees west of the subsolar point over the open Pacific: the specular point sits between.
        val scene = scene(GLINT, lat, lon - 6.0, 0.0, -90.0)
        val img = render(scene, debug = FrameInputs.DEBUG_NO_CLOUDS)
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
        // Ground terminator: cloud tops (higher) stay lit a little longer.
        val img = render(terminatorScene(lon), debug = FrameInputs.DEBUG_NO_ATMOSPHERE or FrameInputs.DEBUG_NO_CLOUDS)
        save("terminator", img)
        // Day above the centre row, night below: compare 200-row bands either side of a 200-row margin.
        fun band(y0: Int) = (y0 until y0 + 200 step 4).flatMap { y -> (0 until W step 4).map { x -> luma(img, x, y) } }.average()
        val day = band(H / 2 - 300)
        val night = band(H / 2 + 100)
        Log.i(TAG, "terminator day band $day night band $night (no atmosphere)")
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
        val img = render(scene(DAY, 0.0, -20.0, 0.0, -90.0), debug = FrameInputs.DEBUG_NO_CLOUDS)
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

    /**
     * Equator east of the subsolar longitude [subsolarLon], looking west 50 deg down, at the camera longitude that puts
     * the analytic terminator (Sun on the horizon) at the frame centre.
     */
    private fun terminatorScene(subsolarLon: Double): Scene {
        val sceneAt = { off: Double -> scene(GLINT, 0.0, subsolarLon + off, 270.0, -50.0) }
        val centreSun = { sc: Scene ->
            val d = sc.rayEcef(W / 2.0, H / 2.0, W, H)
            val hit = sc.cameraEcefKm + d * SceneGeometry.intersectEllipsoid(sc.cameraEcefKm, d)!!
            abs(SceneGeometry.enu(hit).up dot sc.sunDirEcef)
        }
        return sceneAt((0..300).map { 90.0 + it * 0.05 }.minBy { centreSun(sceneAt(it)) })
    }

    /**
     * Continuous y of the first row from the top whose ray meets the atmosphere shell of radius [radiusKm] (a sphere
     * in the ellipsoid-scaled space, z x a / b, as in the shader).
     */
    private fun shellRow(scene: Scene, x: Double, radiusKm: Double): Double? {
        val k = SceneGeometry.A_KM / SceneGeometry.B_KM
        val hits = { y: Double ->
            val d = scene.rayEcef(x, y, W, H)
            val o = scene.cameraEcefKm
            val q = Vector3(o.x, o.y, o.z * k)
            val e = Vector3(d.x, d.y, d.z * k)
            val b = q dot e
            b < 0 && b * b - (e dot e) * ((q dot q) - radiusKm * radiusKm) >= 0
        }
        if (hits(0.0) || !hits(H.toDouble())) return null
        var lo = 0.0
        var hi = H.toDouble()
        repeat(40) {
            val mid = (lo + hi) / 2
            if (hits(mid)) hi = mid else lo = mid
        }
        return hi
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

    /** Exposed linear luma before the tone map: sRGB decode, then the inverse of earth.frag's ACES fit (254 at most). */
    private fun linearLuma(img: ByteArray, x: Int, y: Int): Double {
        fun linear(i: Int): Double {
            val c = minOf(ch(img, i), 254) / 255.0
            val t = if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            val a = 2.43 * t - 2.51
            val b = 0.59 * t - 0.03
            return (-b - sqrt(b * b - 4 * a * 0.14 * t)) / (2 * a)
        }
        val i = at(x, y)
        return 0.2126 * linear(i) + 0.7152 * linear(i + 1) + 0.0722 * linear(i + 2)
    }

    private fun meanLuma(img: ByteArray): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until H step 4) for (x in 0 until W step 4) {
            sum += luma(img, x, y); n++
        }
        return sum / n
    }

    private fun meanAbsDiff(a: ByteArray, b: ByteArray): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until H step 4) for (x in 0 until W step 4) {
            sum += abs(luma(a, x, y) - luma(b, x, y)); n++
        }
        return sum / n
    }

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
        private const val TOP_KM = 6478.137 // atmosphere_params.h ATMO_TOP_KM
        private const val GPU_SAMPLES = 30
        private const val GPU_WARMUP = 10
        private const val SURFACE_ID = 9_001 // apart from the wallpaper sessions (RendererHost ids from 1)
        private const val FRAMES_PER_STEP = 4 // every frame in flight (2) records with the new scale at least once
        private val NIGHT_BAND = 18.0..64.0
        private const val DAY_INTERIOR_MEDIAN_MIN = 27.0
private const val STAR_PEAK_TOLERANCE = 6.0

        // Inside the bundled TLE's validity (epoch 2026-10-18). Egypt near local noon; subsolar point over the Pacific.
        private val DAY = Instant.parse("2026-10-25T09:50:00Z")
        private val GLINT = Instant.parse("2026-10-25T20:25:00Z")

        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var host: RendererHost
        private lateinit var renderer: NativeRenderer
        private lateinit var device: AutoCloseable
        private lateinit var orbit: OrbitModel

        @BeforeClass
        @JvmStatic
        fun setUp() {
            val lines = context.assets.open("orbit/iss.tle").bufferedReader().use { it.readLines() }.filter { it.isNotBlank() }
            orbit = OrbitModel(Tle.parse(lines[lines.size - 2], lines[lines.size - 1]))
            // The live wallpaper may already run in this process; a second renderer would rebind the render thread.
            host = EntryPointAccessors.fromApplication(context, RendererHostEntryPoint::class.java).rendererHost()
            renderer = host.acquire()
            device = renderer.holdDevice()
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            device.close()
            host.release()
        }
    }
}
