package com.baer.cupola.data

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.Settings
import com.baer.cupola.feature.settings.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Preferences DataStore backing; the store must be a process-wide singleton (see the Hilt module). */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {
    override val settings: Flow<Settings> = store.data.map { p ->
        val defaults = Settings()
        Settings(
            restDirection = p[REST_DIRECTION]?.let { name -> RestDirection.entries.firstOrNull { it.name == name } }
                ?: defaults.restDirection,
            lookAroundStrength = (p[LOOK_AROUND] ?: defaults.lookAroundStrength).coerceIn(0f, 1f),
            idleFloat = p[IDLE_FLOAT] ?: defaults.idleFloat,
            interior = p[INTERIOR] ?: defaults.interior,
            bloom = p[BLOOM] ?: defaults.bloom,
            windowGlare = p[GLARE] ?: defaults.windowGlare,
            reflections = p[REFLECTIONS] ?: defaults.reflections,
            dust = p[DUST] ?: defaults.dust,
            homeCityId = p[HOME_CITY],
            onlineOrbitUpdates = p[ONLINE_ORBIT] ?: defaults.onlineOrbitUpdates,
            fieldOfViewDeg = (p[FIELD_OF_VIEW] ?: defaults.fieldOfViewDeg).coerceIn(Settings.FOV_MIN_DEG, Settings.FOV_MAX_DEG),
        )
    }

    override suspend fun setRestDirection(value: RestDirection) = put { it[REST_DIRECTION] = value.name }
    override suspend fun setLookAroundStrength(value: Float) = put { it[LOOK_AROUND] = value.coerceIn(0f, 1f) }
    override suspend fun setFieldOfView(value: Float) =
        put { it[FIELD_OF_VIEW] = value.coerceIn(Settings.FOV_MIN_DEG, Settings.FOV_MAX_DEG) }
    override suspend fun setIdleFloat(value: Boolean) = put { it[IDLE_FLOAT] = value }
    override suspend fun setInterior(value: Boolean) = put { it[INTERIOR] = value }
    override suspend fun setBloom(value: Boolean) = put { it[BLOOM] = value }
    override suspend fun setWindowGlare(value: Boolean) = put { it[GLARE] = value }
    override suspend fun setReflections(value: Boolean) = put { it[REFLECTIONS] = value }
    override suspend fun setDust(value: Boolean) = put { it[DUST] = value }
    override suspend fun setHomeCity(id: String?) = put { if (id == null) it.remove(HOME_CITY) else it[HOME_CITY] = id }
    override suspend fun setOnlineOrbitUpdates(value: Boolean) = put { it[ONLINE_ORBIT] = value }

    /** A failed write (storage full, I/O error) keeps the stored value; callers run in viewModelScope without a handler. */
    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        try {
            store.edit { block(it) }
        } catch (e: IOException) {
            Log.w("Cupola", "settings write failed", e)
        }
    }

    private companion object {
        val REST_DIRECTION = stringPreferencesKey("rest_direction")
        val LOOK_AROUND = floatPreferencesKey("look_around_strength")
        val IDLE_FLOAT = booleanPreferencesKey("idle_float")
        val INTERIOR = booleanPreferencesKey("interior")
        val BLOOM = booleanPreferencesKey("effect_bloom")
        val GLARE = booleanPreferencesKey("effect_window_glare")
        val REFLECTIONS = booleanPreferencesKey("effect_reflections")
        val DUST = booleanPreferencesKey("effect_dust")
        val HOME_CITY = stringPreferencesKey("home_city_id")
        val ONLINE_ORBIT = booleanPreferencesKey("online_orbit_updates")
        val FIELD_OF_VIEW = floatPreferencesKey("field_of_view_deg")
    }
}
