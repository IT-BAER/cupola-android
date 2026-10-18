package com.baer.cupola.tools.tle

import com.baer.cupola.orbit.tle.Tle
import java.util.Locale

/** Builds a syntactically valid TLE with correct checksums; every field the rules look at is a parameter. */
object TleFixtures {
    fun lines(
        catalog: Int = 25544,
        inclination: Double = 51.6400,
        eccentricity: Int = 6703,
        meanMotion: Double = 15.50000000,
        yy: Int = 26,
        day: Double = 290.50000000,
    ): String {
        val l1 = String.format(Locale.ROOT, "1 %05dU 98067A   %02d%012.8f  .00016717  00000-0  10270-3 0  999", catalog, yy, day)
        val l2 = String.format(Locale.ROOT, "2 %05d %8.4f 247.4627 %07d 130.5360 325.0288 %11.8f12345", catalog, inclination, eccentricity, meanMotion)
        return "ISS (ZARYA)\n${l1}${Tle.checksum(l1)}\n${l2.take(68)}${Tle.checksum(l2.take(68))}\n"
    }

    /** Flips the line 1 checksum digit. */
    fun withBadChecksum(text: String): String {
        val rows = text.lines().toMutableList()
        val last = rows[1].last()
        rows[1] = rows[1].dropLast(1) + (if (last == '0') '1' else '0')
        return rows.joinToString("\n")
    }
}
