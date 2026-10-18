package com.baer.cupola.core.render

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Swapchain path through an ImageReader surface: attach, present, read back, detach. */
@RunWith(AndroidJUnit4::class)
class SurfaceTargetTest {
    @Test
    fun presentedFrameCarriesPatternAndDetachIsClean() {
        val reader = ImageReader.newInstance(
            WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3,
            HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
        )
        try {
            NativeRenderer().use { renderer ->
                renderer.attachSurface(SURFACE_ID, reader.surface)
                // Keeps the Choreographer loop from filling the reader's queue while the test reads it.
                renderer.updateScheduler(SURFACE_ID) { setVisible(false) }
                renderer.setFrameInputs(SURFACE_ID, FrameInputs.empty())
                var presented = false
                for (attempt in 0 until 3) presented = presented || renderer.renderFrame(SURFACE_ID)
                assertTrue(presented)

                val image = pollImage(reader)
                assertNotNull("no frame reached the ImageReader", image)
                image!!.use {
                    val plane = it.planes[0]
                    val buffer = plane.buffer
                    val row = ByteArray(WIDTH * 4)
                    val actual = ByteArray(WIDTH * HEIGHT * 4)
                    for (y in 0 until HEIGHT) {
                        buffer.position(y * plane.rowStride)
                        buffer.get(row)
                        row.copyInto(actual, y * WIDTH * 4)
                    }
                    assertEquals(4, plane.pixelStride)
                    assertEquals(TestPattern.expected(WIDTH, HEIGHT).toList(), actual.toList())
                }
                renderer.detachSurface(SURFACE_ID)
                renderer.attachSurface(SURFACE_ID, reader.surface)
                renderer.detachSurface(SURFACE_ID)
            }
        } finally {
            reader.close()
        }
    }

    private fun pollImage(reader: ImageReader) = run {
        val deadline = SystemClock.uptimeMillis() + 5_000
        var image = reader.acquireLatestImage()
        while (image == null && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(10)
            image = reader.acquireLatestImage()
        }
        image
    }

    private companion object {
        const val SURFACE_ID = 7
        const val WIDTH = 64
        const val HEIGHT = 48
    }
}
