package com.baer.cupola.feature.settings

import com.baer.cupola.orbit.geo.City
import com.baer.cupola.orbit.geo.GroundLabel
import com.baer.cupola.orbit.tle.Tle
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

const val ISS_LINE1 = "1 25544U 98067A   26291.46721112  .00007452  00000+0  14361-3 0  9999"
const val ISS_LINE2 = "2 25544  51.6327  50.0298 0006784 279.2532  80.7689 15.48923373590866"

class FakeSettingsRepository(initial: Settings = Settings()) : SettingsRepository {
    private val state = MutableStateFlow(initial)
    override val settings: Flow<Settings> = state
    override suspend fun setRestDirection(value: RestDirection) = state.update { it.copy(restDirection = value) }
    override suspend fun setLookAroundStrength(value: Float) =
        state.update { it.copy(lookAroundStrength = value.coerceIn(0f, 1f)) }
    override suspend fun setFieldOfView(value: Float) =
        state.update { it.copy(fieldOfViewDeg = value.coerceIn(Settings.FOV_MIN_DEG, Settings.FOV_MAX_DEG)) }
    override suspend fun setStillFps(value: Int) =
        state.update { if (value in Settings.STILL_FPS_CHOICES) it.copy(stillFps = value) else it }
    override suspend fun setQuality(value: Quality) = state.update { it.copy(quality = value) }
    override suspend fun setClouds(value: Boolean) = state.update { it.copy(clouds = value) }
    override suspend fun setIdleFloat(value: Boolean) = state.update { it.copy(idleFloat = value) }
    override suspend fun setInterior(value: Boolean) = state.update { it.copy(interior = value) }
    override suspend fun setBloom(value: Boolean) = state.update { it.copy(bloom = value) }
    override suspend fun setWindowGlare(value: Boolean) = state.update { it.copy(windowGlare = value) }
    override suspend fun setReflections(value: Boolean) = state.update { it.copy(reflections = value) }
    override suspend fun setDust(value: Boolean) = state.update { it.copy(dust = value) }
    override suspend fun setHomeCity(id: String?) = state.update { it.copy(homeCityId = id) }
    override suspend fun setOnlineOrbitUpdates(value: Boolean) = state.update { it.copy(onlineOrbitUpdates = value) }
}

class FakeCityRepository(
    val cities: List<City> = emptyList(),
    var label: GroundLabel = GroundLabel.Sea("Test Sea"),
) : CityRepository {
    val queries = mutableListOf<Pair<String, Int>>()
    val labelCalls = mutableListOf<Pair<Double, Double>>()
    override suspend fun search(query: String, limit: Int): List<CityEntry> {
        queries += query to limit
        return cities.filter { it.name.startsWith(query, ignoreCase = true) }.take(limit).map(::CityEntry)
    }
    override suspend fun label(latDeg: Double, lonDeg: Double): GroundLabel {
        labelCalls += latDeg to lonDeg
        return label
    }
    override suspend fun byId(id: String): CityEntry? = cities.map(::CityEntry).firstOrNull { it.id == id }
}

class FakeTleRepository(private val retired: Boolean = false) : TleRepository {
    val parsed: Tle = Tle.parse(ISS_LINE1, ISS_LINE2)
    override suspend fun metadata() =
        OrbitMetadata(parsed.epoch, "test", Instant.parse("2026-10-18T17:33:31Z"), retired)
    override suspend fun tle() = parsed
}

val VIENNA = City("Vienna", "AT", 48.2085, 16.3721, 1_900_000)
val GRAZ = City("Graz", "AT", 47.0667, 15.4333, 290_000)
