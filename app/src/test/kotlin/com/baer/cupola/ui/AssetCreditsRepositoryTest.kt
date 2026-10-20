package com.baer.cupola.ui

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetCreditsRepositoryTest {
    private val repo = AssetCreditsRepository(
        openAsset = { File("src/main/assets/$it").inputStream() },
        openResource = { com.baer.cupola.orbit.geo.GeoIndex::class.java.getResourceAsStream(it) },
    )

    @Test
    fun loadsEveryRowAndTheBundledNotices() = runBlocking {
        val credits = repo.load()
        val dataLines = File("src/main/assets/credits.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
        assertEquals(dataLines.size, credits.rows.size)
        assertEquals(dataLines.map { it.substringBefore('\t') }, credits.rows.map { it.work })
        assertTrue(credits.notices.any { it.text.contains("The MIT License") })
        assertTrue(credits.notices.any { it.text.contains("GeoNames") && it.text.contains("Natural Earth") })
    }

    @Test
    fun noNasaOrEsaLogoAssetsAreBundled() {
        val names = File("src/main/assets").walkTopDown().map { it.name.lowercase() }.toList()
        assertTrue(names.none { it.contains("logo") })
    }
}
