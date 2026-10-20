package com.baer.cupola.feature.settings

import app.cash.turbine.test
import com.baer.cupola.orbit.geo.City
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeCitySearchViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(StandardTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun searchIsDebouncedToTheLastQuery() = runTest {
        val cities = FakeCityRepository(listOf(VIENNA, GRAZ))
        val vm = HomeCitySearchViewModel(FakeSettingsRepository(), cities)
        vm.uiState.test {
            awaitItem()
            vm.onQueryChange("V")
            advanceTimeBy(100)
            vm.onQueryChange("Vi")
            advanceTimeBy(100)
            vm.onQueryChange("Vie")
            var s = awaitItem()
            while (s.results.isEmpty()) s = awaitItem()
            assertEquals(listOf(VIENNA), s.results.map { it.city })
            assertEquals("Vie", s.query)
        }
        assertEquals(listOf("Vie"), cities.queries.map { it.first })
    }

    @Test
    fun resultsAreCappedAtTwenty() = runTest {
        val many = (1..50).map { City("Ab$it", "XX", it.toDouble(), 0.0, it) }
        val cities = FakeCityRepository(many)
        val vm = HomeCitySearchViewModel(FakeSettingsRepository(), cities)
        vm.uiState.test {
            awaitItem()
            vm.onQueryChange("Ab")
            var s = awaitItem()
            while (s.results.isEmpty()) s = awaitItem()
            assertEquals(20, s.results.size)
        }
        assertEquals(20, cities.queries.single().second)
    }

    @Test
    fun blankQueryDoesNotSearch() = runTest {
        val cities = FakeCityRepository(listOf(VIENNA))
        val vm = HomeCitySearchViewModel(FakeSettingsRepository(), cities)
        vm.uiState.test {
            awaitItem()
            vm.onQueryChange("   ")
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }
        assertTrue(cities.queries.isEmpty())
    }

    @Test
    fun selectStoresTheHomeCity() = runTest {
        val repo = FakeSettingsRepository()
        val vm = HomeCitySearchViewModel(repo, FakeCityRepository(listOf(VIENNA)))
        vm.select(CityEntry(VIENNA))
        advanceUntilIdle()
        assertEquals(CityEntry(VIENNA).id, repo.settings.first().homeCityId)
    }
}
