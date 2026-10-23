#pragma once

#include "device.h"
#include "sky_pass.h"

namespace cupola {

// One descriptor set (set 0 of Device::pipelineLayout) with its own host-visible scene uniform buffer.
// A target keeps one per frame in flight; update it only while the GPU is not using that frame.
class SceneBinding {
public:
    VkResult init(Device& device);
    void destroy(VkDevice dev);
    // Copies the scene block of `inputs` and, on first use, binds the device's texture set (loading it then).
    // Binds `earthImage` (the EarthPass view; null binds a placeholder) and the `sky` view LUT when they changed.
    VkResult update(Device& device, const float* inputs, VkImageView earthImage, const SkyPass& sky);

    VkDescriptorSet set = VK_NULL_HANDLE;
    // Same uniform buffer and textures, binding 12 always the placeholder: the scaled Earth pass renders into the
    // image that `set` binds there, and a bound attachment must not be a sampled descriptor of the same draw.
    VkDescriptorSet earthSet = VK_NULL_HANDLE;
    // Same again with the view LUT bindings also on placeholders: the sky pass renders into those images.
    VkDescriptorSet skySet = VK_NULL_HANDLE;

private:
    VkDescriptorPool pool_ = VK_NULL_HANDLE;
    VkBuffer buffer_ = VK_NULL_HANDLE;
    VkDeviceMemory memory_ = VK_NULL_HANDLE;
    void* mapped_ = nullptr;
    bool texturesBound_ = false;
    VkImageView earthImage_ = VK_NULL_HANDLE;
    VkImageView skyInscatter_ = VK_NULL_HANDLE;
};

}  // namespace cupola
