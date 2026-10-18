package com.baer.cupola.orbit.tle

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TleTest {

    private val line1 = "1 25544U 98067A   08264.51782528 -.00002182  00000-0 -11606-4 0  2927"
    private val line2 = "2 25544  51.6416 247.4627 0006703 130.5360 325.0288 15.72125391563537"

    @Test
    fun parsesIssFields() {
        val tle = Tle.parse(line1, line2)

        assertEquals(25544, tle.catalogNumber)
        assertEquals("98067A", tle.internationalDesignator)
        assertEquals(Instant.parse("2008-09-20T12:25:40.104192Z"), tle.epoch)
        assertEquals(2454729.5, tle.epochJdWhole, 0.0)
        assertEquals(0.51782528, tle.epochJdFraction, 0.0)
        assertEquals(-0.00002182, tle.ndot, 0.0)
        assertEquals(0.0, tle.nddot, 0.0)
        assertEquals(-0.11606e-4, tle.bstar, 1e-20)
        assertEquals(51.6416, tle.inclinationDeg, 0.0)
        assertEquals(247.4627, tle.raanDeg, 0.0)
        assertEquals(0.0006703, tle.eccentricity, 0.0)
        assertEquals(130.5360, tle.argumentOfPerigeeDeg, 0.0)
        assertEquals(325.0288, tle.meanAnomalyDeg, 0.0)
        assertEquals(15.72125391, tle.meanMotionRevPerDay, 0.0)
        assertEquals(292, tle.elementSetNumber)
        assertEquals(56353, tle.revolutionNumber)
    }

    @Test
    fun rejectsFlippedChecksumDigit() {
        val flipped = line2.dropLast(1) + "8"
        assertReason(TleParseException.Reason.CHECKSUM) { Tle.parse(line1, flipped) }
    }

    @Test
    fun rejectsMismatchedCatalogNumbers() {
        // 25545 vs 25544, with the checksum adjusted (+1) so only the catalog number differs.
        val other = "2 25545" + line2.substring(7, 68) + "8"
        assertReason(TleParseException.Reason.CATALOG_MISMATCH) { Tle.parse(line1, other) }
    }

    @Test
    fun rejectsWrongLineNumbers() {
        assertReason(TleParseException.Reason.LINE_NUMBER) { Tle.parse(line2, line1) }
        assertReason(TleParseException.Reason.LINE_NUMBER) { Tle.parse("3" + line1.drop(1), line2) }
    }

    @Test
    fun rejectsNonPositiveMeanMotion() {
        val body = line2.substring(0, 52) + " 0.00000000" + line2.substring(63, 68)
        assertReason(TleParseException.Reason.MALFORMED) { Tle.parse(line1, body + Tle.checksum(body)) }
    }

    private fun assertReason(expected: TleParseException.Reason, block: () -> Unit) {
        val e = assertThrows(TleParseException::class.java) { block() }
        assertEquals(expected, e.reason)
    }
}
