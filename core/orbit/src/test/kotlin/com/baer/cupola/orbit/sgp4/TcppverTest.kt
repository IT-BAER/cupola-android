package com.baer.cupola.orbit.sgp4

import com.baer.cupola.orbit.tle.Tle
import com.baer.cupola.orbit.tle.TleParseException
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors python-sgp4 tests.py run_satellite_against_tcppver for the near-Earth subset. */
class TcppverTest {

    private class Block(val catalogNumber: Int, val lines: List<String>)

    private fun resource(name: String): List<String> =
        javaClass.getResourceAsStream("/sgp4/$name")!!.bufferedReader(Charsets.US_ASCII).readLines()

    private fun expectedBlocks(): List<Block> {
        val blocks = mutableListOf<Block>()
        var current: MutableList<String>? = null
        for (line in resource("tcppver.out")) {
            if (line.endsWith(" xx")) {
                current = mutableListOf()
                blocks += Block(line.removeSuffix(" xx").trim().toInt(), current)
            } else {
                current!! += line
            }
        }
        return blocks
    }

    private fun format(tsince: Double, r: Vector3, v: Vector3): List<Double> {
        fun f(value: Double, decimals: Int) = String.format(Locale.ROOT, "%.${decimals}f", value).toDouble()
        return listOf(f(tsince, 8), f(r.x, 8), f(r.y, 8), f(r.z, 8), f(v.x, 9), f(v.y, 9), f(v.z, 9))
    }

    @Test
    fun nearEarthCasesMatchTcppverAndDeepSpaceIsRejected() {
        val tleLines = resource("SGP4-VER.TLE")
        val blocks = expectedBlocks()
        val checksumFailures = mutableListOf<String>()
        val errors = mutableListOf<Pair<Int, Int>>()
        var nearEarth = 0
        var deepSpace = 0
        var linesCompared = 0
        var maxDeviation = 0.0
        var index = 0

        for ((i, line1) in tleLines.withIndex()) {
            if (!line1.startsWith("1")) continue
            val line2 = tleLines[i + 1]
            val block = blocks[index++]
            val tle = try {
                Tle.parse(line1, line2.take(69))
            } catch (e: TleParseException) {
                assertEquals(TleParseException.Reason.CHECKSUM, e.reason)
                checksumFailures += "${block.catalogNumber}: ${e.message}"
                Tle.parse(line1, line2.take(69), verifyChecksum = false)
            }
            assertEquals(block.catalogNumber, tle.catalogNumber)

            val created = Sgp4.create(tle, Gravity.WGS72)
            if (1440.0 / tle.meanMotionRevPerDay >= 225.0) {
                assertTrue("${tle.catalogNumber} must be rejected", created is Sgp4CreateResult.DeepSpaceNotSupported)
                deepSpace++
                continue
            }
            val sgp4 = (created as? Sgp4CreateResult.Ready)?.propagator
                ?: throw AssertionError("${tle.catalogNumber} is near-Earth but was rejected")
            nearEarth++

            val actual = mutableListOf<List<Double>>()
            fun step(tsince: Double): Boolean = when (val result = sgp4.propagate(tsince)) {
                is PropagationResult.Success -> {
                    actual += format(tsince, result.positionKm, result.velocityKmPerSec)
                    true
                }
                is PropagationResult.Failure -> {
                    errors += tle.catalogNumber to result.error.code
                    false
                }
            }

            assertTrue("${tle.catalogNumber}: epoch propagation failed", step(0.0))
            val (tstart, tend, tstep) = line2.substring(69).trim().split(Regex("\\s+")).map { it.toDouble() }
            var tsince = tstart
            var ok = true
            while (tsince <= tend) {
                if (tsince == 0.0 && tstart == 0.0) {
                    tsince += tstep
                    continue
                }
                ok = step(tsince)
                if (!ok) break
                tsince += tstep
            }
            if (ok && tsince - tend < tstep - 1e-6) step(tend)

            assertEquals("${tle.catalogNumber}: line count", block.lines.size, actual.size)
            for ((n, expectedLine) in block.lines.withIndex()) {
                val expected = expectedLine.trim().split(Regex("\\s+")).take(7).map { it.toDouble() }
                for (k in 0 until 7) {
                    val delta = actual[n][k] - expected[k]
                    maxDeviation = maxOf(maxDeviation, kotlin.math.abs(delta))
                    assertTrue(
                        "${tle.catalogNumber} line ${n + 1} field ${k + 1}: expected ${expected[k]} got ${actual[n][k]}",
                        -2e-7 < delta && delta < 2e-7,
                    )
                }
                linesCompared++
            }
        }

        assertEquals(blocks.size, index)
        // tests.py expects [1,1,6,6,4,3,6] over all satellites; the near-Earth ones come first in file order.
        assertEquals(listOf(22312 to 1, 28350 to 1, 28872 to 6, 29141 to 6), errors)
        println("near-Earth satellites compared: $nearEarth")
        println("lines compared: $linesCompared")
        println("max deviation: $maxDeviation")
        println("deep-space satellites rejected: $deepSpace")
        println("checksum failures (lenient parse): $checksumFailures")
    }
}
