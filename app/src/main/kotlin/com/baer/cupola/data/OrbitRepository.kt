package com.baer.cupola.data

import android.util.Log
import com.baer.cupola.feature.settings.OrbitMetadata
import com.baer.cupola.feature.settings.TleRepository
import com.baer.cupola.orbit.OrbitModel
import com.baer.cupola.orbit.tle.IssTle
import com.baer.cupola.orbit.tle.Tle
import com.baer.cupola.orbit.tle.TleParseException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One TLE with its `iss.json` metadata and the orbit model built from it. */
class OrbitData(val tle: Tle, val metadata: OrbitMetadata) {
    val model = OrbitModel(tle)
}

/** Downloaded `iss.tle` and `iss.json` in [dir]; every file is replaced atomically (temp file, fsync, rename). */
class DownloadedOrbitStore(private val dir: File) {
    /** The stored TLE and json text, null when either is missing or unreadable. */
    fun read(): Pair<String, String>? = try {
        val tle = File(dir, TLE)
        val json = File(dir, JSON)
        if (tle.isFile && json.isFile) tle.readText() to json.readText() else null
    } catch (e: IOException) {
        null
    }

    /** The json goes first: a crash between the renames leaves an epoch mismatch, which [OrbitValidation] rejects. */
    fun write(tleText: String, jsonText: String) {
        replace(JSON, jsonText)
        replace(TLE, tleText)
    }

    /** Earliest time of the next download attempt; null when never set or unreadable. */
    fun nextAttempt(): Instant? = try {
        File(dir, NEXT_ATTEMPT).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()?.let(Instant::ofEpochMilli)
    } catch (e: IOException) {
        null
    }

    fun setNextAttempt(at: Instant) = replace(NEXT_ATTEMPT, at.toEpochMilli().toString())

    private fun replace(name: String, text: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use {
            it.write(text.toByteArray())
            it.fd.sync()
        }
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private companion object {
        const val TLE = "iss.tle"
        const val JSON = "iss.json"
        const val NEXT_ATTEMPT = "next-attempt"
    }
}

object OrbitValidation {
    private val maxAhead = Duration.ofDays(1)

    /**
     * The downloaded pair when it is a plausible ISS element set ([IssTle.problems], checksums) with an epoch after
     * [inUse], at most a day after [now], and a json epoch equal to the TLE epoch; else null.
     */
    fun accept(tleText: String, jsonText: String, inUse: Instant, now: Instant): OrbitData? {
        val (l1, l2) = IssTle.elementLines(tleText) ?: return null
        val tle = try {
            Tle.parse(l1, l2)
        } catch (e: TleParseException) {
            return null
        }
        if (IssTle.problems(tle).isNotEmpty() || tle.epoch <= inUse || tle.epoch > now.plus(maxAhead)) return null
        val metadata = try {
            OrbitMetadataParser.parse(jsonText)
        } catch (e: RuntimeException) {
            return null
        }
        return if (metadata.epoch == tle.epoch) OrbitData(tle, metadata) else null
    }
}

/**
 * The orbit in use: the newest valid of the bundled `orbit/iss.tle` + `iss.json` ([bundled] returns both texts) and
 * the downloaded pair in [store]. [offer] swaps it at runtime; readers see the new data on their next [current].
 */
class OrbitRepository(
    private val bundled: () -> Pair<String, String>,
    private val store: DownloadedOrbitStore,
    private val now: () -> Instant,
) : TleRepository {
    @Volatile private var data: OrbitData? = null

    fun current(): OrbitData = data ?: load()

    @Synchronized
    private fun load(): OrbitData = data ?: run {
        val (tleText, jsonText) = bundled()
        val (l1, l2) = requireNotNull(IssTle.elementLines(tleText)) { "bundled iss.tle has no element lines" }
        val base = OrbitData(Tle.parse(l1, l2), OrbitMetadataParser.parse(jsonText))
        val downloaded = store.read()?.let { (tle, json) -> OrbitValidation.accept(tle, json, base.tle.epoch, now()) }
        (downloaded ?: base).also { data = it }
    }

    /** Stores and switches to [tleText] + [jsonText] when they validate against the data in use; false keeps it. */
    @Synchronized
    fun offer(tleText: String, jsonText: String): Boolean {
        val next = OrbitValidation.accept(tleText, jsonText, current().tle.epoch, now()) ?: return false
        try {
            store.write(tleText, jsonText)
        } catch (e: IOException) {
            Log.w("Cupola", "orbit data not stored", e)
            return false
        }
        data = next
        return true
    }

    override suspend fun metadata(): OrbitMetadata = withContext(Dispatchers.IO) { current().metadata }

    override suspend fun tle(): Tle = withContext(Dispatchers.IO) { current().tle }
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
