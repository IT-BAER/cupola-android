package com.baer.cupola.core.render

import org.junit.Assert.assertEquals
import org.junit.Test

class TestPatternTest {
    private fun pixel(bytes: ByteArray, width: Int, x: Int, y: Int): List<Int> {
        val i = (y * width + x) * 4
        return (0 until 4).map { bytes[i + it].toInt() and 0xFF }
    }

    @Test
    fun sizeIsTightlyPackedRgba() {
        assertEquals(64 * 48 * 4, TestPattern.expected(64, 48).size)
    }

    @Test
    fun originIsBlackOpaque() {
        assertEquals(listOf(0, 0, 0, 255), pixel(TestPattern.expected(64, 48), 64, 0, 0))
    }

    @Test
    fun channelsFollowFormula() {
        val bytes = TestPattern.expected(80, 72)
        assertEquals(listOf(28, 12, 0, 255), pixel(bytes, 80, 7, 3))
        assertEquals(listOf(32, 0, 255, 255), pixel(bytes, 80, 8, 0))
        assertEquals(listOf(32, 32, 0, 255), pixel(bytes, 80, 8, 8))
        assertEquals(listOf(0, 4, 0, 255), pixel(bytes, 80, 64, 1))
        assertEquals(listOf(252, 0, 255, 255), pixel(bytes, 80, 63, 64))
    }

    @Test
    fun rowsAreTopFirst() {
        val bytes = TestPattern.expected(2, 3)
        assertEquals(listOf(4, 8, 0, 255), bytes.copyOfRange(20, 24).map { it.toInt() and 0xFF })
    }
}
