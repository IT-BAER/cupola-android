package com.baer.cupola.orbit

import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import com.baer.cupola.orbit.frames.dot
import com.baer.cupola.orbit.frames.minus
import com.baer.cupola.orbit.frames.norm
import com.baer.cupola.orbit.sgp4.Vector3
import com.baer.cupola.orbit.tle.Tle
import java.time.Duration
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassPredictorTest {

    private val tle = Tle.parse(
        "1 25544U 98067A   26291.14461894  .00007503  00000+0  14456-3 0  9994",
        "2 25544  51.6328  51.6259 0006780 277.7856  82.2363 15.48918166590814",
    )
    private val model = OrbitModel(tle)
    private val predictor = PassPredictor(model)
    private val cities = listOf(
        "Vienna" to (48.2082 to 16.3738),
        "Sao Paulo" to (-23.5505 to -46.6333),
        "Tokyo" to (35.6762 to 139.6503),
        "Anchorage" to (61.2181 to -149.9003),
        "Nairobi" to (-1.2921 to 36.8219),
    )

    private class Brute(val start: Int, val end: Int, val closest: Int)

    private fun elevationDeg(sat: Vector3, latDeg: Double, lonDeg: Double): Double {
        val lat = Math.toRadians(latDeg)
        val lon = Math.toRadians(lonDeg)
        val city = Frames.geodeticToEcef(Geodetic(lat, lon, 0.0))
        val up = Vector3(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
        val d = sat - city
        return Math.toDegrees(asin(((d dot up) / d.norm()).coerceIn(-1.0, 1.0)))
    }

    @Test
    fun matchesOneSecondBruteForceOverSevenDays() {
        val t0 = tle.epoch.minusSeconds(3600)
        val n = 3600 + 7 * 86400
        val track = Array(n + 1) { model.ecefPositionKm(t0.plusSeconds(it.toLong()))!! }
        val limit = tle.epoch.plus(Duration.ofDays(7))
        var maxDiffS = 0.0
        for ((name, c) in cities) {
            val (lat, lon) = c
            val city = Frames.geodeticToEcef(Geodetic(Math.toRadians(lat), Math.toRadians(lon), 0.0))
            val brute = ArrayList<Brute>()
            var i = 0
            while (i <= n) {
                if (elevationDeg(track[i], lat, lon) < 10.0) {
                    i++
                    continue
                }
                val s = i
                var best = i
                while (i <= n && elevationDeg(track[i], lat, lon) >= 10.0) {
                    if ((track[i] - city).norm() < (track[best] - city).norm()) best = i
                    i++
                }
                if (i <= n) brute += Brute(s, i - 1, best)
            }
            val expected = brute.filter {
                t0.plusSeconds(it.end.toLong()) >= tle.epoch && t0.plusSeconds(it.start.toLong()) < limit
            }

            val passes = ArrayList<Pass>()
            var after = tle.epoch
            while (true) {
                val p = predictor.nextPass(lat, lon, after) ?: break
                if (p.start >= limit) break
                passes += p
                after = p.end.plusSeconds(1)
            }
            println("P1.8 $name passes=${passes.size} brute=${expected.size}")
            assertEquals(name, expected.size, passes.size)
            assertTrue(name, passes.size > 10)
            var cityMax = 0.0
            for ((p, b) in passes.zip(expected)) {
                val ds = abs(Duration.between(t0.plusSeconds(b.start.toLong()), p.start).toNanos() / 1e9)
                val de = abs(Duration.between(t0.plusSeconds(b.end.toLong()), p.end).toNanos() / 1e9)
                val dc = abs(Duration.between(t0.plusSeconds(b.closest.toLong()), p.closestApproach).toNanos() / 1e9)
                cityMax = maxOf(cityMax, ds, de, dc)
                assertTrue("$name start $ds", ds <= 1.0)
                assertTrue("$name end $de", de <= 1.0)
                assertTrue("$name closest $dc", dc <= 1.0)
                assertTrue(p.start <= p.closestApproach && p.closestApproach <= p.end)
                assertTrue(p.maxElevationDeg >= 10.0)
            }
            println("P1.8 $name largest difference %.3f s".format(cityMax))
            maxDiffS = maxOf(maxDiffS, cityMax)
        }
        println("P1.8 largest difference overall %.3f s".format(maxDiffS))
    }

    @Test
    fun inProgressPassKeepsTrueStart() {
        val p = predictor.nextPass(48.2082, 16.3738, tle.epoch)!!
        val mid = p.start.plus(Duration.between(p.start, p.end).dividedBy(2))
        val q = predictor.nextPass(48.2082, 16.3738, mid)!!
        assertTrue(abs(Duration.between(p.start, q.start).toMillis()) <= 2)
        assertTrue(q.start < mid)
    }

    @Test
    fun findsPassFarAfterFreezeAge() {
        val after = tle.epoch.plus(Duration.ofDays(400))
        val p = predictor.nextPass(48.2082, 16.3738, after)
        assertNotNull(p)
        assertTrue(p!!.end >= after && p.start < after.plus(Duration.ofDays(7)))
    }
}
