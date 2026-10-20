package com.baer.cupola.data

import java.io.File
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetTleRepositoryTest {
    @Test
    fun parsesBundledMetadata() {
        val json = File("src/main/assets/orbit/iss.json").readText()
        val m = OrbitMetadataParser.parse(json)
        assertEquals(Instant.parse("2026-10-18T11:12:47.040768Z"), m.epoch)
        assertEquals("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE", m.source)
        assertEquals(Instant.parse("2026-10-18T17:33:31.153136947Z"), m.fetched)
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
        val repo = AssetTleRepository { name -> File("src/main/assets/$name").inputStream() }
        assertEquals(25544, repo.tle().catalogNumber)
        assertFalse(repo.metadata().retired)
    }
}
