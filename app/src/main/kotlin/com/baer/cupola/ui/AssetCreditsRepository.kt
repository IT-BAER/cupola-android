package com.baer.cupola.ui

import com.baer.cupola.feature.settings.Credits
import com.baer.cupola.feature.settings.CreditsParser
import com.baer.cupola.feature.settings.CreditsRepository
import com.baer.cupola.feature.settings.Notice
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * [openAsset] resolves an asset path; [openResource] a classpath resource (the geo index notice ships
 * inside `:core:orbit`). `notices/python-sgp4.txt` is a copy of core/orbit/NOTICE-python-sgp4.txt.
 */
class AssetCreditsRepository(
    private val openAsset: (String) -> InputStream,
    private val openResource: (String) -> InputStream?,
) : CreditsRepository {
    private fun read(stream: InputStream) = stream.use { it.readBytes().decodeToString() }

    override suspend fun load(): Credits = withContext(Dispatchers.IO) {
        Credits(
            rows = CreditsParser.parseRows(read(openAsset("credits.tsv"))),
            notices = listOfNotNull(
                Notice("python-sgp4 (MIT)", read(openAsset("notices/python-sgp4.txt")).trim()),
                openResource("/geo/NOTICE.txt")?.let { Notice("GeoNames and Natural Earth", read(it).trim()) },
                runCatching { read(openAsset(LIBRARIES_ASSET)) }.getOrNull()?.let(::librariesNotice),
            ),
        )
    }

    /** One line per library, then each licence once with its URL. */
    private fun librariesNotice(json: String): Notice {
        val artifacts = JSONArray(json)
        val lines = ArrayList<String>(artifacts.length())
        val licences = sortedMapOf<String, String>()
        for (i in 0 until artifacts.length()) {
            val a = artifacts.getJSONObject(i)
            val names = ArrayList<String>()
            for (key in listOf("spdxLicenses", "unknownLicenses")) {
                val list = a.optJSONArray(key) ?: continue
                for (j in 0 until list.length()) {
                    val l = list.getJSONObject(j)
                    val name = l.optString("name").ifEmpty { l.optString("identifier") }
                    names += name
                    l.optString("url").takeIf { it.isNotEmpty() }?.let { licences[name] = it }
                }
            }
            val artifact = a.getString("artifactId")
            lines += "${a.optString("name").ifEmpty { artifact }} ${a.getString("version")} " +
                "(${a.getString("groupId")}:$artifact): ${names.joinToString()}"
        }
        val text = lines.sorted().joinToString("\n") + "\n\n" + licences.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        return Notice("Open-source libraries", text)
    }

    companion object {
        /** Written by the Licensee Gradle plugin at build time (app/build.gradle.kts). */
        const val LIBRARIES_ASSET = "app/cash/licensee/artifacts.json"
    }
}
