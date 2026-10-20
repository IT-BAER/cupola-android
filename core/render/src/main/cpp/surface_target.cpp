#include "surface_target.h"

#include <algorithm>
#include <cstring>

namespace cupola {
namespace {

// Bounded so a stalled compositor cannot hold the render thread (and every postAndWait caller) forever.
constexpr uint64_t kAcquireTimeoutNs = 100'000'000;

int32_t quarterTurns(VkSurfaceTransformFlagBitsKHR transform) {
    switch (transform) {
        case VK_SURFACE_TRANSFORM_ROTATE_90_BIT_KHR: return 1;
        case VK_SURFACE_TRANSFORM_ROTATE_180_BIT_KHR: return 2;
        case VK_SURFACE_TRANSFORM_ROTATE_270_BIT_KHR: return 3;
        default: return 0;
    }
}

VkFormat pickFormat(const std::vector<VkSurfaceFormatKHR>& formats) {
    // UNORM keeps the test pattern bytes exact; sRGB formats would re-encode them.
    for (VkFormat wanted : {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM}) {
        for (const auto& f : formats) {
            if (f.format == wanted && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) return wanted;
        }
    }
    return formats.empty() ? VK_FORMAT_UNDEFINED : formats.front().format;
}

}  // namespace

VkResult SurfaceTarget::create(ANativeWindow* window, SurfaceTarget** out) {
    auto* target = new SurfaceTarget();
    VkResult result = target->init(window);
    if (result != VK_SUCCESS) {
        delete target;
        *out = nullptr;
        return result;
    }
    *out = target;
    return VK_SUCCESS;
}

VkResult SurfaceTarget::init(ANativeWindow* window) {
    window_ = window;
    VkResult error;
    device_ = Device::acquire(&error);
    if (device_ == nullptr) return error;
    VkDevice dev = device_->device;

    VkAndroidSurfaceCreateInfoKHR surfaceInfo{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
    surfaceInfo.window = window_;
    VK_TRY(vkCreateAndroidSurfaceKHR(device_->instance, &surfaceInfo, nullptr, &surface_));
    VkBool32 presentable = VK_FALSE;
    VK_TRY(vkGetPhysicalDeviceSurfaceSupportKHR(device_->physical, device_->queueFamily, surface_, &presentable));
    if (!presentable) return VK_ERROR_INCOMPATIBLE_DISPLAY_KHR;

    uint32_t count = 0;
    VK_TRY(vkGetPhysicalDeviceSurfaceFormatsKHR(device_->physical, surface_, &count, nullptr));
    std::vector<VkSurfaceFormatKHR> formats(count);
    VK_TRY(vkGetPhysicalDeviceSurfaceFormatsKHR(device_->physical, surface_, &count, formats.data()));
    format_ = pickFormat(formats);
    if (format_ == VK_FORMAT_UNDEFINED) return VK_ERROR_FORMAT_NOT_SUPPORTED;

    VK_TRY(device_->createRenderPass(format_, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, &renderPass_));
    VK_TRY(device_->createPipeline(renderPass_, false, &pipeline_));
    VK_TRY(device_->createPipeline(renderPass_, true, &earthPipeline_));
    VK_TRY(device_->createPipeline(renderPass_, true, &compositePipeline_, kEarthModeComposite));
    VK_TRY(device_->createTimestampPool(Device::kTimestampsPerFrame * kFramesInFlight, &queries_));

    for (Frame& frame : frames_) {
        VkCommandBufferAllocateInfo alloc{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        alloc.commandPool = device_->commandPool;
        alloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        alloc.commandBufferCount = 1;
        VK_TRY(vkAllocateCommandBuffers(dev, &alloc, &frame.cmd));
        VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        VK_TRY(vkCreateFence(dev, &fenceInfo, nullptr, &frame.fence));
        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VK_TRY(vkCreateSemaphore(dev, &semaphoreInfo, nullptr, &frame.acquired));
        VK_TRY(frame.scene.init(*device_));
    }
    return createSwapchain();
}

SurfaceTarget::~SurfaceTarget() {
    if (device_ != nullptr) {
        VkDevice dev = device_->device;
        // Every submitted frame and present must finish before the swapchain and window go away.
        vkDeviceWaitIdle(dev);
        destroySwapchainResources();
        vkDestroySwapchainKHR(dev, swapchain_, nullptr);
        for (Frame& frame : frames_) {
            frame.scene.destroy(dev);
            vkDestroySemaphore(dev, frame.acquired, nullptr);
            vkDestroyFence(dev, frame.fence, nullptr);
            if (frame.cmd != VK_NULL_HANDLE) vkFreeCommandBuffers(dev, device_->commandPool, 1, &frame.cmd);
        }
        vkDestroyQueryPool(dev, queries_, nullptr);
        vkDestroyPipeline(dev, pipeline_, nullptr);
        vkDestroyPipeline(dev, earthPipeline_, nullptr);
        vkDestroyPipeline(dev, compositePipeline_, nullptr);
        earthPass_.destroy(dev);
        vkDestroyRenderPass(dev, renderPass_, nullptr);
        vkDestroySurfaceKHR(device_->instance, surface_, nullptr);
        Device::release();
    }
    if (window_ != nullptr) ANativeWindow_release(window_);
}

void SurfaceTarget::requestResize(uint32_t width, uint32_t height) {
    requested_ = {width, height};
    dirty_ = true;
}

void SurfaceTarget::setInputs(const float* values, int count) {
    std::memcpy(inputs_, values, sizeof(float) * static_cast<size_t>(std::min(count, inputs::kCount)));
}

VkResult SurfaceTarget::createSwapchain() {
    VkSurfaceCapabilitiesKHR caps;
    VK_TRY(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device_->physical, surface_, &caps));
    rotation_ = quarterTurns(caps.currentTransform);
    VkExtent2D extent = caps.currentExtent;
    if (extent.width == UINT32_MAX) {
        extent.width = std::clamp(requested_.width, caps.minImageExtent.width, caps.maxImageExtent.width);
        extent.height = std::clamp(requested_.height, caps.minImageExtent.height, caps.maxImageExtent.height);
    }
    // Android reports currentExtent in the rotated orientation; pre-rotated images use the native one.
    if (rotation_ % 2 == 1) std::swap(extent.width, extent.height);
    extent_ = extent;

    VkSwapchainKHR old = swapchain_;
    swapchain_ = VK_NULL_HANDLE;
    if (extent.width == 0 || extent.height == 0) {
        vkDestroySwapchainKHR(device_->device, old, nullptr);
        return VK_SUCCESS;
    }

    uint32_t imageCount = caps.minImageCount + 1;
    if (caps.maxImageCount != 0) imageCount = std::min(imageCount, caps.maxImageCount);
    VkCompositeAlphaFlagBitsKHR alpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (VkCompositeAlphaFlagBitsKHR candidate :
         {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR,
          VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
        if (caps.supportedCompositeAlpha & candidate) {
            alpha = candidate;
            break;
        }
    }

    VkSwapchainCreateInfoKHR info{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    info.surface = surface_;
    info.minImageCount = imageCount;
    info.imageFormat = format_;
    info.imageColorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    info.imageExtent = extent;
    info.imageArrayLayers = 1;
    info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.preTransform = caps.currentTransform;
    info.compositeAlpha = alpha;
    info.presentMode = VK_PRESENT_MODE_FIFO_KHR;
    info.clipped = VK_TRUE;
    info.oldSwapchain = old;
    VkResult created = vkCreateSwapchainKHR(device_->device, &info, nullptr, &swapchain_);
    vkDestroySwapchainKHR(device_->device, old, nullptr);
    VK_TRY(created);

    VkDevice dev = device_->device;
    uint32_t count = 0;
    VK_TRY(vkGetSwapchainImagesKHR(dev, swapchain_, &count, nullptr));
    images_.resize(count);
    VK_TRY(vkGetSwapchainImagesKHR(dev, swapchain_, &count, images_.data()));
    for (VkImage image : images_) {
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = image;
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = format_;
        viewInfo.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VkImageView view;
        VK_TRY(vkCreateImageView(dev, &viewInfo, nullptr, &view));
        views_.push_back(view);
        VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fbInfo.renderPass = renderPass_;
        fbInfo.attachmentCount = 1;
        fbInfo.pAttachments = &view;
        fbInfo.width = extent.width;
        fbInfo.height = extent.height;
        fbInfo.layers = 1;
        VkFramebuffer framebuffer;
        VK_TRY(vkCreateFramebuffer(dev, &fbInfo, nullptr, &framebuffer));
        framebuffers_.push_back(framebuffer);
        VkSemaphoreCreateInfo semaphoreInfo{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VkSemaphore semaphore;
        VK_TRY(vkCreateSemaphore(dev, &semaphoreInfo, nullptr, &semaphore));
        rendered_.push_back(semaphore);
    }
    LOGI("swapchain %ux%u rotation %d images %u format %d", extent.width, extent.height, rotation_, count, format_);
    return VK_SUCCESS;
}

void SurfaceTarget::destroySwapchainResources() {
    VkDevice dev = device_->device;
    for (VkFramebuffer fb : framebuffers_) vkDestroyFramebuffer(dev, fb, nullptr);
    for (VkImageView view : views_) vkDestroyImageView(dev, view, nullptr);
    for (VkSemaphore semaphore : rendered_) vkDestroySemaphore(dev, semaphore, nullptr);
    framebuffers_.clear();
    views_.clear();
    rendered_.clear();
    images_.clear();
}

VkResult SurfaceTarget::recreateSwapchain() {
    // Stays set until a recreate succeeds, so a failed one is retried on the next frame.
    dirty_ = true;
    VK_TRY(vkDeviceWaitIdle(device_->device));
    destroySwapchainResources();
    VK_TRY(createSwapchain());
    dirty_ = false;
    return VK_SUCCESS;
}

VkResult SurfaceTarget::renderFrame() {
    TraceSection trace("cupola.render");
    if (dead_ < 0) return dead_;
    VkResult result = drawFrame();
    if (result == VK_ERROR_DEVICE_LOST) dead_ = result;
    return result;
}

// After a successful acquire the acquire semaphore is pending and, once reset, the fence is unsignaled:
// a failure there must never be waited on again.
#define VK_TRY_OR_DIE(expr)                                              \
    do {                                                                 \
        VkResult vk_die_result_ = (expr);                                \
        if (vk_die_result_ < 0) {                                        \
            LOGE("%s failed: %d (%s:%d)", #expr, vk_die_result_, __FILE__, __LINE__); \
            dead_ = vk_die_result_;                                      \
            return vk_die_result_;                                       \
        }                                                                \
    } while (0)

VkResult SurfaceTarget::drawFrame() {
    if (dirty_) VK_TRY(recreateSwapchain());
    if (swapchain_ == VK_NULL_HANDLE) return VK_NOT_READY;

    VkDevice dev = device_->device;
    Frame& frame = frames_[frameIndex_];
    VK_TRY(vkWaitForFences(dev, 1, &frame.fence, VK_TRUE, UINT64_MAX));
    if (frame.queriesWritten) {
        device_->collectGpuTime(queries_, frameIndex_ * Device::kTimestampsPerFrame);
        frame.queriesWritten = false;
    }
    // Before acquire: the first Earth frame loads the texture set, which must not hold a swapchain image.
    const bool earth = wantsEarth(inputs_);
    const bool scaled = EarthPass::wanted(inputs_);
    if (scaled) VK_TRY(earthPass_.ensure(*device_, extent_, inputs_[inputs::kRenderScale]));
    if (earth) VK_TRY(frame.scene.update(*device_, inputs_, scaled ? earthPass_.view : VK_NULL_HANDLE));

    uint32_t imageIndex = 0;
    VkResult acquired;
    {
        TraceSection acquire("cupola.acquire");
        acquired = vkAcquireNextImageKHR(dev, swapchain_, kAcquireTimeoutNs, frame.acquired, VK_NULL_HANDLE,
                                         &imageIndex);
    }
    if (acquired == VK_TIMEOUT || acquired == VK_NOT_READY) return VK_NOT_READY;
    if (acquired == VK_ERROR_OUT_OF_DATE_KHR) {
        VK_TRY(recreateSwapchain());
        return VK_NOT_READY;
    }
    VK_TRY(acquired);

    VK_TRY_OR_DIE(vkResetCommandBuffer(frame.cmd, 0));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_TRY_OR_DIE(vkBeginCommandBuffer(frame.cmd, &begin));
    VkPipeline pipeline = !earth ? pipeline_ : scaled ? compositePipeline_ : earthPipeline_;
    device_->recordFrame(frame.cmd, renderPass_, framebuffers_[imageIndex], pipeline,
                         earth ? frame.scene.set : VK_NULL_HANDLE, extent_,
                         makePushConstants(inputs_, extent_, rotation_), queries_,
                         frameIndex_ * Device::kTimestampsPerFrame, scaled ? &earthPass_ : nullptr, frame.scene.earthSet);
    VK_TRY_OR_DIE(vkEndCommandBuffer(frame.cmd));

    VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.waitSemaphoreCount = 1;
    submit.pWaitSemaphores = &frame.acquired;
    submit.pWaitDstStageMask = &waitStage;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &frame.cmd;
    submit.signalSemaphoreCount = 1;
    submit.pSignalSemaphores = &rendered_[imageIndex];
    VK_TRY_OR_DIE(vkResetFences(dev, 1, &frame.fence));
    VK_TRY_OR_DIE(vkQueueSubmit(device_->queue, 1, &submit, frame.fence));
    frame.queriesWritten = queries_ != VK_NULL_HANDLE;

    VkResult presented;
    {
        TraceSection present("cupola.present");
        VkPresentInfoKHR info{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
        info.waitSemaphoreCount = 1;
        info.pWaitSemaphores = &rendered_[imageIndex];
        info.swapchainCount = 1;
        info.pSwapchains = &swapchain_;
        info.pImageIndices = &imageIndex;
        presented = vkQueuePresentKHR(device_->queue, &info);
    }
    frameIndex_ = (frameIndex_ + 1) % kFramesInFlight;
    if (acquired == VK_SUBOPTIMAL_KHR || presented == VK_SUBOPTIMAL_KHR || presented == VK_ERROR_OUT_OF_DATE_KHR) {
        VK_TRY(recreateSwapchain());
        return VK_SUCCESS;
    }
    VK_TRY(presented);
    return VK_SUCCESS;
}

}  // namespace cupola
