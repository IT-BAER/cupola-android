#include "texture_set.h"

#include <chrono>
#include <string>

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

VkResult createSampler(const Device& device, VkSamplerAddressMode u, VkSampler* out) {
    VkSamplerCreateInfo info{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    info.magFilter = VK_FILTER_LINEAR;
    info.minFilter = VK_FILTER_LINEAR;
    info.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    info.addressModeU = u;
    info.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
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

}  // namespace

VkResult loadTextureSet(Device& device, AAssetManager* assets, TextureSet* out) {
    TraceSection trace("cupola.textureSet");
    auto start = std::chrono::steady_clock::now();
    VK_TRY(createSampler(device, VK_SAMPLER_ADDRESS_MODE_REPEAT, &out->wrapU));
    VK_TRY(createSampler(device, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE, &out->clamp));
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
    vkDestroySampler(device.device, set->wrapU, nullptr);
    vkDestroySampler(device.device, set->clamp, nullptr);
    *set = TextureSet{};
}

}  // namespace cupola
