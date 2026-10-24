#include "earth_pass.h"

#include <algorithm>
#include <cmath>

#include "frame_inputs.h"

namespace cupola {
namespace {

// sRGB storage keeps 8-bit precision in the dark; the composite samples linear values.
constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_SRGB;

VkResult createRenderPass(VkDevice dev, VkRenderPass* out) {
    VkAttachmentDescription color{};
    color.format = kFormat;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;  // MODE 1 writes every texel
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &ref;
    VkSubpassDependency deps[2]{};
    // Earlier composites read the image in their fragment shaders before this pass overwrites it.
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 2;
    info.pDependencies = deps;
    return vkCreateRenderPass(dev, &info, nullptr, out);
}

}  // namespace

bool EarthPass::wanted(const float* inputs) {
    const float scale = inputs[inputs::kRenderScale];
    return wantsEarth(inputs) && scale > 0.0f && scale < 1.0f;
}

VkResult EarthPass::ensure(Device& device, VkExtent2D full, float scale) {
    if (image_ != VK_NULL_HANDLE && full.width == full_.width && full.height == full_.height && scale == scale_) {
        return VK_SUCCESS;
    }
    VkDevice dev = device.device;
    if (image_ != VK_NULL_HANDLE) {
        VK_TRY(vkQueueWaitIdle(device.queue));
        destroyTarget(dev);
    }
    full_ = full;
    scale_ = scale;
    extent_ = {std::max(1u, static_cast<uint32_t>(std::lround(full.width * scale))),
               std::max(1u, static_cast<uint32_t>(std::lround(full.height * scale)))};

    VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = kFormat;
    imageInfo.extent = {extent_.width, extent_.height, 1};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_TRY(vkCreateImage(dev, &imageInfo, nullptr, &image_));
    VkMemoryRequirements req;
    vkGetImageMemoryRequirements(dev, image_, &req);
    VK_TRY(device.allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &memory_));
    VK_TRY(vkBindImageMemory(dev, image_, memory_, 0));
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = image_;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kFormat;
    viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(vkCreateImageView(dev, &viewInfo, nullptr, &view));
    if (renderPass_ == VK_NULL_HANDLE) VK_TRY(createRenderPass(dev, &renderPass_));
    VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbInfo.renderPass = renderPass_;
    fbInfo.attachmentCount = 1;
    fbInfo.pAttachments = &view;
    fbInfo.width = extent_.width;
    fbInfo.height = extent_.height;
    fbInfo.layers = 1;
    VK_TRY(vkCreateFramebuffer(dev, &fbInfo, nullptr, &framebuffer_));
    if (pipeline_ != VK_NULL_HANDLE) return VK_SUCCESS;
    return device.createPipeline(renderPass_, true, &pipeline_, kEarthModeScaled);
}

void EarthPass::destroyTarget(VkDevice dev) {
    vkDestroyFramebuffer(dev, framebuffer_, nullptr);
    vkDestroyImageView(dev, view, nullptr);
    vkDestroyImage(dev, image_, nullptr);
    vkFreeMemory(dev, memory_, nullptr);
    framebuffer_ = VK_NULL_HANDLE;
    view = VK_NULL_HANDLE;
    image_ = VK_NULL_HANDLE;
    memory_ = VK_NULL_HANDLE;
}

void EarthPass::destroy(VkDevice dev) {
    destroyTarget(dev);
    vkDestroyPipeline(dev, pipeline_, nullptr);
    vkDestroyRenderPass(dev, renderPass_, nullptr);
    *this = EarthPass{};
}

void EarthPass::record(const Device& device, VkCommandBuffer cmd, VkDescriptorSet scene, PushConstants push) const {
    push.earthScaleX = static_cast<float>(extent_.width) / static_cast<float>(full_.width);
    push.earthScaleY = static_cast<float>(extent_.height) / static_cast<float>(full_.height);
    VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    begin.renderPass = renderPass_;
    begin.framebuffer = framebuffer_;
    begin.renderArea.extent = extent_;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, device.pipelineLayout, 0, 1, &scene, 0, nullptr);
    VkViewport viewport{0.0f, 0.0f, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdPushConstants(cmd, device.pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PushConstants), &push);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
}

}  // namespace cupola
