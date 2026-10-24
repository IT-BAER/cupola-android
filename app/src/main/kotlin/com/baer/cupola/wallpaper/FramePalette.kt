package com.baer.cupola.wallpaper

/**
 * Wallpaper colours from a rendered frame (system theming, onComputeColors): [primary] is the mean of the brightest
 * quarter of the pixels, [secondary] the mean of all, [tertiary] the mean of the darkest quarter; colours as 0xRRGGBB.
 */
internal data class FramePalette(
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val supportsDarkText: Boolean,
    val supportsDarkTheme: Boolean,
) {
    companion object {
        /** [rgba]: tightly packed RGBA8, row-major (NativeRenderer.renderOffscreen). */
        fun of(rgba: ByteArray, width: Int, height: Int): FramePalette {
            val n = width * height
            require(rgba.size >= n * 4) { "frame too small: ${rgba.size} bytes for ${width}x$height" }
            fun ch(i: Int, c: Int) = rgba[i * 4 + c].toInt() and 0xFF
            fun luma(i: Int) = 0.2126 * ch(i, 0) + 0.7152 * ch(i, 1) + 0.0722 * ch(i, 2)
            val order = (0 until n).sortedBy(::luma)
            fun mean(indices: List<Int>): Int {
                var r = 0L
                var g = 0L
                var b = 0L
                for (i in indices) {
                    r += ch(i, 0); g += ch(i, 1); b += ch(i, 2)
                }
                val k = indices.size.coerceAtLeast(1)
                return ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
            }
            val quarter = (n / 4).coerceAtLeast(1)
            val meanLuma = order.sumOf(::luma) / n
            return FramePalette(
                primary = mean(order.takeLast(quarter)),
                secondary = mean(order),
                tertiary = mean(order.take(quarter)),
                supportsDarkText = meanLuma > 160,
                supportsDarkTheme = meanLuma < 100,
            )
        }
    }
}
