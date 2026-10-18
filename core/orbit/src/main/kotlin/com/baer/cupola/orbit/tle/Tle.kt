// TLE field layout ported from python-sgp4 2.27 io.py / model.py (Brandon Rhodes, MIT licence);
// see core/orbit/NOTICE-python-sgp4.txt.
package com.baer.cupola.orbit.tle

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.pow

/**
 * One parsed two-line element set, in TLE units (degrees, revolutions per day).
 *
 * [epochJdWhole] + [epochJdFraction] is the epoch as a split Julian date, built the same way as
 * python-sgp4 `Satrec.jdsatepoch` / `jdsatepochF`: the fraction is the exact 8-digit TLE day fraction.
 */
data class Tle(
    val catalogNumber: Int,
    val internationalDesignator: String,
    val epoch: Instant,
    val epochJdWhole: Double,
    val epochJdFraction: Double,
    val ndot: Double,
    val nddot: Double,
    val bstar: Double,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val eccentricity: Double,
    val argumentOfPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
    val meanMotionRevPerDay: Double,
    val elementSetNumber: Int,
    val revolutionNumber: Int,
) {
    companion object {
        /** Parses and validates a TLE; throws [TleParseException] on any defect. */
        fun parse(line1: String, line2: String): Tle = parse(line1, line2, verifyChecksum = true)

        internal fun parse(line1: String, line2: String, verifyChecksum: Boolean): Tle {
            val l1 = line1.trimEnd()
            val l2 = line2.trimEnd()
            if (!l1.startsWith("1 ")) fail(TleParseException.Reason.LINE_NUMBER, "line 1 must start with '1 '")
            if (!l2.startsWith("2 ")) fail(TleParseException.Reason.LINE_NUMBER, "line 2 must start with '2 '")
            checkLayout(l1, 1, intArrayOf(8, 32, 43, 52, 61, 63), intArrayOf(23, 34))
            checkLayout(l2, 2, intArrayOf(7, 16, 25, 33, 42, 51), intArrayOf(11, 20, 37, 46))
            if (l1.substring(2, 7) != l2.substring(2, 7)) {
                fail(TleParseException.Reason.CATALOG_MISMATCH, "catalog numbers in line 1 and 2 differ")
            }
            if (verifyChecksum) {
                verifyChecksum(l1, 1)
                verifyChecksum(l2, 2)
            }

            val twoDigitYear = int(l1, 18, 20)
            val year = if (twoDigitYear < 57) twoDigitYear + 2000 else twoDigitYear + 1900
            val dayOfYear = int(l1, 20, 23)
            val fractionDigits = l1.substring(24, 32)
            if (!fractionDigits.all { it.isDigit() }) malformed(1, "epoch day fraction")
            val fraction = "0.$fractionDigits".toDouble()
            // 86400e9 ns per day / 1e8 per 8-digit unit = 864000 ns: the Instant is exact.
            val epoch = LocalDate.ofYearDay(year, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
                .plusSeconds((dayOfYear - 1) * 86_400L)
                .plusNanos(fractionDigits.toLong() * 864_000L)

            return Tle(
                catalogNumber = int(l1, 2, 7),
                internationalDesignator = l1.substring(9, 17).trimEnd(),
                epoch = epoch,
                epochJdWhole = year * 365.0 + (year - 1) / 4 + dayOfYear + 1721044.5,
                epochJdFraction = fraction,
                ndot = double(l1, 33, 43),
                nddot = exponent(l1, 44),
                bstar = exponent(l1, 53),
                inclinationDeg = double(l2, 8, 16),
                raanDeg = double(l2, 17, 25),
                eccentricity = ("0." + l2.substring(26, 33).replace(' ', '0')).toDoubleOrMalformed(2, "eccentricity"),
                argumentOfPerigeeDeg = double(l2, 34, 42),
                meanAnomalyDeg = double(l2, 43, 51),
                meanMotionRevPerDay = double(l2, 52, 63).also { if (it <= 0.0) malformed(2, "mean motion") },
                elementSetNumber = int(l1, 64, 68),
                revolutionNumber = int(l2, 63, 68),
            )
        }

        /** Modulo-10 sum of columns 1-68: digits count their value, '-' counts 1. */
        fun checksum(line: String): Int =
            line.take(68).sumOf { c -> if (c.isDigit()) c - '0' else if (c == '-') 1 else 0 } % 10

        private fun verifyChecksum(line: String, number: Int) {
            val given = line[68]
            val computed = checksum(line)
            if (given - '0' != computed) {
                fail(TleParseException.Reason.CHECKSUM, "line $number checksum is '$given' but computes to $computed")
            }
        }

        private fun checkLayout(line: String, number: Int, spaces: IntArray, dots: IntArray) {
            if (line.length != 69) malformed(number, "length ${line.length}, expected 69")
            if (spaces.any { line[it] != ' ' } || dots.any { line[it] != '.' }) malformed(number, "column layout")
        }

        // Mantissa with implied leading decimal point and a signed exponent, e.g. " 12345-4".
        private fun exponent(line: String, start: Int): Double {
            val mantissa = (line[start] + "." + line.substring(start + 1, start + 6)).toDoubleOrMalformed(1, "exponent field")
            return mantissa * 10.0.pow(int(line, start + 6, start + 8))
        }

        private fun double(line: String, start: Int, end: Int): Double =
            line.substring(start, end).toDoubleOrMalformed(line[0] - '0', "columns ${start + 1}-$end")

        private fun int(line: String, start: Int, end: Int): Int =
            line.substring(start, end).trim().toIntOrNull()
                ?: malformed(line[0] - '0', "columns ${start + 1}-$end")

        private fun String.toDoubleOrMalformed(number: Int, what: String): Double {
            val s = trim()
            // Kotlin's toDoubleOrNull also accepts NaN/Infinity and hex forms; TLE fields never contain letters.
            if (s.isEmpty() || s.any { it.isLetter() }) malformed(number, what)
            return s.toDoubleOrNull() ?: malformed(number, what)
        }

        private fun malformed(number: Int, what: String): Nothing =
            fail(TleParseException.Reason.MALFORMED, "line $number: malformed $what")

        private fun fail(reason: TleParseException.Reason, message: String): Nothing =
            throw TleParseException(reason, message)
    }
}

class TleParseException(val reason: Reason, message: String) : IllegalArgumentException(message) {
    enum class Reason { LINE_NUMBER, MALFORMED, CATALOG_MISMATCH, CHECKSUM }
}
