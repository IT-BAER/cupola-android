package com.baer.cupola.core.render

/** Expected RGBA8 bytes of the shader test pattern (tightly packed, row-major, top row first). */
object TestPattern {
    fun expected(width: Int, height: Int): ByteArray {
        val out = ByteArray(width * height * 4)
        var i = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[i++] = ((x * 4) and 255).toByte()
                out[i++] = ((y * 4) and 255).toByte()
                out[i++] = if (((x / 8 + y / 8) and 1) == 1) 255.toByte() else 0
                out[i++] = 255.toByte()
            }
        }
        return out
    }
}
