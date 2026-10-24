package com.baer.cupola

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.cupola.core.render.TextureInfo
import com.baer.cupola.wallpaper.RendererHostEntryPoint
import dagger.hilt.android.EntryPointAccessors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Loads every texture of the installed `:earthpack` slice (plan P5.4) from the app's assets. The app must be
 * installed from the bundle (bundletool install-apks): `assembleDebug` APKs carry no asset pack.
 */
@RunWith(AndroidJUnit4::class)
class EarthpackLoadTest {
    @Test
    fun everyTextureLoadsFromTheDeviceSliceWithinTheHeapBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val assets = context.assets
        // The live wallpaper may already run in this process; a second renderer would rebind the render thread.
        val host = EntryPointAccessors.fromApplication(context, RendererHostEntryPoint::class.java).rendererHost()
        val renderer = host.acquire()
        try {
            renderer.holdDevice().use {
                // Play picks the slice from the GL ASTC extensions; the renderer needs Vulkan ASTC LDR to match.
                val expected = if (renderer.deviceInfo().astcLdr) ASTC else ETC2
                val before = javaHeapKb()
                val loaded = expected.keys.associateWith { renderer.loadTextureInfo(assets, "textures/$it.ktx2") }
                val growthKb = javaHeapKb() - before
                println("EarthpackLoadTest java heap before=$before kB growth=$growthKb kB")
                assertEquals(expected, loaded)
                assertTrue("Java heap grew by $growthKb kB, limit 10 MiB", growthKb <= 10 * 1024)
            }
        } finally {
            host.release()
        }
    }

    // Same value as "Java Heap" in the App Summary of `dumpsys meminfo`.
    private fun javaHeapKb(): Int {
        Runtime.getRuntime().gc()
        return Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.getMemoryStat("summary.java-heap").toInt()
    }

    private companion object {
        const val ETC2_RGB_UNORM = 147
        const val ETC2_RGB_SRGB = 148
        const val ETC2_RGBA_UNORM = 151
        const val ETC2_RGBA_SRGB = 152
        const val ASTC_6X6_UNORM = 165
        const val ASTC_6X6_SRGB = 166
        const val ASTC_8X8_UNORM = 171
        const val ASTC_8X8_SRGB = 172

        fun ok(format: Int, width: Int, height: Int, layers: Int, levels: Int) =
            TextureInfo(TextureInfo.OK, 0, format, width, height, layers, levels)

        // tools/textures/README.md, runtime contract table.
        val ASTC = mapOf(
            "day_detail" to ok(ASTC_8X8_SRGB, 4096, 3277, 8, 13),
            "monthly_colour" to ok(ASTC_6X6_SRGB, 2048, 819, 12, 12),
            "clouds" to ok(ASTC_8X8_UNORM, 8192, 3277, 1, 14),
            "city_lights" to ok(ASTC_8X8_SRGB, 8192, 3277, 1, 14),
            "terrain_normal" to ok(ASTC_6X6_UNORM, 4096, 1638, 1, 13),
            "stars" to ok(ASTC_6X6_SRGB, 4096, 2048, 1, 13),
            "water_mask" to ok(ASTC_8X8_UNORM, 4096, 1638, 1, 13),
            "interior_earth" to ok(ASTC_6X6_SRGB, 2048, 7296, 1, 1),
            "interior_cabin" to ok(ASTC_8X8_SRGB, 2048, 7296, 1, 1),
            "interior_emissive" to ok(ASTC_8X8_SRGB, 2048, 7296, 1, 1),
            "interior_ambient" to ok(ASTC_8X8_SRGB, 512, 1824, 1, 1),
        )
        val ETC2 = mapOf(
            "day_detail" to ok(ETC2_RGB_SRGB, 2048, 1638, 8, 12),
            "monthly_colour" to ok(ETC2_RGB_SRGB, 1024, 409, 12, 11),
            "clouds" to ok(ETC2_RGB_UNORM, 4096, 1638, 1, 13),
            "city_lights" to ok(ETC2_RGB_SRGB, 4096, 1638, 1, 13),
            "terrain_normal" to ok(ETC2_RGBA_UNORM, 2048, 819, 1, 12),
            "stars" to ok(ETC2_RGB_SRGB, 2048, 1024, 1, 12),
            "water_mask" to ok(ETC2_RGB_UNORM, 2048, 819, 1, 12),
            "interior_earth" to ok(ETC2_RGBA_SRGB, 1024, 3648, 1, 1),
            "interior_cabin" to ok(ETC2_RGB_SRGB, 1024, 3648, 1, 1),
            "interior_emissive" to ok(ETC2_RGB_SRGB, 1024, 3648, 1, 1),
            "interior_ambient" to ok(ETC2_RGB_SRGB, 256, 912, 1, 1),
        )
    }
}
