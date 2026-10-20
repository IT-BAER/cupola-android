#pragma once

#include <android/asset_manager.h>

#include <cstdint>
#include <memory>
#include <string>

#include "frame_inputs.h"
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
    float earthScaleX;  // scaled Earth target / full extent (EarthPass only)
    float earthScaleY;
};

// earth.frag specialization constant MODE.
constexpr int32_t kEarthModeSingle = 0;
constexpr int32_t kEarthModeScaled = 1;
constexpr int32_t kEarthModeComposite = 2;

class EarthPass;

struct TextureSet;

// Scene uniform block of earth.frag: the FrameInputs floats from kSceneBlock, padded to whole vec4s.
constexpr VkDeviceSize kSceneUniformBytes = (inputs::kCount - inputs::kSceneBlock + 3) / 4 * 4 * sizeof(float);

// Earth pipeline when the inputs ask for the scene and no synthetic load; else the test-pattern pipeline.
bool wantsEarth(const float* inputs);

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
    VkShaderModule patternShader = VK_NULL_HANDLE;
    VkShaderModule earthShader = VK_NULL_HANDLE;
    // Set 0: binding 0 scene uniform block, bindings 1.. the TextureSet slots, then the scaled Earth image
    // (fragment stage).
    VkDescriptorSetLayout sceneSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;

    // Asset manager for the texture set; set before the first Earth frame (JNI setAssetManager).
    static void setAssetManager(AAssetManager* assets);
    // The texture set, loaded on first use (render thread, blocking) and kept for the device lifetime.
    VkResult textures(const TextureSet** out);

    // GPU duration of the last completed frame (any target, survives device teardown), -1 until known.
    static int64_t lastGpuTimeNs();
    // GPU time of the last frame's scaled Earth pass (0 without one), -1 if unknown.
    static int64_t lastEarthPassGpuTimeNs();
    static constexpr uint32_t kTimestampsPerFrame = 3;

    VkResult createRenderPass(VkFormat format, VkImageLayout finalLayout, VkRenderPass* out) const;
    // `earthMode` (kEarthMode*) applies to the Earth pipeline.
    VkResult createPipeline(VkRenderPass renderPass, bool earth, VkPipeline* out,
                            int32_t earthMode = kEarthModeSingle) const;
    // Returns UINT32_MAX when no memory type matches.
    uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags flags) const;
    // Memory of type `preferred`, else `required`; VK_ERROR_OUT_OF_DEVICE_MEMORY when neither exists.
    VkResult allocate(VkMemoryRequirements req, VkMemoryPropertyFlags preferred, VkMemoryPropertyFlags required,
                      VkDeviceMemory* out) const;
    // kTimestampsPerFrame timestamps per frame at query index `first`: begin, after the Earth pass, end.
    // A non-null `earthPass` is recorded (with `earthScene`) before the main pass, which then uses the composite
    // pipeline.
    void recordFrame(VkCommandBuffer cmd, VkRenderPass renderPass, VkFramebuffer framebuffer,
                     VkPipeline pipeline, VkDescriptorSet scene, VkExtent2D extent, const PushConstants& push,
                     VkQueryPool queries, uint32_t first, const EarthPass* earthPass = nullptr,
                     VkDescriptorSet earthScene = VK_NULL_HANDLE) const;
    VkResult createTimestampPool(uint32_t count, VkQueryPool* out) const;
    void collectGpuTime(VkQueryPool queries, uint32_t first);

private:
    Device() = default;
    ~Device();
    VkResult init();

    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
    std::unique_ptr<TextureSet> textures_;
};

}  // namespace cupola
