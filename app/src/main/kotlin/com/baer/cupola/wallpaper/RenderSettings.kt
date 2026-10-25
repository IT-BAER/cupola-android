package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.feature.settings.RestDirection
import com.baer.cupola.feature.settings.Settings

/** The part of [Settings] the render thread reads every frame; [effects] uses the bits below. */
data class RenderSettings(
    val rest: RestView = RestView.NADIR,
    val lookStrength: Double = 1.0,
    val idleFloat: Boolean = true,
    val interior: Boolean = true,
    val effects: Int = BLOOM or WINDOW_GLARE or REFLECTIONS or DUST,
    /** Home city (latitude, longitude) in degrees for the wallpaper marker (P6.7); null when none is set. */
    val homeCity: Pair<Double, Double>? = null,
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
    interior = interior,
    effects = (if (bloom) RenderSettings.BLOOM else 0) or
        (if (windowGlare) RenderSettings.WINDOW_GLARE else 0) or
        (if (reflections) RenderSettings.REFLECTIONS else 0) or
        (if (dust) RenderSettings.DUST else 0),
    homeCity = homeCityId?.let(::homeCityLatLon),
)

/** Latitude and longitude from the end of a CityEntry id (name|country|lat|lon); null when malformed. */
internal fun homeCityLatLon(id: String): Pair<Double, Double>? {
    val parts = id.split('|')
    if (parts.size < 4) return null
    val lat = parts[parts.size - 2].toDoubleOrNull() ?: return null
    val lon = parts[parts.size - 1].toDoubleOrNull() ?: return null
    return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
}
