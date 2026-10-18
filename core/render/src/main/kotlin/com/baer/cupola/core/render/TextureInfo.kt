package com.baer.cupola.core.render

/**
 * Result of [NativeRenderer.loadTextureInfo]. [status] mirrors `TextureStatus` in texture_loader.h
 * ([KTX2_BASE] + `ktx2::Error` for a file outside the KTX2 contract); [vkResult] is set for [VULKAN].
 * [format] is the VkFormat value, [layers] the Vulkan array layer count (1 for a plain 2D file).
 */
data class TextureInfo(
    val status: Int,
    val vkResult: Int,
    val format: Int,
    val width: Int,
    val height: Int,
    val layers: Int,
    val levels: Int,
) {
    companion object {
        const val OK = 0
        const val ASSET_OPEN = 1
        const val ASSET_READ = 2
        const val FORMAT_UNSUPPORTED = 3
        const val TOO_LARGE = 4
        const val VULKAN = 5
        const val KTX2_BASE = 100
    }
}
