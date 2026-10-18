#include "texture_loader.h"

#include <algorithm>
#include <cstdio>
#include <numeric>

namespace cupola {
namespace {

static_assert(static_cast<uint32_t>(ktx2::Format::kEtc2R8G8B8Unorm) == VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK);
static_assert(static_cast<uint32_t>(ktx2::Format::kEtc2R8G8B8Srgb) == VK_FORMAT_ETC2_R8G8B8_SRGB_BLOCK);
static_assert(static_cast<uint32_t>(ktx2::Format::kEtc2R8G8B8A8Unorm) == VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK);
static_assert(static_cast<uint32_t>(ktx2::Format::kAstc6x6Unorm) == VK_FORMAT_ASTC_6x6_UNORM_BLOCK);
static_assert(static_cast<uint32_t>(ktx2::Format::kAstc6x6Srgb) == VK_FORMAT_ASTC_6x6_SRGB_BLOCK);
static_assert(static_cast<uint32_t>(ktx2::Format::kAstc8x8Unorm) == VK_FORMAT_ASTC_8x8_UNORM_BLOCK);
static_assert(static_cast<uint32_t>(ktx2::Format::kAstc8x8Srgb) == VK_FORMAT_ASTC_8x8_SRGB_BLOCK);

constexpr int32_t code(TextureStatus status) { return static_cast<int32_t>(status); }

// Stores the failing VkResult and returns kVulkan from the enclosing loader function.
#define TEXTURE_VK_TRY(expr)                                                                \
    do {                                                                                    \
        VkResult texture_vk_try_ = (expr);                                                  \
        if (texture_vk_try_ < 0) {                                                          \
            LOGE("%s failed: %d (%s:%d)", #expr, texture_vk_try_, __FILE__, __LINE__);      \
            *vkResult = texture_vk_try_;                                                    \
            return code(TextureStatus::kVulkan);                                            \
        }                                                                                   \
    } while (0)

struct AssetHandle {
    AAsset* asset = nullptr;
    ~AssetHandle() {
        if (asset != nullptr) AAsset_close(asset);
    }
};

bool readFully(AAsset* asset, void* dst, uint64_t bytes) {
    auto* p = static_cast<uint8_t*>(dst);
    while (bytes > 0) {
        int n = AAsset_read(asset, p, static_cast<size_t>(std::min<uint64_t>(bytes, 1u << 30)));
        if (n <= 0) return false;
        p += n;
        bytes -= static_cast<uint64_t>(n);
    }
    return true;
}

// Everything the loader creates; the destructor frees whatever is still owned.
struct UploadResources {
    const Device* device = nullptr;
    Texture texture;
    VkBuffer staging = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    bool pending = false;  // submitted, fence not yet waited

    ~UploadResources() {
        VkDevice dev = device->device;
        if (pending) vkWaitForFences(dev, 1, &fence, VK_TRUE, UINT64_MAX);
        vkDestroyFence(dev, fence, nullptr);
        if (cmd != VK_NULL_HANDLE) vkFreeCommandBuffers(dev, device->commandPool, 1, &cmd);
        if (mapped != nullptr) vkUnmapMemory(dev, stagingMemory);
        vkDestroyBuffer(dev, staging, nullptr);
        vkFreeMemory(dev, stagingMemory, nullptr);
        destroyTexture(*device, &texture);
    }
};

VkImageMemoryBarrier imageBarrier(const Texture& t, VkImageLayout from, VkImageLayout to, VkAccessFlags srcAccess,
                                  VkAccessFlags dstAccess) {
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    b.srcAccessMask = srcAccess;
    b.dstAccessMask = dstAccess;
    b.oldLayout = from;
    b.newLayout = to;
    b.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    b.image = t.image;
    b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, t.info.levels, 0, t.info.layers};
    return b;
}

// Layers per staging fill for a level: all of them, or as many whole layers as fit under the cap.
uint64_t layersPerChunk(const ktx2::Info& info, uint32_t level) {
    const uint64_t length = info.level[level].length;
    if (length <= kStagingCapBytes) return info.layers;
    return kStagingCapBytes / (length / info.layers);
}

int32_t load(Device& device, AAssetManager* assets, const char* path, Texture* out, VkResult* vkResult) {
    AssetHandle asset{AAssetManager_open(assets, path, AASSET_MODE_STREAMING)};
    if (asset.asset == nullptr) return code(TextureStatus::kAssetOpen);
    const off64_t length = AAsset_getLength64(asset.asset);
    const uint64_t fileSize = length < 0 ? 0 : static_cast<uint64_t>(length);

    uint8_t prefix[ktx2::kHeaderBytes + ktx2::kMaxLevels * ktx2::kLevelIndexEntryBytes];
    size_t have = static_cast<size_t>(std::min<uint64_t>(fileSize, ktx2::kHeaderBytes));
    if (!readFully(asset.asset, prefix, have)) return code(TextureStatus::kAssetRead);
    const size_t need = ktx2::prefixBytes(prefix, have);
    if (need > have && need <= fileSize) {
        if (!readFully(asset.asset, prefix + have, need - have)) return code(TextureStatus::kAssetRead);
        have = need;
    }
    ktx2::Info info;
    const ktx2::Error error = ktx2::parse(prefix, have, fileSize, &info);
    if (error != ktx2::Error::kNone) return code(TextureStatus::kKtx2Base) + static_cast<int32_t>(error);

    const auto format = static_cast<VkFormat>(info.format);
    const bool astc = format >= VK_FORMAT_ASTC_4x4_UNORM_BLOCK && format <= VK_FORMAT_ASTC_12x12_SRGB_BLOCK;
    VkFormatProperties formatProps;
    vkGetPhysicalDeviceFormatProperties(device.physical, format, &formatProps);
    const VkFormatFeatureFlags needed = VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT | VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    if ((astc && !device.astcLdr) || (formatProps.optimalTilingFeatures & needed) != needed) {
        return code(TextureStatus::kFormatUnsupported);
    }
    const VkPhysicalDeviceLimits& limits = device.properties.limits;
    if (info.width > limits.maxImageDimension2D || info.height > limits.maxImageDimension2D ||
        info.layers > limits.maxImageArrayLayers) {
        return code(TextureStatus::kTooLarge);
    }
    VkDeviceSize stagingSize = 0;
    for (uint32_t n = 0; n < info.levels; ++n) {
        const uint64_t layers = layersPerChunk(info, n);
        if (layers == 0) return code(TextureStatus::kTooLarge);
        stagingSize = std::max<VkDeviceSize>(stagingSize, layers * (info.level[n].length / info.layers));
    }

    UploadResources r;
    r.device = &device;
    const VkDevice dev = device.device;
    r.texture.info = info;

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {info.width, info.height, 1};
    imageInfo.mipLevels = info.levels;
    imageInfo.arrayLayers = info.layers;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    TEXTURE_VK_TRY(vkCreateImage(dev, &imageInfo, nullptr, &r.texture.image));
    VkMemoryRequirements imageReq;
    vkGetImageMemoryRequirements(dev, r.texture.image, &imageReq);
    TEXTURE_VK_TRY(device.allocate(imageReq, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &r.texture.memory));
    TEXTURE_VK_TRY(vkBindImageMemory(dev, r.texture.image, r.texture.memory, 0));

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = r.texture.image;
    viewInfo.viewType = info.array ? VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, info.levels, 0, info.layers};
    TEXTURE_VK_TRY(vkCreateImageView(dev, &viewInfo, nullptr, &r.texture.view));

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = stagingSize;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    TEXTURE_VK_TRY(vkCreateBuffer(dev, &bufferInfo, nullptr, &r.staging));
    VkMemoryRequirements bufferReq;
    vkGetBufferMemoryRequirements(dev, r.staging, &bufferReq);
    TEXTURE_VK_TRY(device.allocate(bufferReq, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                   VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, &r.stagingMemory));
    TEXTURE_VK_TRY(vkBindBufferMemory(dev, r.staging, r.stagingMemory, 0));
    TEXTURE_VK_TRY(vkMapMemory(dev, r.stagingMemory, 0, VK_WHOLE_SIZE, 0, &r.mapped));

    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = device.commandPool;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    TEXTURE_VK_TRY(vkAllocateCommandBuffers(dev, &alloc, &r.cmd));
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    TEXTURE_VK_TRY(vkCreateFence(dev, &fenceInfo, nullptr, &r.fence));

    // Levels in file order (smallest mip first in KTX2) so the asset stream only seeks forwards.
    uint32_t order[ktx2::kMaxLevels];
    std::iota(order, order + info.levels, 0u);
    std::sort(order, order + info.levels,
              [&info](uint32_t a, uint32_t b) { return info.level[a].offset < info.level[b].offset; });
    bool first = true;
    for (uint32_t i = 0; i < info.levels; ++i) {
        const uint32_t n = order[i];
        const uint64_t layerBytes = info.level[n].length / info.layers;
        const uint64_t chunk = layersPerChunk(info, n);
        for (uint64_t base = 0; base < info.layers; base += chunk) {
            const uint64_t count = std::min<uint64_t>(chunk, info.layers - base);
            const uint64_t offset = info.level[n].offset + base * layerBytes;
            if (AAsset_seek64(asset.asset, static_cast<off64_t>(offset), SEEK_SET) != static_cast<off64_t>(offset) ||
                !readFully(asset.asset, r.mapped, count * layerBytes)) {
                return code(TextureStatus::kAssetRead);
            }
            VkMappedMemoryRange flush{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
            flush.memory = r.stagingMemory;
            flush.size = VK_WHOLE_SIZE;
            TEXTURE_VK_TRY(vkFlushMappedMemoryRanges(dev, 1, &flush));

            const bool last = i + 1 == info.levels && base + count >= info.layers;
            VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            TEXTURE_VK_TRY(vkBeginCommandBuffer(r.cmd, &begin));
            if (first) {
                VkImageMemoryBarrier toDst = imageBarrier(r.texture, VK_IMAGE_LAYOUT_UNDEFINED,
                                                          VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0,
                                                          VK_ACCESS_TRANSFER_WRITE_BIT);
                vkCmdPipelineBarrier(r.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0,
                                     nullptr, 0, nullptr, 1, &toDst);
            }
            VkBufferImageCopy region{};
            region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, n, static_cast<uint32_t>(base),
                                       static_cast<uint32_t>(count)};
            region.imageExtent = {std::max(1u, info.width >> n), std::max(1u, info.height >> n), 1};
            vkCmdCopyBufferToImage(r.cmd, r.staging, r.texture.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
            if (last) {
                VkImageMemoryBarrier toRead = imageBarrier(r.texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                                                           VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                                           VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT);
                vkCmdPipelineBarrier(r.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0,
                                     nullptr, 0, nullptr, 1, &toRead);
            }
            TEXTURE_VK_TRY(vkEndCommandBuffer(r.cmd));
            VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            submit.commandBufferCount = 1;
            submit.pCommandBuffers = &r.cmd;
            TEXTURE_VK_TRY(vkQueueSubmit(device.queue, 1, &submit, r.fence));
            r.pending = true;
            TEXTURE_VK_TRY(vkWaitForFences(dev, 1, &r.fence, VK_TRUE, UINT64_MAX));
            r.pending = false;
            TEXTURE_VK_TRY(vkResetFences(dev, 1, &r.fence));
            first = false;
        }
    }
    *out = r.texture;
    r.texture = Texture{};
    return code(TextureStatus::kOk);
}

}  // namespace

int32_t loadTexture(Device& device, AAssetManager* assets, const char* path, Texture* out, VkResult* vkResult) {
    TraceSection trace("cupola.loadTexture");
    *out = Texture{};
    *vkResult = VK_SUCCESS;
    int32_t status = load(device, assets, path, out, vkResult);
    if (status != code(TextureStatus::kOk)) LOGW("loadTexture %s failed: status %d, VkResult %d", path, status, *vkResult);
    return status;
}

void destroyTexture(const Device& device, Texture* texture) {
    vkDestroyImageView(device.device, texture->view, nullptr);
    vkDestroyImage(device.device, texture->image, nullptr);
    vkFreeMemory(device.device, texture->memory, nullptr);
    *texture = Texture{};
}

}  // namespace cupola
