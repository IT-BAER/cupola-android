#pragma once

#include "device.h"

namespace cupola {

// One descriptor set (set 0 of Device::pipelineLayout) with its own host-visible scene uniform buffer.
// A target keeps one per frame in flight; update it only while the GPU is not using that frame.
class SceneBinding {
public:
    VkResult init(Device& device);
    void destroy(VkDevice dev);
    // Copies the scene block of `inputs` and, on first use, binds the device's texture set (loading it then).
    VkResult update(Device& device, const float* inputs);

    VkDescriptorSet set = VK_NULL_HANDLE;

private:
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkBuffer buffer_ = VK_NULL_HANDLE;
    VkDeviceMemory memory_ = VK_NULL_HANDLE;
    void* mapped_ = nullptr;
    bool texturesBound_ = false;
};

}  // namespace cupola
