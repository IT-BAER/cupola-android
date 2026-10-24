package com.baer.cupola.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.baer.cupola.feature.settings.CityRepository
import com.baer.cupola.feature.settings.SettingsRepository
import com.baer.cupola.feature.settings.TleRepository
import com.baer.cupola.orbit.geo.GeoIndex
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    // One DataStore per process: a second instance on the same file throws.
    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        settingsDataStore(CoroutineScope(SupervisorJob() + Dispatchers.IO)) { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    fun settingsRepository(store: DataStore<Preferences>): SettingsRepository = DataStoreSettingsRepository(store)

    @Provides
    @Singleton
    fun orbitRepository(@ApplicationContext context: Context): OrbitRepository {
        fun asset(path: String) = context.assets.open(path).use { it.readBytes().decodeToString() }
        return OrbitRepository(
            bundled = { asset("orbit/iss.tle") to asset("orbit/iss.json") },
            store = orbitStore(context),
            now = Instant::now,
        )
    }

    @Provides
    @Singleton
    fun tleRepository(orbits: OrbitRepository): TleRepository = orbits

    @Provides
    @Singleton
    fun orbitUpdater(@ApplicationContext context: Context, orbits: OrbitRepository, settings: SettingsRepository): OrbitUpdater =
        OrbitUpdater(
            http = UrlConnectionGet(),
            repository = orbits,
            store = orbitStore(context),
            enabled = { settings.settings.first().onlineOrbitUpdates },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            now = Instant::now,
            jitter = { Duration.ofMinutes(Random.nextLong(OrbitUpdater.MAX_JITTER.toMinutes())) },
        )

    private fun orbitStore(context: Context) = DownloadedOrbitStore(File(context.filesDir, "orbit"))

    @Provides
    @Singleton
    fun cityRepository(): CityRepository = GeoIndexCityRepository(lazy { GeoIndex.load() })
}

/** A corrupt settings file is replaced by defaults instead of failing every read. */
internal fun settingsDataStore(scope: CoroutineScope, file: () -> File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        scope = scope,
        produceFile = file,
    )
