package com.baer.cupola.orbit.geo

data class City(
    val name: String,
    val countryCode: String,
    val latDeg: Double,
    val lonDeg: Double,
    val population: Int,
)

sealed interface GroundLabel {
    /** Nearest city; [distanceKm] is the great-circle distance from the queried point. */
    data class City(val name: String, val countryCode: String, val distanceKm: Double) : GroundLabel

    data class Sea(val name: String) : GroundLabel
}
