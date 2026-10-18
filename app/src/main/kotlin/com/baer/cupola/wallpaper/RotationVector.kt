package com.baer.cupola.wallpaper

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Viewing direction as compass-style yaw (positive turning right, from above) and pitch (positive looking up). */
data class Orientation(val yawRad: Double, val pitchRad: Double)

object RotationVector {
    private const val VERTICAL_EPS = 1e-6

    /** Android sensor layout: x, y, z, optional w of the device-to-world quaternion. */
    fun fromSensorValues(values: FloatArray): Orientation {
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        val w = if (values.size > 3) values[3].toDouble() else sqrt(maxOf(0.0, 1.0 - x * x - y * y - z * z))
        return toYawPitch(x, y, z, w)
    }

    /**
     * Android world frame (X east, Y north, Z up) and device frame (X right, Y top edge, Z out of the screen).
     * The viewing direction is the device -Z axis in world coordinates: a phone held upright in portrait
     * facing north is yaw 0, pitch 0; flat on a table, face up, it is yaw 0, pitch -90 deg.
     * At the vertical poles the heading comes from the top edge instead (negated when looking up), so yaw
     * stays finite and continuous there.
     */
    fun toYawPitch(x: Double, y: Double, z: Double, w: Double): Orientation {
        val dx = -2.0 * (x * z + w * y)
        val dy = -2.0 * (y * z - w * x)
        val dz = -(1.0 - 2.0 * (x * x + y * y))
        val horizontal = hypot(dx, dy)
        val pitch = atan2(dz, horizontal)
        val yaw = if (horizontal >= VERTICAL_EPS) {
            atan2(dx, dy)
        } else {
            val topX = 2.0 * (x * y - w * z)
            val topY = 1.0 - 2.0 * (x * x + z * z)
            if (dz < 0) atan2(topX, topY) else atan2(-topX, -topY)
        }
        return Orientation(yaw, pitch)
    }
}
