package com.baer.cupola.feature.settings

import java.time.Instant

enum class RestDirection { DIAGONAL, NADIR }

/**
 * User settings (design decision 19). [homeCityId] is a [CityEntry.id], null when no home city is set.
 * [onlineOrbitUpdates] allows the daily orbit data download from the project's GitHub Pages mirror.
 */
data class Settings(
    val restDirection: RestDirection = RestDirection.NADIR,
    /** 0 = no look-around, 1 = full. */
    val lookAroundStrength: Float = 1f,
    val idleFloat: Boolean = true,
    /** Show the baked Cupola cabin around the view; off renders the Earth only. */
    val interior: Boolean = true,
    val bloom: Boolean = true,
    val windowGlare: Boolean = true,
    val reflections: Boolean = true,
    val dust: Boolean = true,
    val homeCityId: String? = null,
    val onlineOrbitUpdates: Boolean = true,
    /** Field of view on the long screen axis in degrees, [FOV_MIN_DEG]..[FOV_MAX_DEG]. */
    val fieldOfViewDeg: Float = FOV_DEFAULT_DEG,
) {
    companion object {
        const val FOV_MIN_DEG = 60f
        const val FOV_MAX_DEG = 105f
        /** The rest lens, 16 mm on a 36 mm sensor (SceneInputs.DEFAULT_FOV_LONG_DEG). */
        const val FOV_DEFAULT_DEG = 96.73f
    }
}

/** Metadata of the bundled orbit data (`assets/orbit/iss.json`); [retired] is the decision 38 flag. */
data class OrbitMetadata(
    val epoch: Instant,
    val source: String,
    val fetched: Instant,
    val retired: Boolean = false,
)
