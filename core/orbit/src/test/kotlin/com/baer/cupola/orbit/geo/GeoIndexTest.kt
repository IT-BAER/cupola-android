package com.baer.cupola.orbit.geo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoIndexTest {
    private val index by lazy { GeoIndex.load() }

    private fun city(lat: Double, lon: Double): GroundLabel.City {
        val label = index.label(lat, lon)
        assertTrue("expected City at $lat,$lon but was $label", label is GroundLabel.City)
        return label as GroundLabel.City
    }

    private fun assertCity(lat: Double, lon: Double, foldedName: String?, cc: String) {
        val c = city(lat, lon)
        if (foldedName != null) assertEquals(foldedName, GeoFold.fold(c.name))
        assertEquals(cc, c.countryCode)
        assertTrue("distance ${c.distanceKm}", c.distanceKm < 50.0)
    }

    @Test fun vienna() = assertCity(48.2082, 16.3738, "vienna", "AT")
    @Test fun saoPaulo() = assertCity(-23.5505, -46.6333, "sao paulo", "BR")
    @Test fun tokyo() = assertCity(35.6762, 139.6503, "tokyo", "JP")
    @Test fun sydney() = assertCity(-33.8688, 151.2093, "sydney", "AU")
    @Test fun reykjavik() = assertCity(64.1466, -21.9426, "reykjavik", "IS")
    @Test fun kawasakiIsNotTokyo() = assertCity(35.5309, 139.7029, "kawasaki", "JP")
    @Test fun nairobi() = assertCity(-1.2921, 36.8219, "nairobi", "KE")
    @Test fun lima() = assertCity(-12.0464, -77.0428, "lima", "PE")
    @Test fun anchorage() = assertCity(61.2181, -149.9003, "anchorage", "US")

    private fun assertSea(lat: Double, lon: Double, name: String) =
        assertEquals(GroundLabel.Sea(name), index.label(lat, lon))

    @Test fun northAtlantic() = assertSea(35.0, -40.0, "North Atlantic Ocean")
    @Test fun southPacific() = assertSea(-30.0, -130.0, "South Pacific Ocean")
    @Test fun indianOcean() = assertSea(-20.0, 80.0, "Indian Ocean")
    @Test fun ionianSea() = assertSea(35.0, 18.0, "Mediterranean Sea") // Natural Earth Ionian polygon spans only 36.5N-40.5N
    @Test fun caribbean() = assertSea(15.0, -75.0, "Caribbean Sea")
    @Test fun seaOfJapan() = assertSea(40.0, 135.0, "Sea of Japan")
    @Test fun arctic() = assertSea(85.0, 0.0, "Arctic Ocean")
    @Test fun southern() = assertSea(-62.0, 100.0, "Southern Ocean")

    private fun assertFar(lat: Double, lon: Double) {
        assertTrue(city(lat, lon).distanceKm > 50.0)
    }

    @Test fun sahara() = assertFar(23.0, 10.0)
    @Test fun rubAlKhali() = assertFar(20.0, 50.0)
    @Test fun gobi() = assertFar(43.0, 105.0)
    @Test fun greatVictoria() = assertFar(-28.0, 127.0)

    @Test fun searchWien() {
        val r = index.search("Wien", 5).first()
        assertEquals("Vienna", r.name)
        assertEquals("AT", r.countryCode)
    }

    @Test fun searchSaoPaulo() {
        for (q in listOf("Sao Paulo", "são paulo")) {
            val r = index.search(q, 5).first()
            assertEquals("sao paulo", GeoFold.fold(r.name))
            assertEquals("BR", r.countryCode)
        }
    }

    @Test fun searchMuenchen() {
        for (q in listOf("muenchen", "munchen")) {
            val r = index.search(q, 10).first()
            assertEquals("Munich", r.name) // GeoNames primary name; Muenchen/Munchen are aliases
            assertEquals("DE", r.countryCode)
        }
    }

    @Test fun searchWordPrefixAndLimit() {
        assertEquals(3, index.search("new", 3).size)
        assertTrue(index.search("york", 20).any { it.name == "New York City" })
        assertTrue(index.search("", 5).isEmpty())
    }

    @Test fun noAllCapsSeaNames() {
        val rnd = java.util.Random(7)
        repeat(20000) {
            val l = index.label(rnd.nextDouble() * 180 - 90, rnd.nextDouble() * 360 - 180)
            val n = when (l) { is GroundLabel.Sea -> l.name; is GroundLabel.City -> l.name }
            assertTrue(n, n != n.uppercase())
        }
    }

    @Test fun resourcesWithinBudget() {
        val dir = File("src/main/resources/geo")
        val files = dir.listFiles()
        assertNotNull(files)
        val total = files!!.sumOf { it.length() }
        assertTrue("geo resources $total bytes", total <= 2L * 1024 * 1024)
        assertTrue(File(dir, "NOTICE.txt").readText().let { "GeoNames" in it && "Natural Earth" in it })
    }

    @Test fun nearestLookupSpeed() {
        val rnd = java.util.Random(1)
        repeat(2000) { index.label(rnd.nextDouble() * 180 - 90, rnd.nextDouble() * 360 - 180) }
        val n = 20000
        val t0 = System.nanoTime()
        repeat(n) { index.label(rnd.nextDouble() * 180 - 90, rnd.nextDouble() * 360 - 180) }
        val ms = (System.nanoTime() - t0) / 1e6 / n
        println("label ms/call = $ms")
        assertTrue("label $ms ms/call", ms < 1.0)
    }
}
