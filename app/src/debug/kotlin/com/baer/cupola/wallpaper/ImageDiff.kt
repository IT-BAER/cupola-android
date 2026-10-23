package com.baer.cupola.wallpaper

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Pixel comparison of two captures; a pixel's delta is its largest RGB channel difference. */
internal data class ImageDiff(val pixels: Int, val differing: Int, val maxDelta: Int, val meanDelta: Double) {
    fun line(a: String, b: String): String = String.format(
        Locale.ROOT, "gate diff a=%s b=%s pixels=%d differing=%d (%.3f%%) max_delta=%d mean_delta=%.3f",
        a, b, pixels, differing, if (pixels == 0) 0.0 else 100.0 * differing / pixels, maxDelta, meanDelta,
    )

    companion object {
        /** [a] and [b] are ARGB pixels (Bitmap.getPixels); a pixel differs when its delta exceeds [threshold]. */
        fun of(a: IntArray, b: IntArray, threshold: Int = 0): ImageDiff {
            require(a.size == b.size) { "sizes differ: ${a.size} vs ${b.size}" }
            var differing = 0
            var maxDelta = 0
            var sum = 0L
            for (i in a.indices) {
                val delta = max(channel(a[i], b[i], 16), max(channel(a[i], b[i], 8), channel(a[i], b[i], 0)))
                if (delta > threshold) differing++
                maxDelta = max(maxDelta, delta)
                sum += delta
            }
            return ImageDiff(a.size, differing, maxDelta, if (a.isEmpty()) 0.0 else sum.toDouble() / a.size)
        }

        private fun channel(a: Int, b: Int, shift: Int) = abs((a shr shift and 0xFF) - (b shr shift and 0xFF))
    }
}
