package com.baer.cupola.ui

import android.content.Context
import com.baer.cupola.feature.settings.AppInfo
import com.baer.cupola.feature.settings.CreditsRepository
import com.baer.cupola.feature.settings.PreviewRenderer
import com.baer.cupola.feature.settings.ShareRenderer
import com.baer.cupola.orbit.geo.GeoIndex
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UiBindings {
    @Binds abstract fun preview(impl: RendererPreview): PreviewRenderer

    @Binds abstract fun share(impl: RendererShareRenderer): ShareRenderer

    companion object {
        @Provides
        fun appInfo(@ApplicationContext context: Context): AppInfo =
            AppInfo(context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty())

        @Provides
        fun credits(@ApplicationContext context: Context): CreditsRepository = AssetCreditsRepository(
            openAsset = { context.assets.open(it) },
            openResource = { GeoIndex::class.java.getResourceAsStream(it) },
        )
    }
}
