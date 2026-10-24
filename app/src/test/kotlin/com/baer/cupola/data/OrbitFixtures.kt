package com.baer.cupola.data

import com.baer.cupola.orbit.tle.Tle

/** The bundled-style TLE of 2026-10-18 (epoch 26291.46721112) and variants with recomputed checksums. */
object OrbitFixtures {
    const val LINE1 = "1 25544U 98067A   26291.46721112  .00007452  00000+0  14361-3 0  9999"
    const val LINE2 = "2 25544  51.6327  50.0298 0006784 279.2532  80.7689 15.48923373590866"
    val BUNDLED_TLE = "ISS (ZARYA)\n$LINE1\n$LINE2\n"
    val BUNDLED_JSON = json(Tle.parse(LINE1, LINE2))

    private fun withChecksum(line: String) = line.take(68) + Tle.checksum(line)

    /** [epoch] is the 14-column TLE epoch field (YYDDD.DDDDDDDD); [inclination] the 8-column field. */
    fun tle(epoch: String = "26297.50000000", inclination: String = " 51.6327"): String {
        val l1 = withChecksum(LINE1.replaceRange(18, 32, epoch))
        val l2 = withChecksum(LINE2.replaceRange(8, 16, inclination))
        return "ISS (ZARYA)\n$l1\n$l2\n"
    }

    fun json(tle: Tle, retired: Boolean = false) =
        "{\"epoch\": \"${tle.epoch}\", \"source\": \"test\", \"fetched\": \"2026-10-24T12:00:00Z\", \"catalog\": 25544" +
            (if (retired) ", \"retired\": true" else "") + "}\n"

    fun jsonFor(tleText: String): String {
        val rows = tleText.lines()
        return json(Tle.parse(rows[1], rows[2]))
    }

    /** Flips the line 2 checksum digit. */
    fun badChecksum(tleText: String): String {
        val rows = tleText.lines().toMutableList()
        val c = rows[2].last()
        rows[2] = rows[2].dropLast(1) + ((c - '0' + 1) % 10)
        return rows.joinToString("\n")
    }
}
