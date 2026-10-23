package com.baer.cupola.ui

import android.content.ActivityNotFoundException
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import com.baer.cupola.feature.settings.AppInfo
import com.baer.cupola.feature.settings.CreditsViewModel
import com.baer.cupola.feature.settings.HomeCitySearchViewModel
import com.baer.cupola.feature.settings.InfoViewModel
import com.baer.cupola.feature.settings.PreviewRenderer
import com.baer.cupola.feature.settings.SettingsViewModel
import com.baer.cupola.feature.settings.ui.CupolaNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var preview: PreviewRenderer
    @Inject lateinit var share: ShareController
    @Inject lateinit var appInfo: AppInfo

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableCupolaEdgeToEdge()
        setContent {
            CupolaNavHost(
                settings = hiltViewModel<SettingsViewModel>(),
                info = hiltViewModel<InfoViewModel>(),
                search = hiltViewModel<HomeCitySearchViewModel>(),
                credits = hiltViewModel<CreditsViewModel>(),
                preview = preview,
                appInfo = appInfo,
                onSetWallpaper = ::setWallpaper,
                onShare = ::shareImage,
            )
        }
    }

    private fun setWallpaper() {
        try {
            startActivity(WallpaperIntents.changeLiveWallpaper(this))
        } catch (_: ActivityNotFoundException) {
            startActivity(WallpaperIntents.chooser())
        }
    }

    private fun shareImage() {
        lifecycleScope.launch { share.share(::startActivity) }
    }
}

// The app is always dark, so bar icons stay light whatever the system theme.
internal fun ComponentActivity.enableCupolaEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
    )
}
