package com.baer.cupola.feature.settings

import com.baer.cupola.orbit.geo.City
import com.baer.cupola.orbit.geo.GroundLabel
import com.baer.cupola.orbit.tle.Tle
import kotlinx.coroutines.flow.Flow

/** Feature-owned ports; implemented in `:app`. */
interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun setRestDirection(value: RestDirection)

    /** Clamped to 0..1. */
    suspend fun setLookAroundStrength(value: Float)
    suspend fun setIdleFloat(value: Boolean)
    suspend fun setBloom(value: Boolean)
    suspend fun setWindowGlare(value: Boolean)
    suspend fun setReflections(value: Boolean)
    suspend fun setDust(value: Boolean)
    suspend fun setHomeCity(id: String?)
    suspend fun setOnlineOrbitUpdates(value: Boolean)
}

interface TleRepository {
    suspend fun metadata(): OrbitMetadata
    suspend fun tle(): Tle
}

/**
 * A city of the bundled index. The index carries no GeoNames id, so [id] is a stable key built from
 * name, country and rounded position.
 */
data class CityEntry(val city: City) {
    val id: String
        get() = "${city.name}|${city.countryCode}|${"%.3f".format(java.util.Locale.ROOT, city.latDeg)}|" +
            "%.3f".format(java.util.Locale.ROOT, city.lonDeg)
}

interface CityRepository {
    /** Diacritics-insensitive prefix search, most populous first. */
    suspend fun search(query: String, limit: Int): List<CityEntry>
    suspend fun label(latDeg: Double, lonDeg: Double): GroundLabel
    suspend fun byId(id: String): CityEntry?
}
