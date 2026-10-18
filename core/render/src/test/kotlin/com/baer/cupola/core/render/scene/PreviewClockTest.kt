package com.baer.cupola.core.render.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewClockTest {
    private var monoNs = 0L
    private var utcMs = 1_700_000_000_000L
    private val moonRevPerDay = 13.176358 // inverse of ~27.3 d sidereal period
    private val clock = PreviewClock({ monoNs }, { utcMs }, moonRevPerDay)

    private fun advance(seconds: Double) {
        monoNs += (seconds * 1e9).toLong()
        utcMs += (seconds * 1000).toLong()
    }

    @Test fun realTimeWhenNotInPreview() {
        advance(10.0)
        assertEquals(utcMs, clock.nowUtcMillis())
        assertFalse(clock.isPreview)
    }

    @Test fun previewAdvancesOneOrbitPerPreviewOrbitSeconds() {
        val start = utcMs
        clock.setPreview(true)
        advance(25.0)
        val orbitMs = 86_400_000.0 / moonRevPerDay
        val simulated = clock.nowUtcMillis() - (start)
        assertEquals(orbitMs, simulated.toDouble(), orbitMs * 1e-6 + 1_000.0)
    }

    @Test fun previewOrbitSecondsIsConfigurable() {
        val c = PreviewClock({ monoNs }, { utcMs }, 1.0, previewOrbitSeconds = 20.0)
        val start = utcMs
        c.setPreview(true)
        monoNs += 10_000_000_000L
        assertEquals(43_200_000L, c.nowUtcMillis() - start)
    }

    @Test fun previewStartsFromRealTimeAtSwitchOn() {
        advance(100.0)
        val start = utcMs
        clock.setPreview(true)
        assertEquals(start, clock.nowUtcMillis())
    }

    @Test fun switchingOffReturnsToRealTimeImmediately() {
        clock.setPreview(true)
        advance(12.0)
        clock.setPreview(false)
        assertEquals(utcMs, clock.nowUtcMillis())
        advance(5.0)
        assertEquals(utcMs, clock.nowUtcMillis())
    }

    @Test fun restartingPreviewRestartsFromRealTime() {
        clock.setPreview(true)
        advance(12.0)
        clock.setPreview(false)
        clock.setPreview(true)
        assertEquals(utcMs, clock.nowUtcMillis())
    }

    @Test fun previewOrbitSecondsOutsideRangeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { PreviewClock({ 0L }, { 0L }, 1.0, 19.9) }
        assertThrows(IllegalArgumentException::class.java) { PreviewClock({ 0L }, { 0L }, 1.0, 30.1) }
        assertTrue(PreviewClock({ 0L }, { 0L }, 1.0, 30.0).previewOrbitSeconds == 30.0)
    }
}
