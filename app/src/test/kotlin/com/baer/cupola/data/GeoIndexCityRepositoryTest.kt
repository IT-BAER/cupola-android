package com.baer.cupola.data

import com.baer.cupola.feature.settings.CityEntry
import com.baer.cupola.orbit.geo.GeoIndex
import com.baer.cupola.orbit.geo.GroundLabel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoIndexCityRepositoryTest {
    private val repo = GeoIndexCityRepository(lazy { GeoIndex.load() })

    @Test
    fun labelOverViennaIsVienna() = runBlocking {
        val l = repo.label(48.2082, 16.3738) as GroundLabel.City
        assertEquals("Vienna", l.name)
        assertEquals("AT", l.countryCode)
    }

    @Test
    fun labelOverOpenOceanIsSea() = runBlocking {
        assertTrue(repo.label(0.0, -30.0) is GroundLabel.Sea)
    }

    @Test
    fun searchIsDiacriticsInsensitiveAndLimited() = runBlocking {
        val r = repo.search("zuri", 20)
        assertTrue(r.any { it.city.name == "Zürich" })
        assertTrue(repo.search("a", 20).size <= 20)
    }

    @Test
    fun byIdRoundTripsASearchResult() = runBlocking {
        val vienna = repo.search("vienna", 5).first { it.city.countryCode == "AT" }
        val found = repo.byId(vienna.id)
        assertNotNull(found)
        assertEquals(vienna, found)
    }
}
