package com.baer.cupola.tools.geoindex

import com.baer.cupola.orbit.geo.GeoFold
import java.io.DataOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/** Writes cities.bin from a GeoNames cities15000 dump; layout is read by `GeoIndex.load()`. */
object CityIndexWriter {
    private const val MAGIC = 0x47435431 // "GCT1"
    private const val FIXED_SCALE = 1e4

    /** Historical, abandoned or destroyed places are dropped everywhere. */
    private val HISTORICAL = setOf("PPLH", "PPLQ", "PPLW", "PPLCH")

    private class Row(
        val id: Int,
        val name: String,
        val ascii: String,
        val alternates: String,
        val lat: Double,
        val lon: Double,
        val feature: String,
        val cc: String,
        val pop: Int,
    )

    /** Returns (file bytes, city count, alias count). */
    fun write(zip: File, outDir: File): Triple<Long, Int, Int> {
        val rows = ZipFile(zip).use { zf ->
            val entry = zf.entries().asSequence().first { it.name.endsWith(".txt") }
            zf.getInputStream(entry).bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.filter { it.isNotBlank() }.map { line ->
                    val c = line.split('\t')
                    Row(c[0].toInt(), c[1], c[2], c[3], c[4].toDouble(), c[5].toDouble(), c[7], c[8], c[14].toInt())
                }.filter { it.feature !in HISTORICAL }.toList()
            }
        }.sortedWith(compareByDescending<Row> { it.pop }.thenBy { it.id })

        outDir.mkdirs()
        val target = File(outDir, "cities.bin")
        var aliasTotal = 0
        DataOutputStream(DeflaterOutputStream(target.outputStream().buffered(), Deflater(Deflater.BEST_COMPRESSION)).buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(rows.size)
            for (r in rows) {
                out.writeUTF(r.name)
                require(r.cc.length == 2 && r.cc.all { it.code < 128 }) { "country code ${r.cc} of ${r.id}" }
                out.writeByte(r.cc[0].code)
                out.writeByte(r.cc[1].code)
                out.writeInt((r.lat * FIXED_SCALE).roundToInt())
                out.writeInt((r.lon * FIXED_SCALE).roundToInt())
                out.writeInt(r.pop)
                // Bit 0: section of a populated place (PPLX), searchable but never a ground label.
                out.writeByte(if (r.feature == "PPLX") 1 else 0)
                val aliases = aliasesOf(r)
                aliasTotal += aliases.size
                out.writeShort(aliases.size)
                aliases.forEach { out.writeUTF(it) }
            }
        }
        return Triple(target.length(), rows.size, aliasTotal)
    }

    /** Folded Latin-script alternate names (plus the ASCII name), deduplicated, excluding the folded primary name. */
    private fun aliasesOf(r: Row): List<String> {
        val primary = GeoFold.fold(r.name)
        val seen = LinkedHashSet<String>()
        for (candidate in sequenceOf(r.ascii) + r.alternates.split(',').asSequence()) {
            if (!isLatin(candidate)) continue
            val key = GeoFold.fold(candidate)
            if (key.isNotEmpty() && key != primary) seen.add(key)
        }
        return seen.toList()
    }

    private fun isLatin(s: String): Boolean = s.all {
        !Character.isLetter(it) || Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN
    }
}
