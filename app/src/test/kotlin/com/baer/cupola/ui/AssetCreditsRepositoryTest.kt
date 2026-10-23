package com.baer.cupola.ui

import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric for the platform org.json behind the library list.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssetCreditsRepositoryTest {
    private val licensee = """
        [{"groupId":"androidx.activity","artifactId":"activity","version":"1.13.0","name":"Activity",
          "spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"}]},
         {"groupId":"androidx.datastore","artifactId":"datastore-preferences-external-protobuf","version":"1.2.1",
          "spdxLicenses":[{"identifier":"BSD-3-Clause","name":"BSD 3-Clause \"New\" or \"Revised\" License","url":"https://opensource.org/licenses/BSD-3-Clause"}]}]
    """.trimIndent()

    private fun repo(libraries: String?) = AssetCreditsRepository(
        openAsset = { path ->
            if (path == AssetCreditsRepository.LIBRARIES_ASSET) {
                libraries?.byteInputStream() ?: throw FileNotFoundException(path)
            } else {
                File("src/main/assets/$path").inputStream()
            }
        },
        openResource = { com.baer.cupola.orbit.geo.GeoIndex::class.java.getResourceAsStream(it) },
    )

    @Test
    fun loadsEveryRowAndTheBundledNotices() = runBlocking {
        val credits = repo(licensee).load()
        val dataLines = File("src/main/assets/credits.tsv").readLines().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1)
        assertEquals(dataLines.size, credits.rows.size)
        assertEquals(dataLines.map { it.substringBefore('\t') }, credits.rows.map { it.work })
        assertTrue(credits.notices.any { it.text.contains("The MIT License") })
        assertTrue(credits.notices.any { it.text.contains("GeoNames") && it.text.contains("Natural Earth") })
    }

    @Test
    fun listsEveryBundledLibraryWithItsLicence() = runBlocking {
        val notice = repo(licensee).load().notices.single { it.title == "Open-source libraries" }
        assertTrue(notice.text, "Activity 1.13.0 (androidx.activity:activity): Apache License 2.0" in notice.text)
        assertTrue(notice.text, "datastore-preferences-external-protobuf 1.2.1 (androidx.datastore:datastore-preferences-external-protobuf): BSD 3-Clause" in notice.text)
        assertTrue(notice.text, "Apache License 2.0: https://www.apache.org/licenses/LICENSE-2.0" in notice.text)
        assertTrue(notice.text, "https://opensource.org/licenses/BSD-3-Clause" in notice.text)
    }

    @Test
    fun noLibraryNoticeWithoutTheGeneratedList() = runBlocking {
        assertNull(repo(null).load().notices.firstOrNull { it.title == "Open-source libraries" })
    }

    @Test
    fun noNasaOrEsaLogoAssetsAreBundled() {
        val names = File("src/main/assets").walkTopDown().map { it.name.lowercase() }.toList()
        assertTrue(names.none { it.contains("logo") })
    }
}
