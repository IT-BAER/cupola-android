package com.baer.cupola.data

import com.baer.cupola.feature.settings.OrbitMetadata
import com.baer.cupola.feature.settings.TleRepository
import com.baer.cupola.orbit.tle.Tle
import java.io.InputStream
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the bundled `orbit/iss.tle` and `orbit/iss.json`; [open] resolves an asset path such as `orbit/iss.tle`. */
class AssetTleRepository(private val open: (String) -> InputStream) : TleRepository {
    private fun read(path: String): String = open(path).use { it.readBytes().decodeToString() }

    override suspend fun metadata(): OrbitMetadata =
        withContext(Dispatchers.IO) { OrbitMetadataParser.parse(read("orbit/iss.json")) }

    override suspend fun tle(): Tle = withContext(Dispatchers.IO) {
        val lines = read("orbit/iss.tle").lines().map { it.trimEnd() }.filter { it.isNotEmpty() }
        val l1 = lines.first { it.startsWith("1 ") }
        val l2 = lines.first { it.startsWith("2 ") }
        Tle.parse(l1, l2)
    }
}

/** Flat JSON with string fields and an optional boolean `retired` (decision 38); no JSON library is needed. */
object OrbitMetadataParser {
    fun parse(json: String): OrbitMetadata {
        fun string(key: String): String = Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("iss.json has no \"$key\" field")
        val retired = Regex(""""retired"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1) == "true"
        return OrbitMetadata(Instant.parse(string("epoch")), string("source"), Instant.parse(string("fetched")), retired)
    }
}
