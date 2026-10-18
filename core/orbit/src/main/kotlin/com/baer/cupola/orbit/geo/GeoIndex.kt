package com.baer.cupola.orbit.geo

import java.io.DataInputStream
import java.util.BitSet
import java.util.zip.InflaterInputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline ground-point labels and city search. Resources are written by `:tools:geoindex`.
 * Immutable after [load]; safe for concurrent reads.
 */
class GeoIndex private constructor(
    private val cities: Array<City>,
    private val keys: Array<String>,
    private val keyCity: IntArray,
    private val sections: BooleanArray,
    private val marine: MarineRaster,
) {
    private val cellStart: IntArray
    private val cellCities: IntArray
    private val entries: Array<Long>

    init {
        val counts = IntArray(GRID_ROWS * GRID_COLS + 1)
        for ((i, c) in cities.withIndex()) if (!sections[i]) counts[cell(c.latDeg, c.lonDeg) + 1]++
        for (i in 1 until counts.size) counts[i] += counts[i - 1]
        cellStart = counts
        val fill = counts.copyOf()
        cellCities = IntArray(counts.last())
        for ((i, c) in cities.withIndex()) if (!sections[i]) cellCities[fill[cell(c.latDeg, c.lonDeg)]++] = i

        // One search entry per word start of every key, encoded as keyIndex shl 8 or offset.
        val list = ArrayList<Long>(keys.size * 2)
        for ((k, key) in keys.withIndex()) {
            var off = 0
            while (off < key.length && off < 255) {
                list.add((k.toLong() shl 8) or off.toLong())
                val sp = key.indexOf(' ', off)
                if (sp < 0) break
                off = sp + 1
            }
        }
        entries = list.toTypedArray()
        entries.sortWith { a, b -> compareEntries(a, b) }
    }

    private fun compareEntries(a: Long, b: Long): Int {
        val ka = keys[(a shr 8).toInt()]
        val kb = keys[(b shr 8).toInt()]
        var i = (a and 0xFF).toInt()
        var j = (b and 0xFF).toInt()
        while (i < ka.length && j < kb.length) {
            val d = ka[i] - kb[j]
            if (d != 0) return d
            i++
            j++
        }
        return (ka.length - i) - (kb.length - j)
    }

    /**
     * Within 50 km: the label candidate maximising population / (distanceKm + 5)^2; else the marine polygon name;
     * else the nearest label candidate at any distance.
     */
    fun label(latDeg: Double, lonDeg: Double): GroundLabel {
        val lat = latDeg.coerceIn(-90.0, 90.0)
        val lon = wrapLon(lonDeg)
        var best = -1
        var bestKm = Double.MAX_VALUE
        var radiusKm = 100.0
        while (true) {
            scan(lat, lon, radiusKm) { ci, km ->
                if (km < bestKm || (km == bestKm && ci < best)) {
                    bestKm = km
                    best = ci
                }
            }
            if ((best >= 0 && bestKm <= radiusKm) || radiusKm >= HALF_CIRCUMFERENCE_KM) break
            radiusKm *= 2
        }
        if (bestKm <= NEAR_KM) {
            var pick = -1
            var pickKm = 0.0
            var pickScore = -1.0
            scan(lat, lon, NEAR_KM) { ci, km ->
                if (km <= NEAR_KM) {
                    val score = cities[ci].population / ((km + 5.0) * (km + 5.0))
                    if (score > pickScore || (score == pickScore && ci < pick)) {
                        pickScore = score
                        pick = ci
                        pickKm = km
                    }
                }
            }
            val c = cities[pick]
            return GroundLabel.City(c.name, c.countryCode, pickKm)
        }
        marine.nameAt(lat, lon)?.let { return GroundLabel.Sea(it) }
        val city = cities[best]
        return GroundLabel.City(city.name, city.countryCode, bestKm)
    }

    /** Visits every label candidate whose grid cell intersects the cap of [radiusKm] around the point. */
    private inline fun scan(lat: Double, lon: Double, radiusKm: Double, visit: (Int, Double) -> Unit) {
        val dRad = min(radiusKm / EARTH_RADIUS_KM, PI)
        val dDeg = Math.toDegrees(dRad)
        val rowLo = rowOf(max(-90.0, lat - dDeg))
        val rowHi = rowOf(min(90.0, lat + dDeg))
        val latRad = Math.toRadians(lat)
        // A cap containing a pole spans every longitude.
        val dLonDeg = if (dRad >= PI / 2 - abs(latRad)) 360.0 else Math.toDegrees(asin(sin(dRad) / cos(latRad)))
        val colFrom = floor((lon - dLonDeg + 180.0) / CELL_DEG).toInt()
        val colTo = floor((lon + dLonDeg + 180.0) / CELL_DEG).toInt()
        val span = min(GRID_COLS, colTo - colFrom + 1)
        for (r in rowLo..rowHi) {
            for (k in 0 until span) {
                val cellIdx = r * GRID_COLS + Math.floorMod(colFrom + k, GRID_COLS)
                for (p in cellStart[cellIdx] until cellStart[cellIdx + 1]) {
                    val ci = cellCities[p]
                    visit(ci, distanceKm(lat, lon, cities[ci].latDeg, cities[ci].lonDeg))
                }
            }
        }
    }

    /** Prefix match on the folded name or any word of it (or of an alias), highest population first. */
    fun search(query: String, limit: Int): List<City> {
        val q = GeoFold.fold(query)
        if (q.isEmpty() || limit <= 0) return emptyList()
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compareToQuery(entries[mid], q) < 0) lo = mid + 1 else hi = mid
        }
        val hits = BitSet(cities.size)
        var i = lo
        while (i < entries.size && compareToQuery(entries[i], q) == 0) {
            hits.set(keyCity[(entries[i] shr 8).toInt()])
            i++
        }
        val out = ArrayList<City>(min(limit, 64))
        var c = hits.nextSetBit(0)
        while (c >= 0 && out.size < limit) {
            out.add(cities[c])
            c = hits.nextSetBit(c + 1)
        }
        return out
    }

    /** 0 when the entry's text starts with [q]; otherwise the sign of entry versus q. */
    private fun compareToQuery(entry: Long, q: String): Int {
        val key = keys[(entry shr 8).toInt()]
        val off = (entry and 0xFF).toInt()
        val n = min(q.length, key.length - off)
        for (i in 0 until n) {
            val d = key[off + i] - q[i]
            if (d != 0) return d
        }
        return if (key.length - off >= q.length) 0 else -1
    }

    companion object {
        private const val NEAR_KM = 50.0
        private const val EARTH_RADIUS_KM = 6371.0088
        private const val HALF_CIRCUMFERENCE_KM = PI * EARTH_RADIUS_KM
        private const val CELL_DEG = 2.0
        private const val GRID_ROWS = 90
        private const val GRID_COLS = 180
        private const val CITIES_MAGIC = 0x47435431 // "GCT1"
        private const val FIXED_SCALE = 1e4

        fun load(): GeoIndex {
            val cities = open("/geo/cities.bin").use { readCities(it) }
            val marine = MarineRaster.read(open("/geo/marine.bin"))
            return GeoIndex(cities.cities, cities.keys, cities.keyCity, cities.sections, marine)
        }

        private class CityData(val cities: Array<City>, val keys: Array<String>, val keyCity: IntArray, val sections: BooleanArray)

        private fun open(path: String): DataInputStream {
            val raw = checkNotNull(GeoIndex::class.java.getResourceAsStream(path)) { "missing resource $path" }
            return DataInputStream(InflaterInputStream(raw.buffered(1 shl 16)).buffered(1 shl 16))
        }

        private fun readCities(input: DataInputStream): CityData {
            check(input.readInt() == CITIES_MAGIC) { "bad cities.bin" }
            val n = input.readInt()
            val keys = ArrayList<String>(n * 4)
            val keyCity = ArrayList<Int>(n * 4)
            val sections = BooleanArray(n)
            val cities = Array(n) { i ->
                val name = input.readUTF()
                val cc = CharArray(2) { input.readUnsignedByte().toChar() }.concatToString()
                val lat = input.readInt() / FIXED_SCALE
                val lon = input.readInt() / FIXED_SCALE
                val pop = input.readInt()
                sections[i] = input.readUnsignedByte() and 1 != 0
                keys.add(GeoFold.fold(name))
                keyCity.add(i)
                repeat(input.readUnsignedShort()) {
                    keys.add(input.readUTF())
                    keyCity.add(i)
                }
                City(name, cc, lat, lon, pop)
            }
            return CityData(cities, keys.toTypedArray(), keyCity.toIntArray(), sections)
        }

        private fun wrapLon(lon: Double): Double {
            var l = (lon + 180.0) % 360.0
            if (l < 0) l += 360.0
            return l - 180.0
        }

        private fun rowOf(lat: Double) = min(GRID_ROWS - 1, floor((lat + 90.0) / CELL_DEG).toInt())

        private fun cell(lat: Double, lon: Double): Int =
            rowOf(lat) * GRID_COLS + min(GRID_COLS - 1, floor((wrapLon(lon) + 180.0) / CELL_DEG).toInt())

        private fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val p1 = Math.toRadians(lat1)
            val p2 = Math.toRadians(lat2)
            val sp = sin((p2 - p1) / 2)
            val sl = sin(Math.toRadians(lon2 - lon1) / 2)
            return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(sp * sp + cos(p1) * cos(p2) * sl * sl)))
        }
    }
}
