package com.baer.cupola.core.render

import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A surface whose consumer goes away is dropped without throwing; a new surface under the same id renders again. */
@RunWith(AndroidJUnit4::class)
class DroppedSurfaceTest {
    @Test
    fun closedSurfaceIsDroppedAndANewSurfaceRendersAgain() {
        NativeRenderer().use { renderer ->
            val first = newReader()
            try {
                assertEquals(0, renderer.attachSurface(SURFACE_ID, first.surface))
                renderer.updateScheduler(SURFACE_ID) { setVisible(false) }
                renderer.setFrameInputs(SURFACE_ID, FrameInputs.empty())
                assertTrue(presentOnce(renderer))
            } finally {
                first.close()
            }

            val start = SystemClock.uptimeMillis()
            while (renderer.isAttached(SURFACE_ID) && SystemClock.uptimeMillis() - start < DROP_TIMEOUT_MS) {
                renderer.renderFrame(SURFACE_ID)
            }
            val elapsed = SystemClock.uptimeMillis() - start
            assertFalse("id still attached after ${elapsed} ms", renderer.isAttached(SURFACE_ID))
            assertTrue("drop took $elapsed ms", elapsed < DROP_TIMEOUT_MS)
            assertFalse(renderer.renderFrame(SURFACE_ID))

            val second = newReader()
            try {
                assertEquals(0, renderer.attachSurface(SURFACE_ID, second.surface))
                renderer.updateScheduler(SURFACE_ID) { setVisible(false) }
                renderer.setFrameInputs(SURFACE_ID, FrameInputs.empty())
                assertTrue(presentOnce(renderer))
                val image = pollImage(second)
                assertNotNull("no frame reached the new ImageReader", image)
                image!!.close()
                renderer.detachSurface(SURFACE_ID)
            } finally {
                second.close()
            }
        }
    }

    private fun newReader() = ImageReader.newInstance(
        WIDTH, HEIGHT, PixelFormat.RGBA_8888, 3,
        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN,
    )

    private fun presentOnce(renderer: NativeRenderer): Boolean {
        var presented = false
        for (attempt in 0 until 3) presented = presented || renderer.renderFrame(SURFACE_ID)
        return presented
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
        const val SURFACE_ID = 11
        const val WIDTH = 64
        const val HEIGHT = 48
        const val DROP_TIMEOUT_MS = 2_000L
    }
}
