package com.baer.cupola.tools.tle

import com.baer.cupola.orbit.tle.IssTle
import com.baer.cupola.orbit.tle.Tle
import com.baer.cupola.orbit.tle.TleParseException
import java.time.Duration
import java.time.Instant

object Validator {
    const val ISS_CATALOG = IssTle.CATALOG
    private val maxEpochAge = Duration.ofDays(3)

    /** The two element lines of [text], which may also carry a name line. */
    fun elementLines(text: String): Pair<String, String>? = IssTle.elementLines(text)

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
            addAll(IssTle.problems(tle))
            val age = Duration.between(tle.epoch, now)
            if (age >= maxEpochAge) add("epoch-age: ${Guard.ageDays(age)} days at $now, expected below 3 days")
        }
    }
}
