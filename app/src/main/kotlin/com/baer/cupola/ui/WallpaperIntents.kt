package com.baer.cupola.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.baer.cupola.wallpaper.CupolaWallpaperService

object WallpaperIntents {
    /** Opens the system preview for this live wallpaper (P6.2). */
    fun changeLiveWallpaper(context: Context): Intent =
        Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(context, CupolaWallpaperService::class.java),
            )

    /** Fallback when no activity handles [changeLiveWallpaper]. */
    fun chooser(): Intent = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
}
