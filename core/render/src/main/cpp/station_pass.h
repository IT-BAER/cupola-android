#pragma once

#include <vector>

#include "device.h"
#include "station_mesh.h"

namespace cupola {

// station.vert push block.
struct StationPush {
    float clip[16];   // rest-pose position to clip space, column-major
    float pose[3][4]; // rest pose to Cupola frame, rows (rotation | translation)
};

// meshes/station.mesh on the GPU: one vertex and one index buffer (host-visible), per node its index range and
// rest-pose bounds. One per Device (Device::station).
struct StationMesh {
    struct Range {
        uint32_t firstIndex;
        uint32_t indexCount;
        int32_t vertexOffset;
        float lo[3];
        float hi[3];
    };
    std::vector<station::Node> nodes;  // indices dropped after upload, vertices kept for the culling
    std::vector<Range> ranges;
    VkBuffer vertices = VK_NULL_HANDLE;
    VkBuffer indices = VK_NULL_HANDLE;
    VkDeviceMemory memory[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
};

// Parses and uploads `data`; false (with `*error` or a Vulkan error in `*result`) leaves nothing to destroy.
bool loadStationMesh(const Device& device, const uint8_t* data, size_t size, StationMesh* out, std::string* error,
                     VkResult* result);
void destroyStationMesh(VkDevice dev, StationMesh* mesh);

// The station exterior (P3.6) seen from the Cupola eye, its wings tracked to the Sun, rendered at the Earth
// resolution into its own target: encodeHdr of the exposed radiance, alpha = coverage (earth.frag binding 21).
// One image serves every frame in flight, ordered by its render-pass dependencies like EarthPass.
class StationPass {
public:
    // Poses and culls the station for `inputs`; `*draw` is false (and nothing changes) when the interior is off,
    // DEBUG_NO_STATION is set, the mesh is missing or no node is in view. Else (re)creates the target for the full
    // (physical) extent x `scale` (1 without a scaled Earth pass), waiting for the queue before replacing it.
    VkResult prepare(Device& device, const float* inputs, VkExtent2D full, float scale, int32_t rotationQuarterTurns,
                     bool* draw);
    void destroy(VkDevice dev);
    // Records the draws of the last prepare() that set `*draw`; `scene` must not bind this target.
    void record(const Device& device, VkCommandBuffer cmd, VkDescriptorSet scene) const;

    VkImageView view = VK_NULL_HANDLE;

private:
    VkResult ensure(Device& device, VkExtent2D full, float scale);
    void destroyTarget(VkDevice dev);

    const StationMesh* mesh_ = nullptr;
    std::vector<StationPush> pushes_;
    std::vector<uint32_t> nodes_;
    VkImage images_[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};  // colour, depth
    VkDeviceMemory memory_[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkImageView depthView_ = VK_NULL_HANDLE;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkFramebuffer framebuffer_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkExtent2D full_{};
    VkExtent2D extent_{};
    float scale_ = 0.0f;
};

}  // namespace cupola
