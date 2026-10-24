package com.baer.cupola.orbit.tle

/** ISS plausibility rules shared by the release tooling (:tools:tle) and the in-app orbit update. */
object IssTle {
    const val CATALOG = 25544

    /** The two element lines of [text], which may also carry a name line. */
    fun elementLines(text: String): Pair<String, String>? {
        val rows = text.lines().map { it.trimEnd() }
        val l1 = rows.firstOrNull { it.startsWith("1 ") } ?: return null
        val l2 = rows.firstOrNull { it.startsWith("2 ") } ?: return null
        return l1 to l2
    }

    /** One line per failed rule, empty when [tle] is a plausible ISS element set. Epoch age is the caller's rule. */
    fun problems(tle: Tle): List<String> = buildList {
        if (tle.catalogNumber != CATALOG) add("catalog: ${tle.catalogNumber}, expected $CATALOG")
        if (tle.meanMotionRevPerDay !in 15.3..15.8) add("mean-motion: ${tle.meanMotionRevPerDay} rev/day, expected 15.3 to 15.8")
        if (tle.eccentricity >= 0.01) add("eccentricity: ${tle.eccentricity}, expected below 0.01")
        if (tle.inclinationDeg !in 51.4..51.8) add("inclination: ${tle.inclinationDeg} deg, expected 51.6 +- 0.2")
    }
}
