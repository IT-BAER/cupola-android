package com.baer.cupola.feature.settings

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun defaultsMatchDecision19() = runTest {
        val vm = SettingsViewModel(FakeSettingsRepository(), FakeCityRepository())
        vm.uiState.test {
            var s = awaitItem()
            assertFalse(s.loaded)
            s = awaitItem()
            assertEquals(Settings(), s.settings)
            assertEquals(RestDirection.NADIR, s.settings.restDirection)
            assertEquals(1f, s.settings.lookAroundStrength)
            assertNull(s.homeCity)
        }
    }

    @Test
    fun changesFlowThroughTheRepositoryIntoUiState() = runTest {
        val vm = SettingsViewModel(FakeSettingsRepository(), FakeCityRepository())
        vm.uiState.test {
            var s = awaitItem()
            while (!s.loaded) s = awaitItem()
            vm.setRestDirection(RestDirection.NADIR)
            vm.setLookAroundStrength(1.7f)
            vm.setBloom(false)
            vm.setDust(false)
            while (s.settings.dust) s = awaitItem()
            assertEquals(RestDirection.NADIR, s.settings.restDirection)
            assertEquals(1f, s.settings.lookAroundStrength)
            assertFalse(s.settings.bloom)
        }
    }

    @Test
    fun resetRestoresDefaultsAndKeepsTheHomeCity() = runTest {
        val home = CityEntry(GRAZ).id
        val changed = Settings(RestDirection.NADIR, 0.2f, false, false, false, false, false, false, home)
        val vm = SettingsViewModel(FakeSettingsRepository(changed), FakeCityRepository(listOf(GRAZ)))
        vm.uiState.test {
            var s = awaitItem()
            while (!s.loaded) s = awaitItem()
            vm.resetToDefaults()
            while (!s.settings.dust) s = awaitItem()
            assertEquals(Settings(homeCityId = home), s.settings)
        }
    }

    @Test
    fun onlineOrbitUpdatesToggleSurvivesReset() = runTest {
        val vm = SettingsViewModel(FakeSettingsRepository(), FakeCityRepository())
        vm.uiState.test {
            var s = awaitItem()
            while (!s.loaded) s = awaitItem()
            assertEquals(true, s.settings.onlineOrbitUpdates)
            vm.setOnlineOrbitUpdates(false)
            while (s.settings.onlineOrbitUpdates) s = awaitItem()
            vm.setDust(false)
            while (s.settings.dust) s = awaitItem()
            vm.resetToDefaults()
            while (!s.settings.dust) s = awaitItem()
            assertFalse(s.settings.onlineOrbitUpdates)
        }
    }

    @Test
    fun homeCityIsResolvedFromItsId() = runTest {
        val cities = FakeCityRepository(listOf(VIENNA, GRAZ))
        val repo = FakeSettingsRepository(Settings(homeCityId = CityEntry(GRAZ).id))
        val vm = SettingsViewModel(repo, cities)
        vm.uiState.test {
            var s = awaitItem()
            while (s.homeCity == null) s = awaitItem()
            assertEquals(GRAZ, s.homeCity.city)
            vm.clearHomeCity()
            while (s.homeCity != null) s = awaitItem()
        }
    }
}
