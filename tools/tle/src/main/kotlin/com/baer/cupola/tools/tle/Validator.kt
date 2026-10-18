package com.baer.cupola.tools.tle

import com.baer.cupola.orbit.tle.Tle
import com.baer.cupola.orbit.tle.TleParseException
import java.time.Duration
import java.time.Instant

object Validator {
    const val ISS_CATALOG = 25544
    private val maxEpochAge = Duration.ofDays(3)

    /** The two element lines of [text], which may also carry a name line. */
    fun elementLines(text: String): Pair<String, String>? {
        val rows = text.lines().map { it.trimEnd() }
        val l1 = rows.firstOrNull { it.startsWith("1 ") } ?: return null
        val l2 = rows.firstOrNull { it.startsWith("2 ") } ?: return null
        return l1 to l2
    }

    /** One line per failed rule, empty when the TLE is acceptable. [text] holds an optional name line plus the two element lines. */
    fun validate(text: String, now: Instant): List<String> {
        val (l1, l2) = elementLines(text) ?: return listOf("parse: no TLE line 1 and line 2 found")
        val tle = try {
            Tle.parse(l1, l2)
        } catch (e: TleParseException) {
            val rule = if (e.reason == TleParseException.Reason.CHECKSUM) "checksum" else "parse"
            return listOf("$rule: ${e.message}")
        }
        return buildList {
            if (tle.catalogNumber != ISS_CATALOG) add("catalog: ${tle.catalogNumber}, expected $ISS_CATALOG")
            if (tle.meanMotionRevPerDay !in 15.3..15.8) add("mean-motion: ${tle.meanMotionRevPerDay} rev/day, expected 15.3 to 15.8")
            if (tle.eccentricity >= 0.01) add("eccentricity: ${tle.eccentricity}, expected below 0.01")
            if (tle.inclinationDeg !in 51.4..51.8) add("inclination: ${tle.inclinationDeg} deg, expected 51.6 +- 0.2")
            val age = Duration.between(tle.epoch, now)
            if (age >= maxEpochAge) add("epoch-age: ${Guard.ageDays(age)} days at $now, expected below 3 days")
        }
    }
}
