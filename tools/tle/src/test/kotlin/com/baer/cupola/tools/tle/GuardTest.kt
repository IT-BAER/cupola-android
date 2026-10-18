package com.baer.cupola.tools.tle

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardTest {
    private val epoch = Instant.parse("2026-10-17T12:00:00Z")

    @Test fun thirtyDaysOldFails() {
        val message = Guard.check(epoch, epoch.plusSeconds(30 * 86_400L), 7)
        assertNotNull(message)
        assertTrue(message, message!!.contains("30.0") && message.contains("7"))
    }

    @Test fun twoDaysOldPasses() {
        assertNull(Guard.check(epoch, epoch.plusSeconds(2 * 86_400L), 7))
    }

    @Test fun exactlyAtLimitPasses() {
        assertNull(Guard.check(epoch, epoch.plusSeconds(7 * 86_400L), 7))
    }

    @Test fun epochIsReadFromJson() {
        val json = """{"epoch": "2026-10-17T12:00:00Z", "source": "x", "fetched": "2026-10-18T00:00:00Z", "catalog": 25544}"""
        assertEquals(epoch, Guard.epochOf(json))
    }
}
