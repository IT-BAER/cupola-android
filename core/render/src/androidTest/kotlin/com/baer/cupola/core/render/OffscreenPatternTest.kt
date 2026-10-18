package com.baer.cupola.core.render

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OffscreenPatternTest {
    @Test
    fun offscreenPatternIsByteExact() {
        NativeRenderer().use { renderer ->
            val info = renderer.deviceInfo()
            Log.i(TAG, "device $info api ${info.apiVersionString}")
            val actual = renderer.renderOffscreen(WIDTH, HEIGHT, FrameInputs.empty())
            val expected = TestPattern.expected(WIDTH, HEIGHT)
            assertEquals(expected.size, actual.size)
            val mismatch = expected.indices.firstOrNull { expected[it] != actual[it] }
            if (mismatch != null) {
                val px = mismatch / 4
                val at = px * 4
                throw AssertionError(
                    "first mismatch at pixel (${px % WIDTH}, ${px / WIDTH}): expected " +
                        "${expected.rgba(at)} actual ${actual.rgba(at)}",
                )
            }
            if (info.timestampsSupported) assertTrue(renderer.lastGpuTimeNanos() >= 0)
        }
    }

    @Test
    fun loadModeReplacesPattern() {
        NativeRenderer().use { renderer ->
            val inputs = FrameInputs.empty().apply {
                this[FrameInputs.LOAD] = 1f
                this[FrameInputs.LOAD_ITERATIONS] = 64f
            }
            val actual = renderer.renderOffscreen(WIDTH, HEIGHT, inputs)
            assertNotEquals(TestPattern.expected(WIDTH, HEIGHT).toList(), actual.toList())
        }
    }

    private fun ByteArray.rgba(at: Int) = (0 until 4).map { this[at + it].toInt() and 0xFF }

    private companion object {
        const val TAG = "OffscreenPatternTest"
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
