package com.baer.cupola.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
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
        enableEdgeToEdge()
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
        lifecycleScope.launch {
            try {
                startActivity(Intent.createChooser(share.createShareIntent(), null))
            } catch (e: Exception) {
                Log.w("Cupola", "share failed", e)
            }
        }
    }
}
