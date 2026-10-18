package com.baer.cupola.tools.tle

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidatorTest {
    // Epoch of the default fixture: 2026 day 290.5 = 2026-10-17T12:00:00Z.
    private val now = Instant.parse("2026-10-18T12:00:00Z")

    private fun failures(text: String, at: Instant = now) = Validator.validate(text, at)

    @Test fun validTlePasses() {
        assertEquals(emptyList<String>(), failures(TleFixtures.lines()))
    }

    @Test fun checksumErrorNamesTheRule() {
        val out = failures(TleFixtures.withBadChecksum(TleFixtures.lines()))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("checksum:"))
    }

    @Test fun wrongCatalogNumberFails() {
        val out = failures(TleFixtures.lines(catalog = 25545))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("catalog:") && out[0].contains("25545"))
    }

    @Test fun meanMotionOutOfRangeFails() {
        val out = failures(TleFixtures.lines(meanMotion = 15.9))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("mean-motion:") && out[0].contains("15.9"))
    }

    @Test fun eccentricityTooHighFails() {
        val out = failures(TleFixtures.lines(eccentricity = 200000))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("eccentricity:") && out[0].contains("0.02"))
    }

    @Test fun inclinationOutOfRangeFails() {
        val out = failures(TleFixtures.lines(inclination = 52.0))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("inclination:") && out[0].contains("52.0"))
    }

    @Test fun epochFourDaysOldFails() {
        val out = failures(TleFixtures.lines(), now.plusSeconds(3 * 86_400L))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("epoch-age:") && out[0].contains("4.0"))
    }

    @Test fun epochTwoDaysOldPasses() {
        assertEquals(emptyList<String>(), failures(TleFixtures.lines(), now.plusSeconds(86_400L + 43_200L)))
    }

    @Test fun garbageFailsAsParse() {
        val out = failures("not a tle\n")
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("parse:"))
    }
}

class FetchedFixtureTest {
    private val text = checkNotNull(javaClass.getResource("/iss-fetched.tle")).readText()

    @Test fun fetchedTlePassesAtItsEpoch() {
        assertEquals(emptyList<String>(), Validator.validate(text, Instant.parse("2026-10-18T04:00:00Z")))
    }

    @Test fun fetchedTleFailsAgeRuleAfterThreeDays() {
        val out = Validator.validate(text, Instant.parse("2026-10-22T04:00:00Z"))
        assertEquals(1, out.size)
        assertTrue(out[0], out[0].startsWith("epoch-age:"))
    }
}
