#include "texture_set.h"

#include <chrono>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "atmosphere_lut.h"
#include "device.h"

namespace cupola {
namespace {

struct SlotInfo {
    const char* name;
    bool array;
    VkClearColorValue fallback;  // UNORM values of the 1 x 1 fallback texel
};

// Grey colour (detail ratio 1), flat normal (.ga = 0.5), no clouds, lights, stars or water; no interior
// (window alpha 0 shows the Earth everywhere).
constexpr SlotInfo kSlots[kTextureSlots] = {
    {"day_detail", true, {{0.5f, 0.5f, 0.5f, 1.0f}}},
    {"monthly_colour", true, {{0.5f, 0.5f, 0.5f, 1.0f}}},
    {"clouds", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
    {"city_lights", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
    {"terrain_normal", false, {{0.5f, 0.5f, 1.0f, 0.5f}}},
    {"stars", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
    {"water_mask", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
    {"interior_earth", false, {{0.0f, 0.0f, 0.0f, 0.0f}}},
    {"interior_cabin", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
    {"interior_emissive", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
    {"interior_ambient", false, {{0.0f, 0.0f, 0.0f, 1.0f}}},
};

VkResult createSampler(const Device& device, VkSamplerAddressMode u, VkSampler* out,
                       VkSamplerAddressMode v = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE) {
    VkSamplerCreateInfo info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    info.magFilter = VK_FILTER_LINEAR;
    info.minFilter = VK_FILTER_LINEAR;
    info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    info.addressModeU = u;
    info.addressModeV = v;
    info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    info.maxLod = VK_LOD_CLAMP_NONE;
    return vkCreateSampler(device.device, &info, nullptr, out);
}

VkResult createFallback(Device& device, const SlotInfo& slot, Texture* out) {
    VkDevice dev = device.device;
    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = {1, 1, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    VK_TRY(vkCreateImage(dev, &imageInfo, nullptr, &out->image));
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(dev, out->image, &req);
    VK_TRY(device.allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &out->memory));
    VK_TRY(vkBindImageMemory(dev, out->image, out->memory, 0));
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = out->image;
    viewInfo.viewType = slot.array ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(vkCreateImageView(dev, &viewInfo, nullptr, &out->view));

    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = device.commandPool;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VK_TRY(vkAllocateCommandBuffers(dev, &alloc, &cmd));
    VkFence fence = VK_NULL_HANDLE;
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkResult result = vkCreateFence(dev, &fenceInfo, nullptr, &fence);
    if (result == VK_SUCCESS) {
        VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &begin);
        VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.image = out->image;
        b.subresourceRange = range;
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &b);
        vkCmdClearColorImage(cmd, out->image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &slot.fallback, 1, &range);
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr,
                             0, nullptr, 1, &b);
        result = vkEndCommandBuffer(cmd);
        if (result == VK_SUCCESS) {
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &cmd;
            result = vkQueueSubmit(device.queue, 1, &submit, fence);
            if (result == VK_SUCCESS) result = vkWaitForFences(dev, 1, &fence, VK_TRUE, UINT64_MAX);
        }
        vkDestroyFence(dev, fence, nullptr);
    }
    vkFreeCommandBuffers(dev, device.commandPool, 1, &cmd);
    return result;
}

// IEEE half from float, mantissa truncated (relative error below 0.1 %, enough for LUT data).
uint16_t toHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, 4);
    const uint32_t sign = (x >> 16) & 0x8000u;
    const int32_t exp = static_cast<int32_t>((x >> 23) & 0xFF) - 127 + 15;
    const uint32_t mant = x & 0x7FFFFFu;
    if (exp <= 0) {
        if (exp < -10) return static_cast<uint16_t>(sign);
        return static_cast<uint16_t>(sign | ((mant | 0x800000u) >> (14 - exp)));
    }
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7BFFu);
    return static_cast<uint16_t>(sign | (static_cast<uint32_t>(exp) << 10) | (mant >> 13));
}

VkResult uploadLut(Device& device, const atmosphere::Lut& lut, Texture* out) {
    std::vector<uint16_t> half(lut.rgba.size());
    for (size_t i = 0; i < half.size(); ++i) half[i] = toHalf(lut.rgba[i]);
    return uploadTexture(device, VK_FORMAT_R16G16B16A16_SFLOAT, lut.width, lut.height, half.data(),
                         half.size() * sizeof(uint16_t), out);
}

// Tiling noise, 4 independent channels: value noise on periodic lattices of 4, 8, 16 and 32 cells (fBm, gains
// 1/2), stretched to 0..1. Deterministic integer hash.
constexpr uint32_t kNoiseSize = 256;

uint32_t hash(uint32_t x) {
    x ^= x >> 16;
    x *= 0x7feb352du;
    x ^= x >> 15;
    x *= 0x846ca68bu;
    x ^= x >> 16;
    return x;
}

float lattice(uint32_t channel, uint32_t period, uint32_t ix, uint32_t iy) {
    const uint32_t h = hash((ix % period) + 1024u * (iy % period) + 0x100000u * period + 0x4000000u * channel);
    return static_cast<float>(h & 0xFFFFFFu) / 16777215.0f;
}

float valueNoise(uint32_t channel, uint32_t period, float x, float y) {
    const float fx = std::floor(x), fy = std::floor(y);
    const uint32_t ix = static_cast<uint32_t>(fx), iy = static_cast<uint32_t>(fy);
    float tx = x - fx, ty = y - fy;
    tx = tx * tx * (3 - 2 * tx);
    ty = ty * ty * (3 - 2 * ty);
    const float a = lattice(channel, period, ix, iy), b = lattice(channel, period, ix + 1, iy);
    const float c = lattice(channel, period, ix, iy + 1), d = lattice(channel, period, ix + 1, iy + 1);
    return (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
}

VkResult createNoise(Device& device, Texture* out) {
    std::vector<uint8_t> texels(kNoiseSize * kNoiseSize * 4);
    for (uint32_t y = 0; y < kNoiseSize; ++y) {
        for (uint32_t x = 0; x < kNoiseSize; ++x) {
            for (uint32_t c = 0; c < 4; ++c) {
                float sum = 0, amp = 0.5f, norm = 0;
                for (uint32_t period = 4; period <= 32; period *= 2) {
                    const float scale = static_cast<float>(period) / kNoiseSize;
                    sum += amp * valueNoise(c, period, (x + 0.5f) * scale, (y + 0.5f) * scale);
                    norm += amp;
                    amp *= 0.5f;
                }
                // fBm of value noise clusters near 0.5; stretch by 2 about the mean.
                const float v = std::fmin(1.0f, std::fmax(0.0f, (sum / norm - 0.5f) * 2.0f + 0.5f));
                texels[(y * kNoiseSize + x) * 4 + c] = static_cast<uint8_t>(std::lround(v * 255.0f));
            }
        }
    }
    return uploadTexture(device, VK_FORMAT_R8G8B8A8_UNORM, kNoiseSize, kNoiseSize, texels.data(), texels.size(),
                         out);
}

VkResult createGenerated(Device& device, TextureSet* out) {
    auto start = std::chrono::steady_clock::now();
    atmosphere::Luts luts;
    atmosphere::computeLuts(&luts);
    const long lutMs = static_cast<long>(
        std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
    VK_TRY(uploadLut(device, luts.transmittance, &out->generated[kTransmittanceLut]));
    VK_TRY(uploadLut(device, luts.multiScatter, &out->generated[kMultiScatterLut]));
    VK_TRY(uploadLut(device, luts.irradiance, &out->generated[kSkyIrradianceLut]));
    VK_TRY(createNoise(device, &out->generated[kNoise]));
    LOGI("atmosphere LUTs computed in %ld ms", lutMs);
    return VK_SUCCESS;
}

}  // namespace

VkResult loadTextureSet(Device& device, AAssetManager* assets, TextureSet* out) {
    TraceSection trace("cupola.textureSet");
    auto start = std::chrono::steady_clock::now();
    VK_TRY(createSampler(device, VK_SAMPLER_ADDRESS_MODE_REPEAT, &out->wrapU));
    VK_TRY(createSampler(device, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, &out->clamp));
    VK_TRY(createSampler(device, VK_SAMPLER_ADDRESS_MODE_REPEAT, &out->wrap, VK_SAMPLER_ADDRESS_MODE_REPEAT));
    VK_TRY(createGenerated(device, out));
    std::string missing;
    for (uint32_t s = 0; s < kTextureSlots; ++s) {
        int32_t status = static_cast<int32_t>(TextureStatus::kAssetOpen);
        VkResult vkResult = VK_SUCCESS;
        if (assets != nullptr) {
            std::string path = std::string("textures/") + kSlots[s].name + ".ktx2";
            status = loadTexture(device, assets, path.c_str(), &out->textures[s], &vkResult);
        }
        if (status == static_cast<int32_t>(TextureStatus::kOk) && out->textures[s].info.array == kSlots[s].array) {
            out->loadedMask |= 1u << s;
            continue;
        }
        destroyTexture(device, &out->textures[s]);
        missing += std::string(missing.empty() ? "" : ", ") + kSlots[s].name + " (" + std::to_string(status) + ")";
        VK_TRY(createFallback(device, kSlots[s], &out->textures[s]));
    }
    long ms = static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                    std::chrono::steady_clock::now() - start)
                                    .count());
    if (missing.empty()) {
        LOGI("texture set loaded in %ld ms", ms);
    } else {
        LOGW("texture set: neutral fallback for %s; %ld ms", missing.c_str(), ms);
    }
    return VK_SUCCESS;
}

void destroyTextureSet(const Device& device, TextureSet* set) {
    for (Texture& t : set->textures) destroyTexture(device, &t);
    for (Texture& t : set->generated) destroyTexture(device, &t);
    vkDestroySampler(device.device, set->wrap, nullptr);
    vkDestroySampler(device.device, set->wrapU, nullptr);
    vkDestroySampler(device.device, set->clamp, nullptr);
    *set = TextureSet{};
}

}  // namespace cupola
