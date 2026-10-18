package com.baer.cupola.orbit.time

import java.time.Instant

/** UTC [Instant] to Julian dates in TT and (approximately) UT1. */
object TimeScales {

    private const val UNIX_EPOCH_JD = 2440587.5
    private const val SECONDS_PER_DAY = 86400.0
    private const val TT_MINUS_TAI_SECONDS = 32.184

    // TAI-UTC steps (UTC epoch seconds, TAI-UTC) from IERS Bulletin C / USNO tai-utc.dat.
    // Valid until 2027-06-30: Bulletin C 72 (2026-07-06) announced no leap second at end of 2026.
    private val leapSeconds: List<Pair<Long, Int>> = listOf(
        "1972-01-01" to 10, "1972-07-01" to 11, "1973-01-01" to 12, "1974-01-01" to 13,
        "1975-01-01" to 14, "1976-01-01" to 15, "1977-01-01" to 16, "1978-01-01" to 17,
        "1979-01-01" to 18, "1980-01-01" to 19, "1981-07-01" to 20, "1982-07-01" to 21,
        "1983-07-01" to 22, "1985-07-01" to 23, "1988-01-01" to 24, "1990-01-01" to 25,
        "1991-01-01" to 26, "1992-07-01" to 27, "1993-07-01" to 28, "1994-07-01" to 29,
        "1996-01-01" to 30, "1997-07-01" to 31, "1999-01-01" to 32, "2006-01-01" to 33,
        "2009-01-01" to 34, "2012-07-01" to 35, "2015-07-01" to 36, "2017-01-01" to 37,
    ).map { (date, offset) -> Instant.parse("${date}T00:00:00Z").epochSecond to offset }

    /** TAI-UTC in seconds; the last entry is held after the table's validity date. */
    fun taiMinusUtcSeconds(utc: Instant): Int {
        require(utc.epochSecond >= leapSeconds.first().first) { "UTC before 1972 is not supported: $utc" }
        return leapSeconds.last { it.first <= utc.epochSecond }.second
    }

    fun julianDateTt(utc: Instant): Double =
        julianDate(utc, taiMinusUtcSeconds(utc) + TT_MINUS_TAI_SECONDS)

    // UT1 is approximated by UTC (SGP4 convention); |UT1-UTC| < 0.9 s by IERS definition.
    fun julianDateUt1(utc: Instant): Double = julianDate(utc, 0.0)

    private fun julianDate(utc: Instant, offsetSeconds: Double): Double =
        UNIX_EPOCH_JD + (utc.epochSecond + utc.nano / 1e9 + offsetSeconds) / SECONDS_PER_DAY
}
