package com.baer.cupola.orbit.geo

/** [timeZone] is the GeoNames IANA zone id, or null when the source has none. */
data class City(
    val name: String,
    val countryCode: String,
    val latDeg: Double,
    val lonDeg: Double,
    val population: Int,
    val timeZone: String? = null,
)

sealed interface GroundLabel {
    /** Nearest city; [distanceKm] is the great-circle distance from the queried point. */
    data class City(val name: String, val countryCode: String, val distanceKm: Double, val timeZone: String? = null) : GroundLabel

    data class Sea(val name: String) : GroundLabel
}
