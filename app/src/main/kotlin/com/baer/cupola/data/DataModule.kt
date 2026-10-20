package com.baer.cupola.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    // One DataStore per process: a second instance on the same file throws.
    @Provides
    @Singleton
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("settings")
        }

    @Provides
    @Singleton
    fun settingsRepository(store: DataStore<Preferences>): SettingsRepository = DataStoreSettingsRepository(store)

    @Provides
    @Singleton
    fun tleRepository(@ApplicationContext context: Context): TleRepository =
        AssetTleRepository { path -> context.assets.open(path) }

    @Provides
    @Singleton
    fun cityRepository(): CityRepository = GeoIndexCityRepository(lazy { GeoIndex.load() })
}
