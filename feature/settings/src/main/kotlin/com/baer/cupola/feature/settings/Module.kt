package com.baer.cupola.feature.settings

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Named
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

@Module
@InstallIn(SingletonComponent::class)
object FeatureSettingsModule {
    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @Provides
    @Named(InfoViewModel.COMPUTE_DISPATCHER)
    fun computeDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
