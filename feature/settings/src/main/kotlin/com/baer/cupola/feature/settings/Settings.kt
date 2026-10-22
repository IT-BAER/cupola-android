package com.baer.cupola.feature.settings

import java.time.Instant

enum class RestDirection { DIAGONAL, NADIR }

/** User settings (design decision 19). [homeCityId] is a [CityEntry.id], null when no home city is set. */
data class Settings(
    val restDirection: RestDirection = RestDirection.NADIR,
    /** 0 = no look-around, 1 = full. */
    val lookAroundStrength: Float = 1f,
    val idleFloat: Boolean = true,
    val bloom: Boolean = true,
    val windowGlare: Boolean = true,
    val reflections: Boolean = true,
    val dust: Boolean = true,
    val homeCityId: String? = null,
)

/** Metadata of the bundled orbit data (`assets/orbit/iss.json`); [retired] is the decision 38 flag. */
data class OrbitMetadata(
    val epoch: Instant,
    val source: String,
    val fetched: Instant,
    val retired: Boolean = false,
)
