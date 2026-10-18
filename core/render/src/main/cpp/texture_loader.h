#pragma once

#include <android/asset_manager.h>

#include <cstdint>

#include "device.h"
#include "ktx2_reader.h"

namespace cupola {

// Result codes of loadTexture (mirrored in TextureInfo.kt). KTX2 contract violations are
// kKtx2Base + ktx2::Error.
enum class TextureStatus : int32_t {
    kOk = 0,
    kAssetOpen = 1,          // asset missing
    kAssetRead = 2,          // short read
    kFormatUnsupported = 3,  // no SAMPLED_IMAGE + TRANSFER_DST for the format (or no ASTC LDR)
    kTooLarge = 4,           // above maxImageDimension2D / maxImageArrayLayers, or one layer above the staging cap
    kVulkan = 5,             // a Vulkan call failed; see the VkResult
    kKtx2Base = 100,
};

struct Texture {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    ktx2::Info info{};
};

// Staging buffer cap; a level above it is uploaded in groups of whole layers.
constexpr VkDeviceSize kStagingCapBytes = 32ull << 20;

// Streams a KTX2 asset into a sampled image in SHADER_READ_ONLY_OPTIMAL (all levels and layers).
// Render thread only (uses Device::queue and Device::commandPool). Waits on a fence per submission.
// On failure nothing is left allocated and `*out` is empty; `*vkResult` holds the failing VkResult.
int32_t loadTexture(Device& device, AAssetManager* assets, const char* path, Texture* out, VkResult* vkResult);

void destroyTexture(const Device& device, Texture* texture);

}  // namespace cupola
