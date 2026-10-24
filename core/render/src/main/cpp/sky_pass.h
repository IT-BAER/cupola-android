#pragma once

#include "device.h"
#include "sky_lut_key.h"

namespace cupola {

// View LUT of the atmosphere seen from the camera (earth.frag MODE 3, Hillaire 2020 style ray march):
// inscattered radiance and transmittance per view direction, parameterised by nadir angle and azimuth from the
// Sun (atmosphere_params.h). Recorded before the Earth pass when [key] reports it stale; the same image pair
// serves every frame in flight (render-pass dependencies order it against earlier and later reads, like EarthPass).
class SkyPass {
public:
    VkResult ensure(Device& device);
    void destroy(VkDevice dev);
    void record(const Device& device, VkCommandBuffer cmd, VkDescriptorSet scene, PushConstants push) const;

    VkImageView inscatter = VK_NULL_HANDLE;
    VkImageView transmittance = VK_NULL_HANDLE;
    // Inputs of the LUT last recorded; reset with the images by destroy().
    SkyLutKey key;

private:
    VkImage images_[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkDeviceMemory memory_[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
};

}  // namespace cupola
