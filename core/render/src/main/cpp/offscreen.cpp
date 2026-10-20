#include "offscreen.h"

#include <cstring>

#include "earth_pass.h"
#include "scene_binding.h"

namespace cupola {
namespace {

struct OffscreenResources {
    VkDevice dev = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory imageMemory = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkFramebuffer framebuffer = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    VkBuffer buffer = VK_NULL_HANDLE;
    VkDeviceMemory bufferMemory = VK_NULL_HANDLE;
    VkQueryPool queries = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    SceneBinding scene;
    EarthPass earthPass;

    ~OffscreenResources() {
        if (fence != VK_NULL_HANDLE) vkWaitForFences(dev, 1, &fence, VK_TRUE, UINT64_MAX);
        vkDestroyFence(dev, fence, nullptr);
        earthPass.destroy(dev);
        scene.destroy(dev);
        if (cmd != VK_NULL_HANDLE) vkFreeCommandBuffers(dev, pool, 1, &cmd);
        vkDestroyQueryPool(dev, queries, nullptr);
        vkDestroyBuffer(dev, buffer, nullptr);
        vkFreeMemory(dev, bufferMemory, nullptr);
        vkDestroyPipeline(dev, pipeline, nullptr);
        vkDestroyFramebuffer(dev, framebuffer, nullptr);
        vkDestroyRenderPass(dev, renderPass, nullptr);
        vkDestroyImageView(dev, view, nullptr);
        vkDestroyImage(dev, image, nullptr);
        vkFreeMemory(dev, imageMemory, nullptr);
    }
};

}  // namespace

VkResult renderOffscreen(Device& device, uint32_t width, uint32_t height, const float* inputs, uint8_t* out) {
    TraceSection trace("cupola.offscreen");
    constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_UNORM;
    const VkExtent2D extent{width, height};
    const VkDeviceSize byteCount = VkDeviceSize{width} * height * 4;
    OffscreenResources r;
    r.dev = device.device;
    r.pool = device.commandPool;

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = kFormat;
    imageInfo.extent = {width, height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_TRY(vkCreateImage(r.dev, &imageInfo, nullptr, &r.image));
    VkMemoryRequirements imageReq;
    vkGetImageMemoryRequirements(r.dev, r.image, &imageReq);
    VK_TRY(device.allocate(imageReq, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &r.imageMemory));
    VK_TRY(vkBindImageMemory(r.dev, r.image, r.imageMemory, 0));

    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = r.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kFormat;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(vkCreateImageView(r.dev, &viewInfo, nullptr, &r.view));
    VK_TRY(device.createRenderPass(kFormat, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, &r.renderPass));
    VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbInfo.renderPass = r.renderPass;
    fbInfo.attachmentCount = 1;
    fbInfo.pAttachments = &r.view;
    fbInfo.width = width;
    fbInfo.height = height;
    fbInfo.layers = 1;
    VK_TRY(vkCreateFramebuffer(r.dev, &fbInfo, nullptr, &r.framebuffer));
    const bool earth = wantsEarth(inputs);
    const bool scaled = EarthPass::wanted(inputs);
    VK_TRY(device.createPipeline(r.renderPass, earth, &r.pipeline, scaled ? kEarthModeComposite : kEarthModeSingle));
    if (scaled) VK_TRY(r.earthPass.ensure(device, extent, inputs[inputs::kRenderScale]));
    if (earth) {
        VK_TRY(r.scene.init(device));
        VK_TRY(r.scene.update(device, inputs, scaled ? r.earthPass.view : VK_NULL_HANDLE));
    }

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = byteCount;
    bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_TRY(vkCreateBuffer(r.dev, &bufferInfo, nullptr, &r.buffer));
    VkMemoryRequirements bufferReq;
    vkGetBufferMemoryRequirements(r.dev, r.buffer, &bufferReq);
    VK_TRY(device.allocate(bufferReq, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                           VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT, &r.bufferMemory));
    VK_TRY(vkBindBufferMemory(r.dev, r.buffer, r.bufferMemory, 0));
    VK_TRY(device.createTimestampPool(Device::kTimestampsPerFrame, &r.queries));

    VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    alloc.commandPool = r.pool;
    alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    alloc.commandBufferCount = 1;
    VK_TRY(vkAllocateCommandBuffers(r.dev, &alloc, &r.cmd));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_TRY(vkBeginCommandBuffer(r.cmd, &begin));
    device.recordFrame(r.cmd, r.renderPass, r.framebuffer, r.pipeline, earth ? r.scene.set : VK_NULL_HANDLE, extent,
                       makePushConstants(inputs, extent, 0), r.queries, 0, scaled ? &r.earthPass : nullptr,
                       r.scene.earthSet);
    VkBufferImageCopy region{};
    region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    region.imageExtent = {width, height, 1};
    vkCmdCopyImageToBuffer(r.cmd, r.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, r.buffer, 1, &region);
    VkBufferMemoryBarrier toHost{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    toHost.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toHost.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    toHost.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toHost.buffer = r.buffer;
    toHost.size = VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(r.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 0, nullptr, 1,
                         &toHost, 0, nullptr);
    VK_TRY(vkEndCommandBuffer(r.cmd));

    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VK_TRY(vkCreateFence(r.dev, &fenceInfo, nullptr, &r.fence));
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &r.cmd;
    VkResult submitted = vkQueueSubmit(device.queue, 1, &submit, r.fence);
    if (submitted != VK_SUCCESS) {
        vkDestroyFence(r.dev, r.fence, nullptr);
        r.fence = VK_NULL_HANDLE;
        VK_TRY(submitted);
    }
    VK_TRY(vkWaitForFences(r.dev, 1, &r.fence, VK_TRUE, UINT64_MAX));
    device.collectGpuTime(r.queries, 0);

    void* mapped = nullptr;
    VK_TRY(vkMapMemory(r.dev, r.bufferMemory, 0, VK_WHOLE_SIZE, 0, &mapped));
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
    range.memory = r.bufferMemory;
    range.size = VK_WHOLE_SIZE;
    VkResult invalidated = vkInvalidateMappedMemoryRanges(r.dev, 1, &range);
    if (invalidated == VK_SUCCESS) std::memcpy(out, mapped, static_cast<size_t>(byteCount));
    vkUnmapMemory(r.dev, r.bufferMemory);
    return invalidated;
}

}  // namespace cupola
