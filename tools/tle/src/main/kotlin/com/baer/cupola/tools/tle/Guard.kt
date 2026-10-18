package com.baer.cupola.tools.tle

import java.time.Duration
import java.time.Instant

object Guard {
    private val epochField = Regex(""""epoch"\s*:\s*"([^"]+)"""")

    /** Returns a failure message when [epoch] is older than [maxAgeDays] at [now], else null. */
    fun check(epoch: Instant, now: Instant, maxAgeDays: Long): String? {
        val age = Duration.between(epoch, now)
        if (age <= Duration.ofDays(maxAgeDays)) return null
        return "Bundled ISS TLE epoch $epoch is ${ageDays(age)} days old at $now; limit is $maxAgeDays days. Run the release-prep workflow."
    }

    /** Extracts the "epoch" field of iss.json. */
    fun epochOf(json: String): Instant {
        val value = epochField.find(json)?.groupValues?.get(1) ?: throw IllegalArgumentException("no \"epoch\" field in json")
        return Instant.parse(value)
    }

    internal fun ageDays(age: Duration): String = "%.1f".format(java.util.Locale.ROOT, age.toMillis() / 86_400_000.0)
}
