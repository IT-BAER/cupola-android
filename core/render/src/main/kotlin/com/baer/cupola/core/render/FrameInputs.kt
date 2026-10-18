package com.baer.cupola.core.render

/** Float layout of per-frame inputs; must match `src/main/cpp/frame_inputs.h`. */
object FrameInputs {
    /** Non-zero switches the fragment shader from the test pattern to the synthetic ALU load. */
    const val LOAD = 0

    /** ALU loop iterations per fragment while [LOAD] is non-zero. */
    const val LOAD_ITERATIONS = 1

    const val TIME_SECONDS = 2

    /** Look-around offset in degrees: yaw shifts the view horizontally, pitch vertically. */
    const val LOOK_YAW_DEG = 3
    const val LOOK_PITCH_DEG = 4

    const val COUNT = 5

    fun empty() = FloatArray(COUNT)
}
