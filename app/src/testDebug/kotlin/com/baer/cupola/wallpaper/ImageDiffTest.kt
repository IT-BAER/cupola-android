package com.baer.cupola.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDiffTest {
    private val grey = 0xFF808080.toInt()
    private val image = IntArray(4) { grey }

    @Test fun identicalImagesDoNotDiffer() {
        assertEquals(ImageDiff(pixels = 4, differing = 0, maxDelta = 0, meanDelta = 0.0), ImageDiff.of(image, image.copyOf()))
    }

    @Test fun countsPixelsWhoseLargestChannelDeltaExceedsTheThreshold() {
        val other = image.copyOf().also {
            it[1] = 0xFF8A8080.toInt() // red +10
            it[2] = 0xFF808078.toInt() // blue -8
        }
        assertEquals(ImageDiff(pixels = 4, differing = 1, maxDelta = 10, meanDelta = 4.5), ImageDiff.of(image, other, threshold = 8))
        assertEquals(2, ImageDiff.of(image, other).differing)
    }

    @Test fun alphaIsIgnored() {
        assertEquals(0, ImageDiff.of(image, IntArray(4) { 0x00808080 }).differing)
    }

    @Test(expected = IllegalArgumentException::class)
    fun sizesMustMatch() {
        ImageDiff.of(image, IntArray(3) { grey })
    }

    @Test fun formatsTheLogLine() {
        assertEquals(
            "gate diff a=a.png b=b.png pixels=4 differing=1 (25.000%) max_delta=10 mean_delta=4.500",
            ImageDiff(4, 1, 10, 4.5).line("a.png", "b.png"),
        )
    }
}
