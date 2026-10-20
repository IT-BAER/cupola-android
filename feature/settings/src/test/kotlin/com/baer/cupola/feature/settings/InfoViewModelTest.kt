package com.baer.cupola.feature.settings

import app.cash.turbine.test
import com.baer.cupola.orbit.geo.GroundLabel
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InfoViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun emitsSnapshotWithLabelAndRetiredFlag() = runTest {
        val cities = FakeCityRepository(label = GroundLabel.Sea("Indian Ocean"))
        val vm = InfoViewModel(FakeSettingsRepository(), FakeTleRepository(retired = true), cities, clock, dispatcher)
        vm.uiState.test {
            assertNull(awaitItem().snapshot)
            val info = awaitItem().snapshot!!
            assertEquals(GroundLabel.Sea("Indian Ocean"), info.groundLabel)
            assertTrue(info.retired)
            assertNull(info.nextPass)
        }
    }

    @Test
    fun homeCityAddsNextPass() = runTest {
        val cities = FakeCityRepository(listOf(VIENNA))
        val settings = FakeSettingsRepository(Settings(homeCityId = CityEntry(VIENNA).id))
        val vm = InfoViewModel(settings, FakeTleRepository(), cities, clock, dispatcher)
        vm.uiState.test {
            var s = awaitItem()
            while (s.snapshot?.nextPass == null) s = awaitItem()
            assertNotNull(s.snapshot.homeCity)
        }
    }
}
