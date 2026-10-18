#pragma once

#include <cstdint>
#include <string>

#include "vk_util.h"

namespace cupola {

struct PushConstants {
    int32_t extentWidth;
    int32_t extentHeight;
    int32_t rotationQuarterTurns;
    int32_t loadIterations;
    float load;
    float timeSeconds;
    float lookYawDeg;
    float lookPitchDeg;
};

PushConstants makePushConstants(const float* inputs, VkExtent2D extent, int32_t rotationQuarterTurns);

// The one Vulkan device of the process, shared by every surface target and offscreen render.
class Device {
public:
    // Reference counted: acquire creates the device on first use, release destroys it after the last user.
    static Device* acquire(VkResult* error);
    static void release();
    static Device* current();

    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physical = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties properties{};
    bool astcLdr = false;
    bool timestamps = false;
    uint64_t timestampMask = 0;

    VkShaderModule vertexShader = VK_NULL_HANDLE;
    VkShaderModule fragmentShader = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;

    // GPU duration of the last completed frame (any target, survives device teardown), -1 until known.
    static int64_t lastGpuTimeNs();

    VkResult createRenderPass(VkFormat format, VkImageLayout finalLayout, VkRenderPass* out) const;
    VkResult createPipeline(VkRenderPass renderPass, VkPipeline* out) const;
    // Returns UINT32_MAX when no memory type matches.
    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags flags) const;
    // Two timestamps per frame: [begin, end] at query index `first`.
    void recordFrame(VkCommandBuffer cmd, VkRenderPass renderPass, VkFramebuffer framebuffer,
                     VkPipeline pipeline, VkExtent2D extent, const PushConstants& push,
                     VkQueryPool queries, uint32_t first) const;
    VkResult createTimestampPool(uint32_t count, VkQueryPool* out) const;
    void collectGpuTime(VkQueryPool queries, uint32_t first);

private:
    Device() = default;
    ~Device();
    VkResult init();

    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
};

}  // namespace cupola
