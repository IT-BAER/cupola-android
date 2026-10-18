package com.baer.cupola.core.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** The float layout is declared twice (Kotlin and C++); this keeps both in lockstep. */
class FrameInputsLayoutTest {
    @Test
    fun cppHeaderMatchesKotlinConstants() {
        val header = File("src/main/cpp/frame_inputs.h").readText()
        val cpp = Regex("""constexpr\s+int\s+k(\w+)\s*=\s*(\d+)\s*;""").findAll(header)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        val kotlin = mapOf(
            "Load" to FrameInputs.LOAD,
            "LoadIterations" to FrameInputs.LOAD_ITERATIONS,
            "TimeSeconds" to FrameInputs.TIME_SECONDS,
            "LookYawDeg" to FrameInputs.LOOK_YAW_DEG,
            "LookPitchDeg" to FrameInputs.LOOK_PITCH_DEG,
            "Count" to FrameInputs.COUNT,
        )
        assertEquals(kotlin, cpp)
    }
}
