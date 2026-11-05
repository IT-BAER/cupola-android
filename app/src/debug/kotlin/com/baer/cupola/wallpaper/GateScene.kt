package com.baer.cupola.wallpaper

import com.baer.cupola.core.render.scene.RestView
import com.baer.cupola.core.render.scene.SceneGeometry
import com.baer.cupola.core.render.scene.SceneOverride
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import java.time.Instant
import java.util.Locale

/**
 * Scene override from the gate fields (GateReceiver `scene` extras, holdout.json `gate` objects): [float] and [string]
 * return null for an absent field. Throws IllegalArgumentException or DateTimeParseException on a bad value.
 */
internal fun gateSceneOverride(float: (String) -> Double?, string: (String) -> String?): SceneOverride {
    val lat = float("lat")
    val lon = float("lon")
    val position = if (lat != null && lon != null) {
        Frames.geodeticToEcef(Geodetic(Math.toRadians(lat), Math.toRadians(lon), float("alt_km") ?: DEFAULT_ALT_KM))
    } else {
        null
    }
    val az = float("az")
    val el = float("el")
    val forward = if (position != null && az != null && el != null) SceneGeometry.directionFromAzEl(position, az, el) else null
    val ascending = when (val pass = string("pass")) {
        null -> null
        "asc" -> true
        "desc" -> false
        else -> throw IllegalArgumentException("pass=$pass")
    }
    val fisheye = when (val projection = string("projection")) {
        null, "rectilinear" -> null
        "fisheye" -> float("fisheye_fov") ?: DEFAULT_FISHEYE_FOV_DEG
        else -> throw IllegalArgumentException("projection=$projection")
    }
    return SceneOverride(
        instant = string("time")?.let(Instant::parse),
        positionEcefKm = position,
        cameraForwardEcef = forward,
        fovLongDeg = float("fov"),
        headingDeg = float("heading_deg") ?: if (lat != null && ascending != null) SceneGeometry.velocityAzimuthDeg(lat, ascending) else null,
        rollDeg = float("roll") ?: 0.0,
        rest = string("rest")?.let { RestView.valueOf(it.uppercase(Locale.ROOT)) },
        lookYawDeg = float("yaw") ?: 0.0,
        lookPitchDeg = float("pitch") ?: 0.0,
        fisheyeFovDeg = fisheye,
    )
}

private const val DEFAULT_ALT_KM = 420.0
private const val DEFAULT_FISHEYE_FOV_DEG = 180.0
