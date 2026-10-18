package com.baer.cupola.orbit.time

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeScalesTest {

    @Test
    fun leapSecondTableSteps() {
        assertEquals(10, TimeScales.taiMinusUtcSeconds(Instant.parse("1972-01-01T00:00:00Z")))
        assertEquals(36, TimeScales.taiMinusUtcSeconds(Instant.parse("2016-12-31T23:59:59Z")))
        assertEquals(37, TimeScales.taiMinusUtcSeconds(Instant.parse("2017-01-01T00:00:00Z")))
        assertEquals(37, TimeScales.taiMinusUtcSeconds(Instant.parse("2026-10-18T00:00:00Z")))
    }

    @Test
    fun rejectsInstantsBefore1972() {
        assertThrows(IllegalArgumentException::class.java) {
            TimeScales.taiMinusUtcSeconds(Instant.parse("1971-12-31T23:59:59Z"))
        }
    }

    @Test
    fun j2000IsTt() {
        // J2000.0 = 2000-01-01 12:00 TT; TAI-UTC was 32 s, so UTC = 11:58:55.816.
        val jdTt = TimeScales.julianDateTt(Instant.parse("2000-01-01T11:58:55.816Z"))
        assertEquals(2451545.0, jdTt, 1e-9)
    }

    @Test
    fun ut1IsUtcJulianDate() {
        assertEquals(2451545.0, TimeScales.julianDateUt1(Instant.parse("2000-01-01T12:00:00Z")), 0.0)
        assertEquals(2440587.5, TimeScales.julianDateUt1(Instant.EPOCH), 0.0)
    }

    @Test
    fun ttMinusUtcIn2026() {
        val utc = Instant.parse("2026-10-18T00:00:00Z")
        val diffSeconds = (TimeScales.julianDateTt(utc) - TimeScales.julianDateUt1(utc)) * 86400.0
        assertEquals(69.184, diffSeconds, 1e-4)
    }
}
