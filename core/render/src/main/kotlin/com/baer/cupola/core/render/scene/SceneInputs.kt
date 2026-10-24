package com.baer.cupola.core.render.scene

import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.astro.Moon
import com.baer.cupola.orbit.astro.Sun
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.cross
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.frames.plus
import com.baer.cupola.orbit.frames.rotatedFrameZ
import com.baer.cupola.orbit.frames.times
import com.baer.cupola.orbit.frames.unaryMinus
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.Sidereal
import com.baer.cupola.orbit.time.TimeScales
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Rotation matrix stored as columns; for a camera rotation the columns are right, up and back (forward = -c2). */
data class Mat3(val c0: Vector3, val c1: Vector3, val c2: Vector3) {
    operator fun times(v: Vector3): Vector3 = c0 * v.x + c1 * v.y + c2 * v.z

    operator fun times(m: Mat3) = Mat3(this * m.c0, this * m.c1, this * m.c2)

    companion object {
        val IDENTITY = Mat3(Vector3(1.0, 0.0, 0.0), Vector3(0.0, 1.0, 0.0), Vector3(0.0, 0.0, 1.0))

        /** From the row-major nested lists of `visible_region.json`. */
        fun rows(r0: DoubleArray, r1: DoubleArray, r2: DoubleArray) =
            Mat3(Vector3(r0[0], r1[0], r2[0]), Vector3(r0[1], r1[1], r2[1]), Vector3(r0[2], r1[2], r2[2]))
    }
}

/**
 * The two rest views of the settings: world-from-camera rotations in the Blender interior frame
 * (`local/blender/visible_region.json` key `rest_views`). Blender camera convention: looks along -Z, up +Y.
 */
enum class RestView(val blenderFromCamera: Mat3) {
    /** Cam_Rest: the diagonal view out of a side window and the nadir window. */
    DIAGONAL(
        Mat3.rows(
            doubleArrayOf(-0.6952452063560486, -0.5, -0.5163663029670715),
            doubleArrayOf(-0.40140005946159363, 0.8660253882408142, -0.298124223947525),
            doubleArrayOf(0.5962485074996948, -4.775557727043633e-10, -0.8027999997138977),
        ),
    ),

    /** NADIR_REST: straight down through the nadir window, image up toward azimuth 30 deg. */
    NADIR(
        Mat3.rows(
            doubleArrayOf(-0.49999999999999994, 0.8660254037844387, 0.0),
            doubleArrayOf(0.8660254037844387, 0.49999999999999994, 0.0),
            doubleArrayOf(0.0, 0.0, -1.0),
        ),
    ),
}

/** ISS state for one frame, ECEF axes. Velocity is the inertial (TEME) velocity rotated into ECEF axes. */
data class SceneState(
    val instant: Instant,
    val positionEcefKm: Vector3,
    val velocityEcefKmPerSec: Vector3,
    val sunDirEcef: Vector3,
    val moonDirEcef: Vector3,
)

/** TEME vectors of [OrbitState] rotated into ECEF axes by GMST; the velocity stays inertial (LVLH convention). */
fun OrbitState.toSceneState() = SceneState(
    instant = instant,
    positionEcefKm = positionEcefKm,
    velocityEcefKmPerSec = velocityTemeKmPerSec.rotatedFrameZ(gmstRad),
    sunDirEcef = sunDirectionTeme.rotatedFrameZ(gmstRad),
    moonDirEcef = moonDirectionTeme.rotatedFrameZ(gmstRad),
)

/**
 * Debug override for P3a and G-PR. [instant] is applied by the caller (it propagates the orbit at that time);
 * the others replace the ISS position, the camera direction (look-around is then ignored) and the field of view.
 */
data class SceneOverride(
    val instant: Instant? = null,
    val positionEcefKm: Vector3? = null,
    val cameraForwardEcef: Vector3? = null,
    val fovLongDeg: Double? = null,
)

/** Monthly colour layers around a date: colour = mix(layer0, layer1, weight). */
data class MonthBlend(val layer0: Int, val layer1: Int, val weight: Double)

data class Enu(val east: Vector3, val north: Vector3, val up: Vector3)

/** Geometry shared by [SceneInputs] and the analytic checks; the Earth shader mirrors [intersectEllipsoid]. */
object SceneGeometry {
    const val A_KM = Frames.WGS84_A_KM
    val B_KM = Frames.WGS84_A_KM * (1 - Frames.WGS84_F)

    /**
     * Azimuth of the Blender interior frame (one constant): +Z = LVLH +Z (nadir); the ISS velocity (LVLH +X) lies at
     * azimuth +30 deg about +Z (P4.6: the real window facets sit at 30 + 60k deg from the velocity, build_cupola.py
     * places them at 60k deg), so LVLH = Rz(-30 deg) x Blender. See tools/blender/README.md.
     */
    val LVLH_FROM_BLENDER = Mat3(
        Vector3(cos(Math.toRadians(30.0)), -sin(Math.toRadians(30.0)), 0.0),
        Vector3(sin(Math.toRadians(30.0)), cos(Math.toRadians(30.0)), 0.0),
        Vector3(0.0, 0.0, 1.0),
    )

    fun unit(v: Vector3) = v * (1.0 / v.norm())

    /** LVLH axes in ECEF as columns: z = geocentric nadir, y = z x v (minus the orbit normal), x = y x z. */
    fun lvlh(positionEcefKm: Vector3, velocityEcef: Vector3): Mat3 {
        val z = unit(-positionEcefKm)
        val y = unit(z cross velocityEcef)
        return Mat3(y cross z, y, z)
    }

    /** Local east, north and geodetic up at a point (ECEF). */
    fun enu(p: Vector3): Enu {
        val up = unit(Vector3(p.x / (A_KM * A_KM), p.y / (A_KM * A_KM), p.z / (B_KM * B_KM)))
        val east = unit(Vector3(-p.y, p.x, 0.0))
        return Enu(east, up cross east, up)
    }

    /** Unit direction at [p] from azimuth (from north, eastwards) and elevation above the local horizon. */
    fun directionFromAzEl(p: Vector3, azimuthDeg: Double, elevationDeg: Double): Vector3 {
        val e = enu(p)
        val az = Math.toRadians(azimuthDeg)
        val el = Math.toRadians(elevationDeg)
        return e.east * (cos(el) * sin(az)) + e.north * (cos(el) * cos(az)) + e.up * sin(el)
    }

    /** Distance along the unit ray [dir] from [origin] to the WGS-84 ellipsoid, or null on a miss. */
    fun intersectEllipsoid(origin: Vector3, dir: Vector3): Double? {
        val q = Vector3(origin.x / A_KM, origin.y / A_KM, origin.z / B_KM)
        val d = Vector3(dir.x / A_KM, dir.y / A_KM, dir.z / B_KM)
        val a = d dot d
        val b = q dot d
        val c = (q dot q) - 1
        val disc = b * b - a * c
        if (disc < 0) return null
        val t = (-b - sqrt(disc)) / a
        return if (t > 0) t else null
    }

    /** |q|^2 - 1 for the ellipsoid-scaled camera position, in double (the shader's float would cancel). */
    fun ellipsoidC(p: Vector3): Double {
        val x = p.x / A_KM
        val y = p.y / A_KM
        val z = p.z / B_KM
        return x * x + y * y + z * z - 1
    }
}

/** Atmosphere shell of the renderer; must match `atmosphere_params.h` (radii in the ellipsoid-scaled space). */
object Atmosphere {
    const val BOTTOM_KM = 6378.137
    const val TOP_KM = 6478.137
}

/** Everything the Earth shader needs for one frame; [write] fills the [FrameInputs] scene slots. */
data class Scene(
    val instant: Instant,
    val cameraEcefKm: Vector3,
    val ecefFromCamera: Mat3,
    val fovLongDeg: Double,
    val sunDirEcef: Vector3,
    val moonDirEcef: Vector3,
    val month: MonthBlend,
    /** Blender interior frame from camera; null (debug camera override) renders the Earth without the interior. */
    val blenderFromCamera: Mat3? = null,
    /** Linear exposure; null uses the steady [Exposure.steady] value (offscreen renders and goldens). */
    val exposure: Double? = null,
    /** [FrameInputs.EFFECTS] bits (user effect switches, plus debug bits in tests). */
    val effects: Int = FrameInputs.EFFECTS_ALL,
) {
    private val tanHalfLong = tan(Math.toRadians(fovLongDeg / 2))

    /** (horizontal, vertical) field of view in degrees for a logical width x height; the long axis gets [fovLongDeg]. */
    fun fovDeg(width: Int, height: Int): Pair<Double, Double> {
        val (tx, ty) = tanHalf(width, height)
        return Math.toDegrees(2 * atan(tx)) to Math.toDegrees(2 * atan(ty))
    }

    private fun tanHalf(width: Int, height: Int): Pair<Double, Double> {
        val long = maxOf(width, height).toDouble()
        return tanHalfLong * width / long to tanHalfLong * height / long
    }

    /** Unit ECEF ray through logical pixel coordinate ([px], [py]) (top-left origin, pixel centres at +0.5). */
    fun rayEcef(px: Double, py: Double, width: Int, height: Int): Vector3 {
        val (tx, ty) = tanHalf(width, height)
        val x = (2 * px / width - 1) * tx
        val y = (1 - 2 * py / height) * ty
        return SceneGeometry.unit(ecefFromCamera.c0 * x + ecefFromCamera.c1 * y - ecefFromCamera.c2)
    }

    fun write(inputs: FloatArray) {
        inputs[FrameInputs.SCENE] = 1f
        put(inputs, FrameInputs.CAMERA_POS_KM, cameraEcefKm)
        inputs[FrameInputs.CAMERA_ELLIPSOID_C] = SceneGeometry.ellipsoidC(cameraEcefKm).toFloat()
        put(inputs, FrameInputs.CAMERA_RIGHT, ecefFromCamera.c0)
        inputs[FrameInputs.MONTH_WEIGHT] = month.weight.toFloat()
        put(inputs, FrameInputs.CAMERA_UP, ecefFromCamera.c1)
        inputs[FrameInputs.MONTH_LAYER0] = month.layer0.toFloat()
        put(inputs, FrameInputs.CAMERA_BACK, ecefFromCamera.c2)
        inputs[FrameInputs.MONTH_LAYER1] = month.layer1.toFloat()
        put(inputs, FrameInputs.SUN_DIR, sunDirEcef)
        inputs[FrameInputs.TAN_HALF_FOV_LONG] = tanHalfLong.toFloat()
        put(inputs, FrameInputs.MOON_DIR, moonDirEcef)
        val interior = blenderFromCamera
        inputs[FrameInputs.INTERIOR] = if (interior != null) 1f else 0f
        if (interior != null) {
            put(inputs, FrameInputs.INTERIOR_RIGHT, interior.c0)
            put(inputs, FrameInputs.INTERIOR_UP, interior.c1)
            put(inputs, FrameInputs.INTERIOR_BACK, interior.c2)
        }
        inputs[FrameInputs.EARTH_LIGHT] = earthLight().toFloat()
        inputs[FrameInputs.MOON_LIGHT] = moonLight().toFloat()
        inputs[FrameInputs.EXPOSURE] = (exposure ?: Exposure.steady(this)).toFloat()
        inputs[FrameInputs.EFFECTS] = effects.toFloat()
        writeSkyFrame(inputs)
        val clouds = CloudFlow.of(instant)
        inputs[FrameInputs.CLOUD_FLOW] = clouds.phaseA
        inputs[FrameInputs.CLOUD_FLOW + 1] = clouds.phaseB
        inputs[FrameInputs.CLOUD_FLOW + 2] = clouds.days
        val stars = StarFrame.galacticFromEcef(instant)
        put(inputs, FrameInputs.STAR_ROW_X, Vector3(stars.c0.x, stars.c1.x, stars.c2.x))
        put(inputs, FrameInputs.STAR_ROW_Y, Vector3(stars.c0.y, stars.c1.y, stars.c2.y))
        put(inputs, FrameInputs.STAR_ROW_Z, Vector3(stars.c0.z, stars.c1.z, stars.c2.z))
        put(inputs, FrameInputs.SUN_LIGHT, sunLight())
        inputs[FrameInputs.SUN_LIGHT + 3] = glassFacing().toFloat()
    }

    /** [FrameInputs.SKY_UP] .. [FrameInputs.SKY_TOP_NADIR], in double. */
    private fun writeSkyFrame(inputs: FloatArray) {
        val c = cameraEcefKm
        val scaled = Vector3(c.x, c.y, c.z * SceneGeometry.A_KM / SceneGeometry.B_KM)
        val r = scaled.norm()
        val up = SceneGeometry.unit(scaled)
        val across = sunDirEcef - up * (sunDirEcef dot up)
        // Sun at the zenith or nadir: every azimuth is equivalent, any perpendicular axis does.
        val axis = if (across.norm() > 1e-9) {
            SceneGeometry.unit(across)
        } else {
            SceneGeometry.unit(up cross if (abs(up.x) < 0.9) Vector3(1.0, 0.0, 0.0) else Vector3(0.0, 1.0, 0.0))
        }
        put(inputs, FrameInputs.SKY_UP, up)
        inputs[FrameInputs.SKY_HORIZON_NADIR] = asin((Atmosphere.BOTTOM_KM / r).coerceAtMost(1.0)).toFloat()
        put(inputs, FrameInputs.SKY_SUN_AXIS, axis)
        inputs[FrameInputs.SKY_TOP_NADIR] = asin((Atmosphere.TOP_KM / r).coerceAtMost(1.0)).toFloat()
    }

    /** Mean radiance of the visible ground (shader units, albedo [EARTH_ALBEDO], no atmosphere). */
    fun earthLight(): Double = EARTH_ALBEDO * capMean { maxOf(0.0, it) }

    /**
     * Sunlight reaching the camera per unit sunlight, per shader colour channel: 0 when the Earth hides the Sun, the
     * Chapman grazing-ray transmittance (Rayleigh and Mie of atmosphere_params.h, spherical Earth) when its ray
     * crosses the air.
     */
    fun sunLight(): Vector3 {
        val r = cameraEcefKm.norm()
        val mu = sunDirEcef dot SceneGeometry.unit(cameraEcefKm)
        if (mu >= 0.0) return Vector3(1.0, 1.0, 1.0)
        val h = r * sqrt(1 - mu * mu) - Atmosphere.BOTTOM_KM
        if (h <= 0.0) return Vector3(0.0, 0.0, 0.0)
        if (h >= Atmosphere.TOP_KM - Atmosphere.BOTTOM_KM) return Vector3(1.0, 1.0, 1.0)
        fun column(scaleKm: Double) = sqrt(2 * Math.PI * Atmosphere.BOTTOM_KM * scaleKm) * exp(-h / scaleKm)
        val rayleigh = column(8.0)
        val mie = 4.44e-3 * column(1.2)
        return Vector3(exp(-(5.802e-3 * rayleigh + mie)), exp(-(13.558e-3 * rayleigh + mie)), exp(-(33.1e-3 * rayleigh + mie)))
    }

    /** How far the Sun stands on the cupola's outer side (cabin +Z), 0..1; 0 without the cabin. */
    fun glassFacing(): Double {
        val cabin = blenderFromCamera ?: return 0.0
        val c = ecefFromCamera
        val z = (cabin * Vector3(sunDirEcef dot c.c0, sunDirEcef dot c.c1, sunDirEcef dot c.c2)).z
        val t = ((z + 0.3) / 0.4).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * Mean radiance the Sun's bloom adds to a view with the Sun inside the frame (earth.frag bloom, EFFECT_BLOOM),
     * judged on the long axis's field. Cabin structure is unknown here, so a Sun behind a window frame counts too.
     */
    fun sunGlare(): Double {
        if (effects and FrameInputs.EFFECT_BLOOM == 0) return 0.0
        val t = sunLight()
        val luma = 0.2126 * t.x + 0.7152 * t.y + 0.0722 * t.z
        val c = ecefFromCamera
        val z = -(sunDirEcef dot c.c2)
        if (luma <= 0.0 || z <= 0.0) return 0.0
        val inFrame = abs((sunDirEcef dot c.c0) / z) <= tanHalfLong && abs((sunDirEcef dot c.c1) / z) <= tanHalfLong
        return if (inFrame) BLOOM_FRAME_MEAN * luma else 0.0
    }

    /** Mean radiance the sunlit window glass adds to the view (earth.frag sunOnGlass); 0 with window glare off. */
    fun glassGlare(): Double {
        if (effects and FrameInputs.EFFECT_WINDOW_GLARE == 0) return 0.0
        val t = sunLight()
        return GLASS_GLARE_MEAN * (0.2126 * t.x + 0.7152 * t.y + 0.0722 * t.z) * glassFacing()
    }

    /** Mean radiance (luma) of the sunlit air in front of the visible ground: [airlight] averaged like [earthLight]. */
    fun airLight(): Double = capMean(::airlight)

    /**
     * Mean of [f] (of the Sun's cosine at the ground) over the solid angle of the visible cap as seen from the camera
     * (spherical Earth of radius a). Rings are uniform in ground angle (smooth as the terminator leaves the cap),
     * weighted by sin(theta) dtheta/dgamma. Runs three times per frame: inline (no boxed f) and scalar in the loop, so
     * the 512 samples allocate nothing (ART has no escape analysis to remove Vector3 temporaries).
     */
    private inline fun capMean(f: (Double) -> Double): Double {
        val a = SceneGeometry.A_KM
        val r = cameraEcefKm.norm()
        val up = SceneGeometry.unit(cameraEcefKm)
        val e1 = SceneGeometry.unit(if (abs(up.z) < 0.9) up cross Vector3(0.0, 0.0, 1.0) else up cross Vector3(1.0, 0.0, 0.0))
        val e2 = up cross e1
        val upSun = up dot sunDirEcef
        val e1Sun = e1 dot sunDirEcef
        val e2Sun = e2 dot sunDirEcef
        val capAngle = acos(a / r)
        var sum = 0.0
        var weight = 0.0
        for (i in 0 until CAP_RINGS) {
            val g = capAngle * (i + 0.5) / CAP_RINGS
            val theta = atan2(a * sin(g), r - a * cos(g))
            val w = sin(theta) * a * (r * cos(g) - a) / (r * r - 2 * r * a * cos(g) + a * a)
            for (j in 0 until CAP_SECTORS) {
                val phi = 2 * Math.PI * (j + 0.5) / CAP_SECTORS
                // ground . sun, ground = up cos(g) + (e1 cos(phi) + e2 sin(phi)) sin(g)
                sum += w * f(upSun * cos(g) + (e1Sun * cos(phi) + e2Sun * sin(phi)) * sin(g))
            }
            weight += w * CAP_SECTORS
        }
        return sum / weight
    }

    /**
     * Lunar irradiance relative to the Sun's: full Moon [MOON_FULL] times the visual phase law
     * 10^(-0.4 (0.026 i + 4e-9 i^4)), i = phase angle in degrees (Sun at infinity, so cos i = -sun . moon), times
     * [MOON_GAIN].
     */
    fun moonLight(): Double {
        val i = Math.toDegrees(acos((-(sunDirEcef dot moonDirEcef)).coerceIn(-1.0, 1.0)))
        return MOON_FULL * MOON_GAIN * 10.0.pow(-0.4 * (0.026 * i + 4e-9 * i * i * i * i))
    }

    companion object {
        const val EARTH_ALBEDO = 0.3
        const val MOON_FULL = 2.5e-6

        /**
         * Moonlit ground and clouds as a long exposure shows them: true full-Moon light on the ground is 1/30 of the night floor
         * ([Exposure.NIGHT_FLOOR]), which would leave a full-Moon night as dark as a moonless one.
         */
        const val MOON_GAIN = 20.0

        /** earth.frag bloom lobes averaged over a view with the Sun inside it. */
        const val BLOOM_FRAME_MEAN = 0.03

        /** earth.frag WINDOW_GLARE averaged over the forward lobe and the glass share of a view. */
        const val GLASS_GLARE_MEAN = 0.002
        private const val CAP_RINGS = 16
        private const val CAP_SECTORS = 32

        /** Multiple over single scattering in airlight luma (A35 render vs tools/atmosphere/airlight.py: 1.24-1.43 at -1..-3 deg). */
        const val AIRLIGHT_MULTI_SCATTER = 1.3

        /**
         * Airlight luma (shader units) seen from 420 km over ground where the Sun's cosine is [muS]: a fit (within 11 %
         * from -8 to 90 deg elevation) to the cap-mean single scattering of tools/atmosphere/airlight.py, times
         * [AIRLIGHT_MULTI_SCATTER]. Twilight: a Gaussian falloff in elevation below the horizon.
         */
        fun airlight(muS: Double): Double {
            val e = Math.toDegrees(asin(muS.coerceIn(-1.0, 1.0)))
            val day = AIRLIGHT_MULTI_SCATTER * 0.0418
            return if (e >= 0) {
                day * (1 - exp(-(e + 2.34) / 8.83))
            } else {
                day * (1 - exp(-2.34 / 8.83)) * exp(0.495 * e - 0.0413 * e * e)
            }
        }
    }

    private fun put(inputs: FloatArray, at: Int, v: Vector3) {
        inputs[at] = v.x.toFloat()
        inputs[at + 1] = v.y.toFloat()
        inputs[at + 2] = v.z.toFloat()
    }
}

object SceneInputs {
    /** Cam_Rest lens: 14 mm on a 36 mm sensor (fit AUTO), applied to the long screen axis. */
    val DEFAULT_FOV_LONG_DEG = Math.toDegrees(2 * atan(18.0 / 14.0))

    /**
     * Explicit pose (override with instant and position): Sun and Moon straight from the ephemerides at that instant,
     * no SGP4, so dates far from the TLE epoch work. Without a camera forward the view looks at nadir (no orbit
     * velocity, so no LVLH). Null when the override lacks the instant or the position.
     */
    fun buildPose(override: SceneOverride): Scene? {
        val instant = override.instant ?: return null
        val position = override.positionEcefKm ?: return null
        val jdTt = TimeScales.julianDateTt(instant)
        val gmst = Sidereal.gmstRad(TimeScales.julianDateUt1(instant))
        val sunEcef = Sun.apparent(jdTt).positionTemeKm(jdTt).rotatedFrameZ(gmst)
        val moonEcef = Moon.apparent(jdTt).positionTemeKm(jdTt).rotatedFrameZ(gmst)
        val forward = override.cameraForwardEcef ?: -SceneGeometry.enu(position).up
        // Velocity is unused: build() takes the camera from the forward direction.
        val state = SceneState(instant, position, Vector3(0.0, 0.0, 0.0), SceneGeometry.unit(sunEcef), SceneGeometry.unit(moonEcef - position))
        return build(state, RestView.NADIR, 0.0, 0.0, override.copy(cameraForwardEcef = forward))
    }

    /**
     * Camera = LVLH(ISS) x [SceneGeometry.LVLH_FROM_BLENDER] x rest view x look-around. Positive [yawDeg] turns the
     * view right (about the camera up axis), positive [pitchDeg] up (about the turned right axis).
     */
    fun build(state: SceneState, rest: RestView, yawDeg: Double, pitchDeg: Double, override: SceneOverride? = null): Scene {
        val position = override?.positionEcefKm ?: state.positionEcefKm
        val forward = override?.cameraForwardEcef
        val blenderFromCamera = if (forward != null) null else rest.blenderFromCamera * yaw(-yawDeg) * pitch(pitchDeg)
        val ecefFromCamera = if (blenderFromCamera == null) {
            lookAlong(position, SceneGeometry.unit(forward!!))
        } else {
            SceneGeometry.lvlh(position, state.velocityEcefKmPerSec) * SceneGeometry.LVLH_FROM_BLENDER * blenderFromCamera
        }
        return Scene(
            instant = state.instant,
            cameraEcefKm = position,
            ecefFromCamera = ecefFromCamera,
            fovLongDeg = override?.fovLongDeg ?: DEFAULT_FOV_LONG_DEG,
            sunDirEcef = state.sunDirEcef,
            moonDirEcef = state.moonDirEcef,
            month = monthBlend(state.instant),
            blenderFromCamera = blenderFromCamera,
        )
    }

    /** Layer m (month m + 1) is centred on the middle of its month; weight runs linearly between centres. */
    fun monthBlend(instant: Instant): MonthBlend {
        val t = instant.atOffset(ZoneOffset.UTC)
        val days = t.toLocalDate().lengthOfMonth()
        val dayFraction = (t.dayOfMonth - 1 + t.toLocalTime().toNanoOfDay() / 86_400e9) / days
        val f = t.monthValue - 1 + dayFraction - 0.5
        val base = floor(f)
        val layer0 = Math.floorMod(base.toInt(), 12)
        return MonthBlend(layer0, (layer0 + 1) % 12, f - base)
    }

    /** Camera along [forward] without roll: up lies in the vertical plane (north when looking straight up or down). */
    private fun lookAlong(position: Vector3, forward: Vector3): Mat3 {
        val enu = SceneGeometry.enu(position)
        val vertical = if (abs(forward dot enu.up) > 0.999999) enu.north else enu.up
        val right = SceneGeometry.unit(forward cross vertical)
        val up = right cross forward
        return Mat3(right, up, -forward)
    }

    private fun yaw(deg: Double): Mat3 {
        val c = cos(Math.toRadians(deg))
        val s = sin(Math.toRadians(deg))
        return Mat3(Vector3(c, 0.0, -s), Vector3(0.0, 1.0, 0.0), Vector3(s, 0.0, c))
    }

    private fun pitch(deg: Double): Mat3 {
        val c = cos(Math.toRadians(deg))
        val s = sin(Math.toRadians(deg))
        return Mat3(Vector3(1.0, 0.0, 0.0), Vector3(0.0, c, s), Vector3(0.0, -s, c))
    }
}
