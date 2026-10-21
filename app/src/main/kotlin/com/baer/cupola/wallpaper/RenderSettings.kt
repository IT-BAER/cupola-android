package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.Settings

/** The part of [Settings] the render thread reads every frame; [effects] uses the bits below. */
data class RenderSettings(
    val rest: RestView = RestView.DIAGONAL,
    val lookStrength: Double = 1.0,
    val idleFloat: Boolean = true,
    val effects: Int = BLOOM or WINDOW_GLARE or REFLECTIONS or DUST,
) {
    companion object {
        const val BLOOM = 1
        const val WINDOW_GLARE = 2
        const val REFLECTIONS = 4
        const val DUST = 8
    }
}

fun Settings.toRenderSettings() = RenderSettings(
    rest = when (restDirection) {
        RestDirection.DIAGONAL -> RestView.DIAGONAL
        RestDirection.NADIR -> RestView.NADIR
    },
    lookStrength = lookAroundStrength.toDouble(),
    idleFloat = idleFloat,
    effects = (if (bloom) RenderSettings.BLOOM else 0) or
        (if (windowGlare) RenderSettings.WINDOW_GLARE else 0) or
        (if (reflections) RenderSettings.REFLECTIONS else 0) or
        (if (dust) RenderSettings.DUST else 0),
)
