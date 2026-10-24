package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePaletteTest {
    /** RGBA8 frame, [top] fraction of the rows in colour a, the rest in colour b. */
    private fun frame(w: Int, h: Int, top: Double, a: Int, b: Int) = ByteArray(w * h * 4).also { px ->
        for (y in 0 until h) for (x in 0 until w) {
            val c = if (y < h * top) a else b
            val i = (y * w + x) * 4
            px[i] = (c shr 16 and 0xFF).toByte(); px[i + 1] = (c shr 8 and 0xFF).toByte(); px[i + 2] = (c and 0xFF).toByte()
            px[i + 3] = 0xFF.toByte()
        }
    }

    private fun Int.rgb() = Triple(this shr 16 and 0xFF, this shr 8 and 0xFF, this and 0xFF)

    /** Night window: a blue Earth limb over a black cabin. Primary is the blue, tertiary the black, dark theme. */
    @Test
    fun darkFrameWithABlueWindow() {
        val p = FramePalette.of(frame(40, 80, 0.3, 0x2E6FD8, 0x050505), 40, 80)
        assertEquals(Triple(0x2E, 0x6F, 0xD8), p.primary.rgb())
        assertEquals(Triple(5, 5, 5), p.tertiary.rgb())
        assertTrue(p.supportsDarkTheme)
        assertFalse(p.supportsDarkText)
    }

    /** A bright cloudy day frame supports dark text, not the dark theme. */
    @Test
    fun brightFrameSupportsDarkText() {
        val p = FramePalette.of(frame(40, 80, 0.5, 0xF0F0F0, 0xC8D2E6), 40, 80)
        assertTrue(p.supportsDarkText)
        assertFalse(p.supportsDarkTheme)
        val (r, g, b) = p.secondary.rgb()
        assertEquals((0xF0 + 0xC8) / 2.0, r.toDouble(), 2.0)
        assertEquals((0xF0 + 0xD2) / 2.0, g.toDouble(), 2.0)
        assertEquals((0xF0 + 0xE6) / 2.0, b.toDouble(), 2.0)
    }
}
