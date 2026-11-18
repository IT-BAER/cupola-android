#pragma once

#include "device.h"
#include "sky_lut_key.h"

namespace cupola {

// View LUT of the atmosphere seen from the camera (earth.frag MODE 3, Hillaire 2020 style ray march):
// inscattered radiance and transmittance per view direction, parameterised by nadir angle and azimuth from the
// Sun (atmosphere_params.h). Recorded before the Earth pass when [key] reports it stale; the same image serves
// every frame in flight (render-pass dependencies order it against earlier and later reads, like EarthPass).
class SkyPass {
public:
    VkResult ensure(Device& device);
    void destroy(VkDevice dev);
    void record(const Device& device, VkCommandBuffer cmd, VkDescriptorSet scene, PushConstants push) const;

    // Array view: layer 0 inscatter, layer 1 transmittance (earth.frag skyView).
    VkImageView view = VK_NULL_HANDLE;
    // Inputs of the LUT last recorded; reset with the image by destroy().
    SkyLutKey key;

private:
    VkImage image_ = VK_NULL_HANDLE;
    VkDeviceMemory memory_ = VK_NULL_HANDLE;
    VkImageView layers_[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};  // the two colour attachments
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
};

}  // namespace cupola
