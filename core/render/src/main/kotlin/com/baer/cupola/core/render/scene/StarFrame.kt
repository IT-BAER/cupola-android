package com.baer.cupola.core.render.scene

import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.time.TimeScales
import java.time.Instant

/**
 * Rotation from ECEF (TEME turned by GMST) to galactic coordinates, the frame of the stars map
 * (tools/textures/README.md "stars"). J2000 stands in for TEME: precession since J2000 (0.36 deg in 2026) is ignored.
 */
object StarFrame {
    // IAU galactic frame from J2000 equatorial, rows x (centre), y (l = 90 deg), z (north pole).
    private val ROW_X = Vector3(-0.0548755604162154, -0.8734370902348850, -0.4838350155487132)
    private val ROW_Y = Vector3(0.4941094278755837, -0.4448296299600112, 0.7469822444972189)
    private val ROW_Z = Vector3(-0.8676661490190047, -0.1980763734312015, 0.4559837761750669)

    fun galacticFromEcef(instant: Instant): Mat3 {
        val jd = TimeScales.julianDateUt1(instant)
        fun column(e: Vector3): Vector3 {
            val j2000 = Frames.ecefToTeme(e, jd)
            return Vector3(ROW_X dot j2000, ROW_Y dot j2000, ROW_Z dot j2000)
        }
        return Mat3(column(Vector3(1.0, 0.0, 0.0)), column(Vector3(0.0, 1.0, 0.0)), column(Vector3(0.0, 0.0, 1.0)))
    }
}
