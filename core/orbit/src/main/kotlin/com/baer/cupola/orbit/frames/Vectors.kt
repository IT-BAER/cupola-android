package com.baer.cupola.orbit.frames

import com.baer.cupola.orbit.sgp4.Vector3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

operator fun Vector3.plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)
operator fun Vector3.minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)
operator fun Vector3.times(s: Double) = Vector3(x * s, y * s, z * s)
operator fun Vector3.unaryMinus() = Vector3(-x, -y, -z)
infix fun Vector3.dot(o: Vector3) = x * o.x + y * o.y + z * o.z
infix fun Vector3.cross(o: Vector3) = Vector3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
fun Vector3.norm() = sqrt(this dot this)

/** Angle between two vectors in radians, accurate also for tiny and near-pi angles. */
fun Vector3.angleTo(o: Vector3) = atan2((this cross o).norm(), this dot o)

/** Coordinates of this vector in a frame rotated by [angleRad] about z (passive rotation). */
fun Vector3.rotatedFrameZ(angleRad: Double): Vector3 {
    val c = cos(angleRad)
    val s = sin(angleRad)
    return Vector3(c * x + s * y, -s * x + c * y, z)
}
