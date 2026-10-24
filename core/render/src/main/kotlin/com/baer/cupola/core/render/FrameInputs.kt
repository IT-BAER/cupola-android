package com.baer.cupola.core.render

/** Float layout of per-frame inputs; must match `src/main/cpp/frame_inputs.h`. */
object FrameInputs {
    /** Non-zero switches the fragment shader to the synthetic ALU load (test pattern pipeline). */
    const val LOAD = 0

    /** ALU loop iterations per fragment while [LOAD] is non-zero. */
    const val LOAD_ITERATIONS = 1

    /** Animation time: real seconds modulo one day, also during the preview time-lapse (lightning, aurora drift). */
    const val TIME_SECONDS = 2

    /** Look-around offset in degrees: yaw shifts the view horizontally, pitch vertically. */
    const val LOOK_YAW_DEG = 3
    const val LOOK_PITCH_DEG = 4

    /** 1 renders the Earth scene from the slots below; 0 the test pattern. [LOAD] overrides both. */
    const val SCENE = 5

    /**
     * Earth render scale (0, 1): the Earth goes into an offscreen target of this size, composited at full resolution.
     * 0 or >= 1 renders everything in one full-resolution pass.
     */
    const val RENDER_SCALE = 6

    /**
     * Effect switches: an OR of the EFFECT_* bits (user settings; [EFFECTS_ALL] by default, see `scene.Scene.effects`)
     * and debug-only DEBUG_* bits, which settings must leave at 0. Integer-valued float, below 2^24.
     */
    const val EFFECTS = 7
    const val EFFECT_BLOOM = 1
    const val EFFECT_WINDOW_GLARE = 2
    const val EFFECT_REFLECTIONS = 4
    const val EFFECT_DUST = 8
    const val EFFECTS_ALL = EFFECT_BLOOM or EFFECT_WINDOW_GLARE or EFFECT_REFLECTIONS or EFFECT_DUST

    /** State, not a setting: the system reduces motion (animator scale 0), so nothing flashes (lightning). */
    const val MOTION_REDUCED = 1 shl 8

    /** Debug: no atmosphere (bare ground and black space), for the analytic geometry checks. */
    const val DEBUG_NO_ATMOSPHERE = 1 shl 16

    /** Debug: atmosphere without its airlight (inscatter): ground light through the sun and view transmittance only. */
    const val DEBUG_NO_INSCATTER = 1 shl 17

    /** Debug: no clouds (and so no cloud shadows). */
    const val DEBUG_NO_CLOUDS = 1 shl 18

    /** Debug: clouds without their shadows on the ground. */
    const val DEBUG_NO_CLOUD_SHADOWS = 1 shl 19

    /** Debug: no city lights on the night side. */
    const val DEBUG_NO_CITY_LIGHTS = 1 shl 20

    /** Debug: no moonlight on the ground and clouds. */
    const val DEBUG_NO_MOONLIGHT = 1 shl 21

    /** Debug: no aurora. */
    const val DEBUG_NO_AURORA = 1 shl 22

    /** Debug: no lightning. */
    const val DEBUG_NO_LIGHTNING = 1 shl 23

    /**
     * Start of the scene block, copied into the per-frame uniform buffer (std140, vec4-aligned; see
     * `earth.frag`). Written by `scene.Scene.write`. Directions are ECEF unit vectors, positions ECEF km.
     */
    const val SCENE_BLOCK = 8
    const val CAMERA_POS_KM = 8

    /** |q|^2 - 1 of the camera position scaled to the unit ellipsoid, computed in double. */
    const val CAMERA_ELLIPSOID_C = 11
    const val CAMERA_RIGHT = 12
    const val MONTH_WEIGHT = 15
    const val CAMERA_UP = 16
    const val MONTH_LAYER0 = 19

    /** Camera back axis; the view direction is its negation. */
    const val CAMERA_BACK = 20
    const val MONTH_LAYER1 = 23
    const val SUN_DIR = 24

    /** tan(fov / 2) on the long axis of the logical image; the short axis scales by the aspect. */
    const val TAN_HALF_FOV_LONG = 27

    /** Topocentric Moon direction (ISS to Moon). */
    const val MOON_DIR = 28

    /** Lunar irradiance relative to the Sun's (phase law), for moonlit ground and clouds. */
    const val MOON_LIGHT = 31

    /**
     * Blender interior frame from camera (columns right, up, back) for the panorama lookup; [INTERIOR] = 1 composites
     * the interior, 0 shows the Earth alone (debug overrides).
     */
    const val INTERIOR_RIGHT = 32
    const val INTERIOR = 35
    const val INTERIOR_UP = 36

    /** Earth brightness that scales the baked Earthlight layer (radiance of the visible Earth, shader units). */
    const val EARTH_LIGHT = 39
    const val INTERIOR_BACK = 40

    /** Linear exposure applied before the tone map (scene.AutoExposure). */
    const val EXPOSURE = 43

    /**
     * Atmosphere frame of the camera in the ellipsoid-scaled space (z x a / b, the surface a sphere of radius a):
     * radial up, the Sun's azimuth axis (unit, perpendicular to up), and the nadir angles (rad) of the ground horizon
     * and of the atmosphere top's tangent. Parameterises the sky view LUT (earth.frag MODE 3).
     */
    const val SKY_UP = 44
    const val SKY_HORIZON_NADIR = 47
    const val SKY_SUN_AXIS = 48
    const val SKY_TOP_NADIR = 51

    /** Cloud time ([scene.CloudFlow]): flow phases A and B (cycle + fraction) and days; the fourth float is unused. */
    const val CLOUD_FLOW = 52

    /** Rows of the ECEF-to-galactic rotation ([scene.StarFrame]) for the stars map; each row's fourth float is unused. */
    const val STAR_ROW_X = 56
    const val STAR_ROW_Y = 60
    const val STAR_ROW_Z = 64

    /** Sunlight at the camera per unit sunlight ([scene.Scene.sunLight], 0 behind the Earth); w = glass facing 0..1. */
    const val SUN_LIGHT = 68

    const val COUNT = 72

    fun empty() = FloatArray(COUNT)
}
