#include "sky_pass.h"

#include "atmosphere_params.h"

namespace cupola {
namespace {

constexpr VkFormat kFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
constexpr VkExtent2D kExtent{ATMO_VIEW_W, ATMO_VIEW_H};

VkResult createRenderPass(VkDevice dev, VkRenderPass* out) {
    VkAttachmentDescription colors[2]{};
    VkAttachmentReference refs[2]{};
    for (uint32_t i = 0; i < 2; ++i) {
        colors[i].format = kFormat;
        colors[i].samples = VK_SAMPLE_COUNT_1_BIT;
        colors[i].loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;  // MODE 3 writes every texel
        colors[i].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colors[i].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colors[i].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colors[i].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colors[i].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        refs[i] = {i, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    }
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 2;
    subpass.pColorAttachments = refs;
    VkSubpassDependency deps[2]{};
    // Earlier Earth passes and composites read the LUT in their fragment shaders before this pass overwrites it.
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
    info.attachmentCount = 2;
    info.pAttachments = colors;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 2;
    info.pDependencies = deps;
    return vkCreateRenderPass(dev, &info, nullptr, out);
}

}  // namespace

VkResult SkyPass::ensure(Device& device) {
    if (pipeline_ != VK_NULL_HANDLE) return VK_SUCCESS;
    VkDevice dev = device.device;
    VkImageView views[2];
    for (int i = 0; i < 2; ++i) {
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = kFormat;
        imageInfo.extent = {kExtent.width, kExtent.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VK_TRY(vkCreateImage(dev, &imageInfo, nullptr, &images_[i]));
        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(dev, images_[i], &req);
        VK_TRY(device.allocate(req, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, &memory_[i]));
        VK_TRY(vkBindImageMemory(dev, images_[i], memory_[i], 0));
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = images_[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = kFormat;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VK_TRY(vkCreateImageView(dev, &viewInfo, nullptr, &views[i]));
        (i == 0 ? inscatter : transmittance) = views[i];
    }
    VK_TRY(createRenderPass(dev, &renderPass_));
    VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbInfo.renderPass = renderPass_;
    fbInfo.attachmentCount = 2;
    fbInfo.pAttachments = views;
    fbInfo.width = kExtent.width;
    fbInfo.height = kExtent.height;
    fbInfo.layers = 1;
    VK_TRY(vkCreateFramebuffer(dev, &fbInfo, nullptr, &framebuffer_));
    return device.createPipeline(renderPass_, true, &pipeline_, kEarthModeSky, 2);
}

void SkyPass::destroy(VkDevice dev) {
    vkDestroyPipeline(dev, pipeline_, nullptr);
    vkDestroyFramebuffer(dev, framebuffer_, nullptr);
    vkDestroyRenderPass(dev, renderPass_, nullptr);
    vkDestroyImageView(dev, inscatter, nullptr);
    vkDestroyImageView(dev, transmittance, nullptr);
    for (int i = 0; i < 2; ++i) {
        vkDestroyImage(dev, images_[i], nullptr);
        vkFreeMemory(dev, memory_[i], nullptr);
    }
    *this = SkyPass{};
}

void SkyPass::record(const Device& device, VkCommandBuffer cmd, VkDescriptorSet scene, PushConstants push) const {
    VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    begin.renderPass = renderPass_;
    begin.framebuffer = framebuffer_;
    begin.renderArea.extent = kExtent;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, device.pipelineLayout, 0, 1, &scene, 0, nullptr);
    VkViewport viewport{0.0f, 0.0f, static_cast<float>(kExtent.width), static_cast<float>(kExtent.height), 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, kExtent};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdPushConstants(cmd, device.pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PushConstants), &push);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
}

}  // namespace cupola
