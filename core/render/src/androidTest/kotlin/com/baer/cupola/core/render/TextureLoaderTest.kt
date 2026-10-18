package com.baer.cupola.core.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Loads the KTX2 fixtures of src/test/cpp/fixtures (test APK assets) through the native loader. */
@RunWith(AndroidJUnit4::class)
class TextureLoaderTest {
    private val renderer = NativeRenderer()
    private val assets = InstrumentationRegistry.getInstrumentation().context.assets

    @After
    fun tearDown() = renderer.close()

    @Test
    fun astc6x6Srgb2d() {
        assumeTrue("no ASTC LDR", renderer.deviceInfo().astcLdr)
        assertLoaded("astc_6x6_srgb_2d.ktx2", VK_FORMAT_ASTC_6x6_SRGB_BLOCK, layers = 1)
    }

    @Test
    fun astc8x8UnormArray3() {
        assumeTrue("no ASTC LDR", renderer.deviceInfo().astcLdr)
        assertLoaded("astc_8x8_unorm_array3.ktx2", VK_FORMAT_ASTC_8x8_UNORM_BLOCK, layers = 3)
    }

    @Test
    fun etc2RgbSrgb2d() = assertLoaded("etc2_rgb_srgb_2d.ktx2", VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK, layers = 1)

    @Test
    fun etc2RgbaUnorm2d() = assertLoaded("etc2_rgba_unorm_2d.ktx2", VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK, layers = 1)

    @Test
    fun truncatedFileReturnsTypedError() {
        val info = renderer.loadTextureInfo(assets, "corrupt_truncated.ktx2")
        assertEquals(TextureInfo.KTX2_BASE + KTX2_LEVEL_OUT_OF_RANGE, info.status)
        // The renderer is still usable after the failed load.
        assertLoaded("etc2_rgb_srgb_2d.ktx2", VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK, layers = 1)
    }

    @Test
    fun missingAssetReturnsTypedError() {
        assertEquals(TextureInfo.ASSET_OPEN, renderer.loadTextureInfo(assets, "missing.ktx2").status)
    }

    private fun assertLoaded(path: String, format: Int, layers: Int) {
        val expected = TextureInfo(TextureInfo.OK, 0, format, 16, 12, layers, 5)
        assertEquals(expected, renderer.loadTextureInfo(assets, path))
    }

    private companion object {
        const val VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK = 148
        const val VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK = 151
        const val VK_FORMAT_ASTC_6x6_SRGB_BLOCK = 166
        const val VK_FORMAT_ASTC_8x8_UNORM_BLOCK = 171
        const val KTX2_LEVEL_OUT_OF_RANGE = 13 // ktx2::Error::kLevelOutOfRange
    }
}
