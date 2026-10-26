package com.baer.cupola.feature.settings

import java.time.Instant

enum class RestDirection { DIAGONAL, NADIR }

/** Highest Earth render scale; the thermal governor can still go lower. */
enum class Quality { HIGH, MEDIUM, LOW }

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
    /** Frame rate while the view rests, one of [STILL_FPS_CHOICES]; about 8 mA per fps on a Pixel 7 Pro. */
    val stillFps: Int = STILL_FPS_DEFAULT,
    val quality: Quality = Quality.HIGH,
    val clouds: Boolean = true,
) {
    companion object {
        const val STILL_FPS_DEFAULT = 15
        val STILL_FPS_CHOICES = listOf(5, 10, 15, 30)

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
