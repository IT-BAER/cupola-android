package com.baer.cupola.data

import com.baer.cupola.data.OrbitFixtures.BUNDLED_JSON
import com.baer.cupola.data.OrbitFixtures.BUNDLED_TLE
import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitRepositoryTest {
    private val dir = File.createTempFile("cupola-orbit", "").apply { delete(); mkdirs() }
    private val now = Instant.parse("2026-10-24T13:00:00Z")
    private val store = DownloadedOrbitStore(dir)
    private val bundledEpoch = Instant.parse("2026-10-18T11:12:47.040768Z")
    private val freshEpoch = Instant.parse("2026-10-24T12:00:00Z")

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repository() = OrbitRepository({ BUNDLED_TLE to BUNDLED_JSON }, store) { now }

    @Test
    fun parsesBundledMetadata() {
        val json = File("src/main/assets/orbit/iss.json").readText()
        val m = OrbitMetadataParser.parse(json)
        assertEquals("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE", m.source)
        assertFalse(m.retired)
    }

    @Test
    fun retiredFlagIsRead() {
        val m = OrbitMetadataParser.parse(
            """{"epoch": "2030-01-01T00:00:00Z", "source": "s", "fetched": "2030-01-02T00:00:00Z", "retired": true}""",
        )
        assertTrue(m.retired)
    }

    @Test
    fun repositoryReadsBundledFiles() = runBlocking {
        val assets = OrbitRepository(
            { File("src/main/assets/orbit/iss.tle").readText() to File("src/main/assets/orbit/iss.json").readText() }, store,
        ) { now }
        assertEquals(25544, assets.tle().catalogNumber)
        assertFalse(assets.metadata().retired)
    }

    @Test
    fun withoutADownloadTheBundledDataIsUsed() = runBlocking {
        assertEquals(bundledEpoch, repository().tle().epoch)
        assertEquals(bundledEpoch, repository().metadata().epoch)
    }

    @Test
    fun aNewerValidDownloadWins() = runBlocking {
        val fresh = OrbitFixtures.tle()
        store.write(fresh, OrbitFixtures.jsonFor(fresh))
        val r = repository()
        assertEquals(freshEpoch, r.tle().epoch)
        assertEquals(freshEpoch, r.metadata().epoch)
        assertEquals("test", r.metadata().source)
    }

    @Test
    fun anOlderDownloadLosesToTheBundledData() {
        val old = OrbitFixtures.tle(epoch = "26280.50000000")
        store.write(old, OrbitFixtures.jsonFor(old))
        assertEquals(bundledEpoch, repository().current().tle.epoch)
    }

    @Test
    fun aCorruptDownloadIsIgnored() {
        val fresh = OrbitFixtures.tle()
        store.write(OrbitFixtures.badChecksum(fresh), OrbitFixtures.jsonFor(fresh))
        assertEquals(bundledEpoch, repository().current().tle.epoch)
        File(dir, "iss.tle").writeText("garbage")
        assertEquals(bundledEpoch, repository().current().tle.epoch)
    }

    @Test
    fun aHalfReplacedPairIsIgnored() {
        // A crash between the two renames leaves the new json next to the old TLE.
        val older = OrbitFixtures.tle(epoch = "26295.50000000")
        val fresh = OrbitFixtures.tle()
        store.write(older, OrbitFixtures.jsonFor(older))
        File(dir, "iss.json").writeText(OrbitFixtures.jsonFor(fresh))
        assertEquals(bundledEpoch, repository().current().tle.epoch)
    }

    @Test
    fun writesReplaceBothFilesAndLeaveNoTempFiles() {
        val older = OrbitFixtures.tle(epoch = "26295.50000000")
        val fresh = OrbitFixtures.tle()
        store.write(older, OrbitFixtures.jsonFor(older))
        store.write(fresh, OrbitFixtures.jsonFor(fresh))
        assertEquals(fresh to OrbitFixtures.jsonFor(fresh), store.read())
        assertEquals(setOf("iss.tle", "iss.json"), dir.list()!!.toSet())
    }

    @Test
    fun offerSwapsTheCurrentDataOnlyForNewerValidData() {
        val r = repository()
        val fresh = OrbitFixtures.tle()
        assertFalse(r.offer(BUNDLED_TLE, BUNDLED_JSON))
        assertTrue(r.offer(fresh, OrbitFixtures.jsonFor(fresh)))
        assertEquals(freshEpoch, r.current().tle.epoch)
        assertEquals(freshEpoch, r.current().model.epoch)
        val older = OrbitFixtures.tle(epoch = "26295.50000000")
        assertFalse(r.offer(older, OrbitFixtures.jsonFor(older)))
        assertEquals(freshEpoch, r.current().tle.epoch)
        assertEquals(fresh, store.read()?.first)
    }
}
