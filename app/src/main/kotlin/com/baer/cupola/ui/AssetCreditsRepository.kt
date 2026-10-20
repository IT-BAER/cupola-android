package com.baer.cupola.ui

import com.baer.cupola.feature.settings.Credits
import com.baer.cupola.feature.settings.CreditsParser
import com.baer.cupola.feature.settings.CreditsRepository
import com.baer.cupola.feature.settings.Notice
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            ),
        )
    }
}
