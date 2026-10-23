#pragma once

#include <android/native_window.h>

#include <vector>

#include "device.h"
#include "earth_pass.h"
#include "frame_inputs.h"
#include "scene_binding.h"
#include "sky_pass.h"

namespace cupola {

// Swapchain presentation to one Android Surface. Holds one Device reference for its lifetime.
class SurfaceTarget {
public:
    static constexpr uint32_t kFramesInFlight = 2;

    // Takes ownership of one ANativeWindow reference (released in the destructor).
    static VkResult create(ANativeWindow* window, SurfaceTarget** out);
    ~SurfaceTarget();

    void requestResize(uint32_t width, uint32_t height);
    void setInputs(const float* values, int count);
    // VK_SUCCESS when a frame was presented, VK_NOT_READY when skipped (zero-sized surface, no image in time).
    // Once dead (device lost, or a failure after an image was acquired) returns the stored error without waiting.
    VkResult renderFrame();

private:
    struct Frame {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore acquired = VK_NULL_HANDLE;
        bool queriesWritten = false;
        SceneBinding scene;
    };

    SurfaceTarget() = default;
    VkResult init(ANativeWindow* window);
    VkResult createSwapchain();
    void destroySwapchainResources();
    VkResult recreateSwapchain();
    VkResult drawFrame();

    Device* device_ = nullptr;
    ANativeWindow* window_ = nullptr;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat format_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    VkExtent2D requested_{};
    int32_t rotation_ = 0;
    bool dirty_ = false;
    // First fatal error; a dead target may hold an unsignaled fence or a pending acquire semaphore.
    VkResult dead_ = VK_SUCCESS;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkPipeline earthPipeline_ = VK_NULL_HANDLE;
    VkPipeline compositePipeline_ = VK_NULL_HANDLE;
    EarthPass earthPass_;
    SkyPass skyPass_;
    VkQueryPool queries_ = VK_NULL_HANDLE;
    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<VkFramebuffer> framebuffers_;
    // One per swapchain image: a present wait semaphore may only be reused after that image is reacquired.
    std::vector<VkSemaphore> rendered_;
    Frame frames_[kFramesInFlight];
    uint32_t frameIndex_ = 0;
    float inputs_[inputs::kCount] = {};
};

}  // namespace cupola
