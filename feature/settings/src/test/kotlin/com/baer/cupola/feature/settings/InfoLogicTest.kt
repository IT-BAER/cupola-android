package com.baer.cupola.feature.settings

import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.OrbitState
import com.baer.cupola.orbit.geo.GroundLabel
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoLogicTest {
    private val tle = FakeTleRepository().parsed
    private val orbit = OrbitModel(tle)
    private val metadata = OrbitMetadata(tle.epoch, "test", Instant.parse("2026-10-18T17:33:31Z"), retired = true)

    private fun firstInstantWhere(predicate: (OrbitState) -> Boolean): Instant {
        var t = tle.epoch.plusSeconds(86_400)
        repeat(270) {
            val s = orbit.stateAt(t) as OrbitState
            if (predicate(s)) return t
            t = t.plusSeconds(60)
        }
        error("no matching instant")
    }

    @Test
    fun nightPassHasPositiveTimeToDaylight() = runTest {
        val night = firstInstantWhere { it.sunlitFraction == 0.0 }
        val info = InfoLogic(orbit, FakeCityRepository()).snapshot(night, metadata, null)
        assertFalse(info.sunlit)
        val d = info.timeToDaylight!!
        assertTrue(d > Duration.ZERO && d < Duration.ofMinutes(60))
        val after = orbit.stateAt(night.plus(d.plusSeconds(15))) as OrbitState
        assertTrue(after.sunlitFraction > 0.0)
    }

    @Test
    fun dayPassHasZeroTimeToDaylight() = runTest {
        val day = firstInstantWhere { it.sunlitFraction == 1.0 }
        val info = InfoLogic(orbit, FakeCityRepository()).snapshot(day, metadata, null)
        assertTrue(info.sunlit)
        assertEquals(Duration.ZERO, info.timeToDaylight)
    }

    @Test
    fun labelIsQueriedAtTheIssGroundPointAndCarriesMetadata() = runTest {
        val t = tle.epoch.plusSeconds(3600)
        val cities = FakeCityRepository(label = GroundLabel.City("Vienna", "AT", 12.0))
        val info = InfoLogic(orbit, cities).snapshot(t, metadata, null)
        val s = orbit.stateAt(t) as OrbitState
        assertEquals(listOf(s.latitudeDeg to s.longitudeDeg), cities.labelCalls)
        assertEquals(GroundLabel.City("Vienna", "AT", 12.0), info.groundLabel)
        assertEquals(tle.epoch, info.orbitDataEpoch)
        assertTrue(info.retired)
        assertNull(info.nextPass)
    }

    @Test
    fun localTimeUsesFifteenDegreesPerHourRoundedToTheHour() {
        assertEquals(0, InfoLogic.utcOffsetHours(7.4))
        assertEquals(1, InfoLogic.utcOffsetHours(16.37))
        assertEquals(2, InfoLogic.utcOffsetHours(22.6))
        assertEquals(-1, InfoLogic.utcOffsetHours(-7.6))
        assertEquals(-5, InfoLogic.utcOffsetHours(-75.0))
        assertEquals(12, InfoLogic.utcOffsetHours(180.0))
        assertEquals(-12, InfoLogic.utcOffsetHours(-180.0))
        assertEquals(LocalTime.of(13, 30), InfoLogic.localTime(Instant.parse("2026-10-20T12:30:00Z"), 16.37))
    }

    @Test
    fun homeCityGetsItsNextPass() = runTest {
        val now = tle.epoch.plusSeconds(3600)
        val info = InfoLogic(orbit, FakeCityRepository()).snapshot(now, metadata, CityEntry(VIENNA))
        val pass = info.nextPass!!
        assertTrue(pass.end > now)
    }
}
