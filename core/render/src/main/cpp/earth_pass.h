#pragma once

#include "device.h"

namespace cupola {

// The scaled Earth target (FrameInputs RENDER_SCALE in (0, 1)): earth.frag MODE 1 renders the Earth into it,
// skipping texels the interior covers; the full-resolution pass (MODE 2) samples it under the interior.
// One image serves every frame in flight: its render pass makes the next write wait for earlier fragment reads
// (queue submission order), and its end makes the write visible to the composite's fragment shader.
class EarthPass {
public:
    // Earth scene with a render scale in (0, 1).
    static bool wanted(const float* inputs);

    // (Re)creates the target for this full (physical) extent and scale; waits for the queue before replacing it.
    VkResult ensure(Device& device, VkExtent2D full, float scale);
    void destroy(VkDevice dev);
    // Records the Earth pass; `push` is the full-resolution frame's push block.
    void record(const Device& device, VkCommandBuffer cmd, VkDescriptorSet scene, PushConstants push) const;

    VkImageView view = VK_NULL_HANDLE;

private:
    VkImage image_ = VK_NULL_HANDLE;
    VkDeviceMemory memory_ = VK_NULL_HANDLE;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkExtent2D full_{};
    VkExtent2D extent_{};
    float scale_ = 0.0f;
};

}  // namespace cupola
