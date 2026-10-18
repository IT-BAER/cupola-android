package com.baer.cupola.tools.geoindex

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Rasterizes Natural Earth marine polygons into marine.bin (0.1 degree cells, run-length rows). */
object MarineRasterWriter {
    private const val MAGIC = 0x474D5231 // "GMR1"
    const val WIDTH = 3600
    const val HEIGHT = 1800
    private const val CELL = 360.0 / WIDTH

    class Polygon(val name: String, val parts: List<DoubleArray>) {
        /** Net area in square degrees (holes subtract); used to paint larger polygons first. */
        val area: Double = abs(parts.sumOf { ring ->
            var s = 0.0
            for (i in 0 until ring.size / 2 - 1) s += ring[2 * i] * ring[2 * i + 3] - ring[2 * i + 2] * ring[2 * i + 1]
            s / 2
        })
    }

    /** Returns the file size in bytes. */
    fun write(zip: File, outDir: File): Long {
        val polygons = readPolygons(zip)
        val names = polygons.map { it.name }.distinct()
        require(names.size <= 0xFFFF)
        val grid = rasterize(polygons, names)

        outDir.mkdirs()
        val target = File(outDir, "marine.bin")
        DataOutputStream(DeflaterOutputStream(target.outputStream().buffered(), Deflater(Deflater.BEST_COMPRESSION)).buffered()).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(WIDTH)
            out.writeInt(HEIGHT)
            out.writeShort(names.size)
            names.forEach { out.writeUTF(it) }
            for (r in 0 until HEIGHT) {
                val runs = ArrayList<IntArray>()
                var c = 0
                while (c < WIDTH) {
                    val v = grid[r * WIDTH + c]
                    var e = c + 1
                    while (e < WIDTH && grid[r * WIDTH + e] == v) e++
                    runs.add(intArrayOf(e - c, v.toInt()))
                    c = e
                }
                out.writeShort(runs.size)
                for (run in runs) {
                    out.writeShort(run[0])
                    out.writeShort(run[1])
                }
            }
        }
        return target.length()
    }

    /** Larger polygons are painted first so smaller nested ones (bays, straits) win where they overlap. */
    fun rasterize(polygons: List<Polygon>, names: List<String>): ShortArray {
        val grid = ShortArray(WIDTH * HEIGHT)
        val ordered = polygons.withIndex().sortedWith(compareByDescending<IndexedValue<Polygon>> { it.value.area }.thenBy { it.index })
        for ((_, poly) in ordered) fill(grid, poly, (names.indexOf(poly.name) + 1).toShort())
        return grid
    }

    // Even-odd scanline fill on cell centres: honours holes and multi-part records.
    private fun fill(grid: ShortArray, poly: Polygon, value: Short) {
        val crossings = HashMap<Int, ArrayList<Double>>()
        for (ring in poly.parts) {
            val n = ring.size / 2
            for (i in 0 until n - 1) {
                val x0 = ring[2 * i]; val y0 = ring[2 * i + 1]
                val x1 = ring[2 * i + 2]; val y1 = ring[2 * i + 3]
                if (y0 == y1) continue
                val ymin = minOf(y0, y1)
                val ymax = maxOf(y0, y1)
                val rFrom = maxOf(0, floor((90.0 - ymax) / CELL - 0.5).toInt() - 1)
                val rTo = minOf(HEIGHT - 1, ceil((90.0 - ymin) / CELL - 0.5).toInt() + 1)
                for (r in rFrom..rTo) {
                    val yc = 90.0 - (r + 0.5) * CELL
                    if (yc >= ymin && yc < ymax) {
                        crossings.getOrPut(r) { ArrayList() }.add(x0 + (yc - y0) * (x1 - x0) / (y1 - y0))
                    }
                }
            }
        }
        for ((r, xs) in crossings) {
            xs.sort()
            var k = 0
            while (k + 1 < xs.size) {
                val cFrom = maxOf(0, ceil((xs[k] + 180.0) / CELL - 0.5).toInt())
                val cTo = minOf(WIDTH - 1, ceil((xs[k + 1] + 180.0) / CELL - 0.5).toInt() - 1)
                for (c in cFrom..cTo) grid[r * WIDTH + c] = value
                k += 2
            }
        }
    }

    private fun readPolygons(zip: File): List<Polygon> {
        ZipFile(zip).use { zf ->
            fun bytes(ext: String): ByteArray {
                val e = zf.entries().asSequence().first { it.name.endsWith(ext) }
                return zf.getInputStream(e).readBytes()
            }
            val shp = ByteBuffer.wrap(bytes(".shp")).order(ByteOrder.LITTLE_ENDIAN)
            val (names, isRiver) = readNames(bytes(".dbf"))
            val out = ArrayList<Polygon>()
            var pos = 100
            var rec = 0
            while (pos + 8 <= shp.limit()) {
                val lenBytes = ByteBuffer.wrap(ByteArray(4).also { shp.get(pos + 4, it) }).order(ByteOrder.BIG_ENDIAN).int * 2
                val body = pos + 8
                val type = shp.getInt(body)
                if (type == 5) {
                    val numParts = shp.getInt(body + 36)
                    val numPoints = shp.getInt(body + 40)
                    val partsAt = body + 44
                    val ptsAt = partsAt + 4 * numParts
                    val starts = IntArray(numParts + 1) { if (it < numParts) shp.getInt(partsAt + 4 * it) else numPoints }
                    val parts = (0 until numParts).map { p ->
                        DoubleArray((starts[p + 1] - starts[p]) * 2) { j -> shp.getDouble(ptsAt + 16 * starts[p] + 8 * j) }
                    }
                    if (names[rec].isNotEmpty() && !isRiver[rec]) out.add(Polygon(names[rec], parts))
                }
                pos = body + lenBytes
                rec++
            }
            check(rec == names.size) { "${names.size} dbf records vs $rec shp records" }
            return out
        }
    }

    private val SMALL_WORDS = setOf("of", "the", "and", "de", "du", "la")

    private fun isAllCaps(s: String) = s.any { it.isLetter() } && s == s.uppercase()

    /** Mixed-case `name` as is; an all-caps name falls back to a mixed-case `name_en`, else is title-cased. */
    internal fun displayName(name: String, nameEn: String): String {
        if (!isAllCaps(name)) return name
        if (nameEn.isNotEmpty() && !isAllCaps(nameEn)) return nameEn
        return name.lowercase().split(" ").withIndex().joinToString(" ") { (i, w) ->
            if (i > 0 && w in SMALL_WORDS) w else w.replaceFirstChar { it.uppercase() }
        }
    }

    /** Record names (`name`, else `name_en`) and whether the record is a river, which is not a sea name. */
    private fun readNames(dbf: ByteArray): Pair<List<String>, List<Boolean>> {
        val b = ByteBuffer.wrap(dbf).order(ByteOrder.LITTLE_ENDIAN)
        val n = b.getInt(4)
        val headerLen = b.getShort(8).toInt() and 0xFFFF
        val recLen = b.getShort(10).toInt() and 0xFFFF
        class Field(val name: String, val offset: Int, val len: Int)
        val fields = ArrayList<Field>()
        var p = 32
        var off = 1
        while (dbf[p] != 0x0D.toByte()) {
            val nm = String(dbf, p, 11, Charsets.ISO_8859_1).substringBefore('\u0000')
            val len = dbf[p + 16].toInt() and 0xFF
            fields.add(Field(nm, off, len))
            off += len
            p += 32
        }
        val name = fields.first { it.name == "name" }
        val nameEn = fields.first { it.name == "name_en" }
        fun text(r: Int, f: Field) = String(dbf, headerLen + r * recLen + f.offset, f.len, Charsets.UTF_8).trim()
        val feature = fields.first { it.name == "featurecla" }
        return List(n) { r -> displayName(text(r, name).ifEmpty { text(r, nameEn) }, text(r, nameEn)) } to List(n) { r -> text(r, feature) == "river" }
    }
}
