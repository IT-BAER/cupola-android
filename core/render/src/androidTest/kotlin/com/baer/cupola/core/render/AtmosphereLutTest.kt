package com.baer.cupola.core.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The CPU atmosphere LUTs (atmosphere_lut.cpp, P3.3) on the device: the same checks as the host test
 * src/test/cpp/atmosphere_lut_test.cpp, which runs only in CI. Model constants mirror atmosphere_params.h.
 */
@RunWith(AndroidJUnit4::class)
class AtmosphereLutTest {
    private class Lut(data: FloatArray) {
        val width = data[0].toInt()
        val height = data[1].toInt()
        val rgba = data.copyOfRange(2, data.size)

        fun texel(x: Int, y: Int) = FloatArray(3) { rgba[(y * width + x) * 4 + it] }

        /** Bilinear lookup at texture coordinates (u, v), clamped to the texel centres. */
        fun sample(u: Double, v: Double): DoubleArray {
            val x = (u * width - 0.5).coerceIn(0.0, width - 1.0)
            val y = (v * height - 0.5).coerceIn(0.0, height - 1.0)
            val x0 = x.toInt()
            val y0 = y.toInt()
            val x1 = minOf(x0 + 1, width - 1)
            val y1 = minOf(y0 + 1, height - 1)
            val fx = x - x0
            val fy = y - y0
            return DoubleArray(3) { c ->
                val a = texel(x0, y0)[c] * (1 - fx) + texel(x1, y0)[c] * fx
                val b = texel(x0, y1)[c] * (1 - fx) + texel(x1, y1)[c] * fx
                a * (1 - fy) + b * fy
            }
        }
    }

    /** Transmittance to the top from radius [r] along cos(zenith) [mu] (Bruneton mapping, as the shader). */
    private fun transmittance(r: Double, mu: Double): DoubleArray {
        val h = sqrt(RT * RT - RG * RG)
        val rho = sqrt(maxOf(0.0, r * r - RG * RG))
        val d = maxOf(0.0, -r * mu + sqrt(maxOf(0.0, r * r * (mu * mu - 1) + RT * RT)))
        val dMin = RT - r
        val dMax = rho + h
        fun tc(x: Double, n: Int) = 0.5 / n + x * (1 - 1.0 / n)
        return trans.sample(tc((d - dMin) / (dMax - dMin), trans.width), tc(rho / h, trans.height))
    }

    @Test
    fun sizesAndFinite() {
        assertEquals(256 to 64, trans.width to trans.height)
        assertEquals(32 to 32, multi.width to multi.height)
        assertEquals(64 to 1, irradiance.width to irradiance.height)
        for (lut in listOf(trans, multi, irradiance)) {
            assertEquals(lut.width * lut.height * 4, lut.rgba.size)
            assertTrue("non-finite or negative texel", lut.rgba.all { it.isFinite() && it >= 0f })
        }
    }

    /** Zenith from the ground: exp(-optical depth) of the exponential and tent profiles, within 1 %. */
    @Test
    fun zenithTransmittanceMatchesOpticalDepth() {
        val t = transmittance(RG, 1.0)
        val thick = RT - RG
        for (c in 0..2) {
            val tau = RAYLEIGH[c] * 8.0 * (1 - exp(-thick / 8.0)) + 4.440e-3 * 1.2 * (1 - exp(-thick / 1.2)) + OZONE[c] * 15.0
            assertEquals("channel $c", 1.0, t[c] / exp(-tau), 0.01)
        }
    }

    /** A low Sun reddens: near the horizon green is far below the zenith value and blue drops most; the top is clear. */
    @Test
    fun horizonReddensAndTopIsClear() {
        val zen = transmittance(RG, 1.0)
        val low = transmittance(RG, 0.05)
        val top = transmittance(RT, 0.0)
        assertTrue("green low ${low[1]} zenith ${zen[1]}", low[1] < 0.5 * zen[1])
        assertTrue("blue/red low ${low[2] / low[0]} zenith ${zen[2] / zen[0]}", low[2] / low[0] < 0.5 * zen[2] / zen[0])
        assertTrue("top ${top.toList()}", top[0] > 0.999 && top[2] > 0.999)
    }

    /** Multiple scattering: blue under a high Sun at the ground, far weaker with the Sun deep below the horizon. */
    @Test
    fun multiScatterFollowsTheSun() {
        val noon = multi.texel(multi.width - 1, 0)
        val night = multi.texel(multi.width / 4, 0) // mu_s about -0.5
        assertTrue("noon ${noon.toList()}", noon[2] > noon[0] && noon[0] > 0f)
        assertTrue("night ${night[1]} noon ${noon[1]}", night[1] < 0.2 * noon[1])
    }

    /**
     * Clear-sky diffuse irradiance at noon is 5..25 % of the direct beam (green) and blue; twilight 6 deg below the
     * horizon is dim but not zero; deep night is dark.
     */
    @Test
    fun skyIrradianceIsPlausible() {
        val noon = irradiance.texel(irradiance.width - 1, 0)
        val twilightX = Math.round((0.5 + 0.5 * sin(Math.toRadians(-6.0))) * irradiance.width - 0.5).toInt()
        val twilight = irradiance.texel(twilightX, 0)
        val night = irradiance.texel(irradiance.width / 4, 0)
        assertTrue("noon green ${noon[1]}", noon[1] > 0.05f && noon[1] < 0.25f)
        assertTrue("noon not blue: ${noon.toList()}", noon[2] > noon[0])
        assertTrue("twilight ${twilight[1]}", twilight[1] > 1e-5f && twilight[1] < 0.01f * noon[1])
        assertTrue("night ${night[1]}", abs(night[1]) < 1e-6f)
    }

    companion object {
        private const val RG = 6378.137
        private const val RT = 6478.137
        private val RAYLEIGH = doubleArrayOf(5.802e-3, 13.558e-3, 33.1e-3)
        private val OZONE = doubleArrayOf(0.650e-3, 1.881e-3, 0.085e-3)

        private lateinit var renderer: NativeRenderer
        private lateinit var trans: Lut
        private lateinit var multi: Lut
        private lateinit var irradiance: Lut

        @BeforeClass
        @JvmStatic
        fun setUp() {
            renderer = NativeRenderer()
            trans = Lut(renderer.atmosphereLut(0))
            multi = Lut(renderer.atmosphereLut(1))
            irradiance = Lut(renderer.atmosphereLut(2))
        }

        @AfterClass
        @JvmStatic
        fun tearDown() = renderer.close()
    }
}
