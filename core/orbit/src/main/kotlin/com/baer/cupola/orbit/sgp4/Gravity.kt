// Constants from python-sgp4 2.27 propagation.py getgravconst (Brandon Rhodes, MIT licence);
// see core/orbit/NOTICE-python-sgp4.txt.
package com.baer.cupola.orbit.sgp4

import kotlin.math.sqrt

/** Earth gravity constant sets for SGP4. WGS-72 is the set TLEs are fitted with. */
enum class Gravity(
    val muKm3PerS2: Double,
    val radiusEarthKm: Double,
    val j2: Double,
    val j3: Double,
    val j4: Double,
) {
    WGS72(398600.8, 6378.135, 0.001082616, -0.00000253881, -0.00000165597),
    WGS84(398600.5, 6378.137, 0.00108262998905, -0.00000253215306, -0.00000161098761),
    ;

    /** Square root of mu in Earth radii^1.5 per minute. */
    val xke: Double = 60.0 / sqrt(radiusEarthKm * radiusEarthKm * radiusEarthKm / muKm3PerS2)
    val j3oj2: Double = j3 / j2
}
