package com.baer.cupola.orbit.geo

import java.io.DataInputStream
import kotlin.math.floor

/** Run-length encoded equirectangular name raster; value 0 means land or unnamed water, n means names[n - 1]. */
internal class MarineRaster(
    private val width: Int,
    private val height: Int,
    private val names: Array<String>,
    private val runEnd: Array<IntArray>,
    private val runValue: Array<ShortArray>,
) {
    fun nameAt(latDeg: Double, lonDeg: Double): String? {
        val row = floor((90.0 - latDeg) * width / 360.0).toInt().coerceIn(0, height - 1)
        var l = (lonDeg + 180.0) % 360.0
        if (l < 0) l += 360.0
        val col = floor(l * width / 360.0).toInt().coerceIn(0, width - 1)
        val ends = runEnd[row]
        var lo = 0
        var hi = ends.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ends[mid] > col) hi = mid else lo = mid + 1
        }
        val v = runValue[row][lo].toInt()
        return if (v == 0) null else names[v - 1]
    }

    companion object {
        const val MAGIC = 0x474D5231 // "GMR1"

        fun read(input: DataInputStream): MarineRaster = input.use {
            check(it.readInt() == MAGIC) { "bad marine.bin" }
            val w = it.readInt()
            val h = it.readInt()
            val names = Array(it.readUnsignedShort()) { _ -> it.readUTF() }
            val ends = Array(h) { IntArray(0) }
            val vals = Array(h) { ShortArray(0) }
            for (r in 0 until h) {
                val n = it.readUnsignedShort()
                val e = IntArray(n)
                val v = ShortArray(n)
                var acc = 0
                for (k in 0 until n) {
                    acc += it.readUnsignedShort()
                    e[k] = acc
                    v[k] = it.readShort()
                }
                check(acc == w) { "row $r covers $acc of $w" }
                ends[r] = e
                vals[r] = v
            }
            MarineRaster(w, h, names, ends, vals)
        }
    }
}
