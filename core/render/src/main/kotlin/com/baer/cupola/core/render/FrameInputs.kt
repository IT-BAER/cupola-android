package com.baer.cupola.core.render

/** Float layout of per-frame inputs; must match `src/main/cpp/frame_inputs.h`. */
object FrameInputs {
    /** Non-zero switches the fragment shader from the test pattern to the synthetic ALU load. */
    const val LOAD = 0

    /** ALU loop iterations per fragment while [LOAD] is non-zero. */
    const val LOAD_ITERATIONS = 1

    const val TIME_SECONDS = 2
    const val COUNT = 3

    fun empty() = FloatArray(COUNT)
}
