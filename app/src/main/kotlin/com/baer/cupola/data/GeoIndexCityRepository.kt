package com.baer.cupola.data

import com.baer.cupola.feature.settings.CityEntry
import com.baer.cupola.feature.settings.CityRepository
import com.baer.cupola.orbit.geo.GeoIndex
import com.baer.cupola.orbit.geo.GroundLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The index loads lazily on first use, off the caller thread. */
class GeoIndexCityRepository(private val index: Lazy<GeoIndex>) : CityRepository {
    override suspend fun search(query: String, limit: Int): List<CityEntry> =
        withContext(Dispatchers.Default) { index.value.search(query, limit).map(::CityEntry) }

    override suspend fun label(latDeg: Double, lonDeg: Double): GroundLabel =
        withContext(Dispatchers.Default) { index.value.label(latDeg, lonDeg) }

    // The id starts with the city name; a name-prefix search finds its entry (the index has no GeoNames ids).
    override suspend fun byId(id: String): CityEntry? = withContext(Dispatchers.Default) {
        index.value.search(id.substringBefore('|'), BY_ID_SEARCH_LIMIT).map(::CityEntry).firstOrNull { it.id == id }
    }

    private companion object {
        const val BY_ID_SEARCH_LIMIT = 500
    }
}
