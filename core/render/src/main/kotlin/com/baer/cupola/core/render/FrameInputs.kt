package com.baer.cupola.core.render

/** Float layout of per-frame inputs; must match `src/main/cpp/frame_inputs.h`. */
object FrameInputs {
    /** Non-zero switches the fragment shader to the synthetic ALU load (test pattern pipeline). */
    const val LOAD = 0

    /** ALU loop iterations per fragment while [LOAD] is non-zero. */
    const val LOAD_ITERATIONS = 1

    const val TIME_SECONDS = 2

    /** Look-around offset in degrees: yaw shifts the view horizontally, pitch vertically. */
    const val LOOK_YAW_DEG = 3
    const val LOOK_PITCH_DEG = 4

    /** 1 renders the Earth scene from the slots below; 0 the test pattern. [LOAD] overrides both. */
    const val SCENE = 5

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

    const val COUNT = 31

    fun empty() = FloatArray(COUNT)
}
