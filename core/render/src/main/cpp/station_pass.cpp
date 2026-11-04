#include "station_pass.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "frame_inputs.h"
#include "shaders.h"

namespace cupola {
namespace {

constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_SRGB;  // as EarthPass: the composite samples linear values
constexpr VkFormat kDepthFormat = VK_FORMAT_D16_UNORM;
// Reverse-Z near plane (m from the eye); the exterior starts beyond the Cupola glass, 1.5 m away.
constexpr double kNearM = 0.5;

struct GpuVertex {
    float position[3];
    int16_t normal[4];  // w = 0
    uint16_t uv[2];
};
static_assert(sizeof(GpuVertex) == 24);

VkResult createBuffer(const Device& device, VkBufferUsageFlags usage, const void* data, size_t bytes, VkBuffer* buffer,
                      VkDeviceMemory* memory) {
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = bytes;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_TRY(vkCreateBuffer(device.device, &info, nullptr, buffer));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(device.device, *buffer, &req);
    const VkMemoryPropertyFlags coherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    VK_TRY(device.allocate(req, coherent, coherent, memory));
    VK_TRY(vkBindBufferMemory(device.device, *buffer, *memory, 0));
    void* mapped = nullptr;
    VK_TRY(vkMapMemory(device.device, *memory, 0, VK_WHOLE_SIZE, 0, &mapped));
    std::memcpy(mapped, data, bytes);
    vkUnmapMemory(device.device, *memory);
    return VK_SUCCESS;
}

VkResult createRenderPass(VkDevice dev, VkRenderPass* out) {
    VkAttachmentDescription attachments[2]{};
    attachments[0].format = kFormat;
    attachments[0].samples = VK_SAMPLE_COUNT_1_BIT;
    attachments[0].loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    attachments[0].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    attachments[0].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    attachments[0].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[0].initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    attachments[0].finalLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    attachments[1] = attachments[0];
    attachments[1].format = kDepthFormat;
    attachments[1].storeOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    attachments[1].finalLayout = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
    VkAttachmentReference color{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkAttachmentReference depth{1, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &color;
    subpass.pDepthStencilAttachment = &depth;
    VkSubpassDependency deps[2]{};
    // Earlier composites read the colour image, and the previous frame's pass wrote the depth image.
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
    deps[0].srcAccessMask = VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT |
                            VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 2;
    info.pAttachments = attachments;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 2;
    info.pDependencies = deps;
    return vkCreateRenderPass(dev, &info, nullptr, out);
}

VkResult createPipeline(const Device& device, VkRenderPass renderPass, VkPipelineLayout layout, VkPipeline* out) {
    VkDevice dev = device.device;
    VkShaderModule modules[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    shaderInfo.codeSize = sizeof(shaders::kStationVert);
    shaderInfo.pCode = shaders::kStationVert;
    VkResult result = vkCreateShaderModule(dev, &shaderInfo, nullptr, &modules[0]);
    if (result == VK_SUCCESS) {
        shaderInfo.codeSize = sizeof(shaders::kStationFrag);
        shaderInfo.pCode = shaders::kStationFrag;
        result = vkCreateShaderModule(dev, &shaderInfo, nullptr, &modules[1]);
    }
    if (result == VK_SUCCESS) {
        VkPipelineShaderStageCreateInfo stages[2]{};
        for (int i = 0; i < 2; ++i) {
            stages[i].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[i].stage = i == 0 ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
            stages[i].module = modules[i];
            stages[i].pName = "main";
        }
        VkVertexInputBindingDescription binding{0, sizeof(GpuVertex), VK_VERTEX_INPUT_RATE_VERTEX};
        VkVertexInputAttributeDescription attributes[3] = {
            {0, 0, VK_FORMAT_R32G32B32_SFLOAT, offsetof(GpuVertex, position)},
            {1, 0, VK_FORMAT_R16G16B16A16_SNORM, offsetof(GpuVertex, normal)},
            {2, 0, VK_FORMAT_R16G16_UNORM, offsetof(GpuVertex, uv)},
        };
        VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
        vertexInput.vertexBindingDescriptionCount = 1;
        vertexInput.pVertexBindingDescriptions = &binding;
        vertexInput.vertexAttributeDescriptionCount = 3;
        vertexInput.pVertexAttributeDescriptions = attributes;
        VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
        assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
        VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
        viewport.viewportCount = 1;
        viewport.scissorCount = 1;
        VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = VK_CULL_MODE_NONE;  // thin two-sided parts; the winding also flips with the pre-rotation
        raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        raster.lineWidth = 1.0f;
        VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
        VkPipelineDepthStencilStateCreateInfo depth{VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO};
        depth.depthTestEnable = VK_TRUE;
        depth.depthWriteEnable = VK_TRUE;
        depth.depthCompareOp = VK_COMPARE_OP_GREATER;  // reverse-Z, cleared to 0
        VkPipelineColorBlendAttachmentState blendAttachment{};
        blendAttachment.colorWriteMask =
            VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
        VkPipelineColorBlendStateCreateInfo blend{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
        blend.attachmentCount = 1;
        blend.pAttachments = &blendAttachment;
        VkDynamicState dynamicStates[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
        VkPipelineDynamicStateCreateInfo dynamic{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
        dynamic.dynamicStateCount = 2;
        dynamic.pDynamicStates = dynamicStates;
        VkGraphicsPipelineCreateInfo info{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
        info.stageCount = 2;
        info.pStages = stages;
        info.pVertexInputState = &vertexInput;
        info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewport;
        info.pRasterizationState = &raster;
        info.pMultisampleState = &multisample;
        info.pDepthStencilState = &depth;
        info.pColorBlendState = &blend;
        info.pDynamicState = &dynamic;
        info.layout = layout;
        info.renderPass = renderPass;
        result = vkCreateGraphicsPipelines(dev, VK_NULL_HANDLE, 1, &info, nullptr, out);
    }
    vkDestroyShaderModule(dev, modules[0], nullptr);
    vkDestroyShaderModule(dev, modules[1], nullptr);
    return result;
}

using Mat4 = double[4][4];

double dot3(const float* a, const float* b) {
    return static_cast<double>(a[0]) * b[0] + static_cast<double>(a[1]) * b[1] + static_cast<double>(a[2]) * b[2];
}

// Rest-pose position to clip space at the `inputs` camera, without the node pose: Cupola frame to camera (eye at
// earth.frag EYE_Z, rows the interior camera axes), earth.frag cameraRay's projection with reverse-Z, then the
// physical pre-rotation (inverse of earth.frag logicalPixel).
void cameraClip(const float* inputs, VkExtent2D full, int32_t rotation, Mat4 out) {
    const float* axes[3] = {inputs + inputs::kInteriorRight, inputs + inputs::kInteriorUp,
                            inputs + inputs::kInteriorBack};
    const double eyeZ = 1.4405 - 1.55;
    double view[3][4];
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) view[i][j] = axes[i][j];
        view[i][3] = -axes[i][2] * eyeZ;
    }
    double w = full.width, h = full.height;
    if (rotation % 2 == 1) std::swap(w, h);
    const double tanLong = inputs[inputs::kTanHalfFovLong];
    const double tanX = tanLong * w / std::max(w, h);
    const double tanY = tanLong * h / std::max(w, h);
    double x[4], y[4];
    for (int j = 0; j < 4; ++j) {
        x[j] = view[0][j] / tanX;
        y[j] = -view[1][j] / tanY;
        out[2][j] = j == 3 ? kNearM : 0.0;
        out[3][j] = -view[2][j];
    }
    for (int j = 0; j < 4; ++j) {
        switch (rotation) {
            case 1: out[0][j] = -y[j], out[1][j] = x[j]; break;
            case 2: out[0][j] = -x[j], out[1][j] = -y[j]; break;
            case 3: out[0][j] = y[j], out[1][j] = -x[j]; break;
            default: out[0][j] = x[j], out[1][j] = y[j]; break;
        }
    }
}

// True when the clip-space points all lie outside one plane (x, y in [-w, w], reverse-Z depth z <= w).
template <typename Point>
bool culled(const Mat4 m, size_t count, Point point) {
    bool inside[5] = {};
    for (size_t c = 0; c < count; ++c) {
        double p[3];
        point(c, p);
        double v[4];
        for (int i = 0; i < 4; ++i) v[i] = m[i][0] * p[0] + m[i][1] * p[1] + m[i][2] * p[2] + m[i][3];
        inside[0] |= v[0] >= -v[3];
        inside[1] |= v[0] <= v[3];
        inside[2] |= v[1] >= -v[3];
        inside[3] |= v[1] <= v[3];
        inside[4] |= v[2] <= v[3];
        if (std::all_of(inside, inside + 5, [](bool b) { return b; })) return false;
    }
    return true;
}

// The rest-pose box first, then the vertices: a tilted wing's box reaches into views its panel does not.
bool culled(const Mat4 m, const StationMesh::Range& r, const station::Node& node) {
    const auto corner = [&r](size_t c, double* p) {
        for (int i = 0; i < 3; ++i) p[i] = (c >> i & 1) ? r.hi[i] : r.lo[i];
    };
    const auto vertex = [&node](size_t c, double* p) {
        for (int i = 0; i < 3; ++i) p[i] = node.vertices[c].position[i];
    };
    return culled(m, 8, corner) || culled(m, node.vertices.size(), vertex);
}

}  // namespace

bool loadStationMesh(const Device& device, const uint8_t* data, size_t size, StationMesh* out, std::string* error,
                     VkResult* result) {
    *result = VK_SUCCESS;
    if (!station::parse(data, size, &out->nodes, error)) return false;
    std::vector<GpuVertex> vertices;
    std::vector<uint32_t> indices;
    for (station::Node& node : out->nodes) {
        StationMesh::Range range{static_cast<uint32_t>(indices.size()), static_cast<uint32_t>(node.indices.size()),
                                 static_cast<int32_t>(vertices.size()), {}, {}};
        std::fill(range.lo, range.lo + 3, INFINITY);
        std::fill(range.hi, range.hi + 3, -INFINITY);
        for (const station::Vertex& v : node.vertices) {
            vertices.push_back({{v.position[0], v.position[1], v.position[2]},
                                {v.normal[0], v.normal[1], v.normal[2], 0},
                                {v.uv[0], v.uv[1]}});
            for (int i = 0; i < 3; ++i) {
                range.lo[i] = std::min(range.lo[i], v.position[i]);
                range.hi[i] = std::max(range.hi[i], v.position[i]);
            }
        }
        indices.insert(indices.end(), node.indices.begin(), node.indices.end());
        out->ranges.push_back(range);
        node.indices = {};
    }
    if (vertices.empty() || indices.empty()) {
        *error = "no geometry";
        return false;
    }
    *result = createBuffer(device, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, vertices.data(),
                           vertices.size() * sizeof(GpuVertex), &out->vertices, &out->memory[0]);
    if (*result == VK_SUCCESS) {
        *result = createBuffer(device, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, indices.data(),
                               indices.size() * sizeof(uint32_t), &out->indices, &out->memory[1]);
    }
    if (*result != VK_SUCCESS) {
        destroyStationMesh(device.device, out);
        return false;
    }
    return true;
}

void destroyStationMesh(VkDevice dev, StationMesh* mesh) {
    vkDestroyBuffer(dev, mesh->vertices, nullptr);
    vkDestroyBuffer(dev, mesh->indices, nullptr);
    for (VkDeviceMemory m : mesh->memory) vkFreeMemory(dev, m, nullptr);
    *mesh = StationMesh{};
}

VkResult StationPass::prepare(Device& device, const float* inputs, VkExtent2D full, float scale,
                              int32_t rotationQuarterTurns, bool* draw) {
    *draw = false;
    if (inputs[inputs::kInterior] != 1.0f ||
        (static_cast<int32_t>(inputs[inputs::kEffects]) & inputs::kDebugNoStation) != 0) {
        return VK_SUCCESS;
    }
    VK_TRY(device.station(&mesh_));
    if (mesh_ == nullptr) return VK_SUCCESS;

    // The Sun in the Cupola frame: ECEF to camera, then the camera axes in the Cupola frame.
    const float* sunEcef = inputs + inputs::kSunDir;
    const double sunCam[3] = {dot3(sunEcef, inputs + inputs::kCameraRight), dot3(sunEcef, inputs + inputs::kCameraUp),
                              dot3(sunEcef, inputs + inputs::kCameraBack)};
    float sun[3];
    for (int i = 0; i < 3; ++i) {
        sun[i] = static_cast<float>(inputs[inputs::kInteriorRight + i] * sunCam[0] +
                                    inputs[inputs::kInteriorUp + i] * sunCam[1] +
                                    inputs[inputs::kInteriorBack + i] * sunCam[2]);
    }
    const std::vector<station::Pose> poses = station::poses(mesh_->nodes, sun);
    Mat4 camera;
    cameraClip(inputs, full, rotationQuarterTurns, camera);
    pushes_.clear();
    nodes_.clear();
    for (uint32_t n = 0; n < mesh_->ranges.size(); ++n) {
        const station::Pose& pose = poses[n];
        Mat4 m;
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 4; ++j) {
                m[i][j] = camera[i][0] * pose.m[0][j] + camera[i][1] * pose.m[1][j] + camera[i][2] * pose.m[2][j] +
                          (j == 3 ? camera[i][3] : 0.0);
            }
        }
        if (mesh_->ranges[n].indexCount == 0 || culled(m, mesh_->ranges[n], mesh_->nodes[n])) continue;
        StationPush push{};
        for (int i = 0; i < 4; ++i) {
            for (int j = 0; j < 4; ++j) push.clip[j * 4 + i] = static_cast<float>(m[i][j]);
        }
        std::memcpy(push.pose, pose.m, sizeof(push.pose));
        pushes_.push_back(push);
        nodes_.push_back(n);
    }
    if (pushes_.empty()) return VK_SUCCESS;
    VK_TRY(ensure(device, full, scale));
    *draw = true;
    return VK_SUCCESS;
}

VkResult StationPass::ensure(Device& device, VkExtent2D full, float scale) {
    if (images_[0] != VK_NULL_HANDLE && full.width == full_.width && full.height == full_.height && scale == scale_) {
        return VK_SUCCESS;
    }
    VkDevice dev = device.device;
    if (images_[0] != VK_NULL_HANDLE) {
        VK_TRY(vkQueueWaitIdle(device.queue));
        destroyTarget(dev);
    }
    full_ = full;
    scale_ = scale;
    // EarthPass's rounding: the composite samples both targets at the same texel centres.
    extent_ = {std::max(1u, static_cast<uint32_t>(std::lround(full.width * scale))),
               std::max(1u, static_cast<uint32_t>(std::lround(full.height * scale)))};
    VkImageView views[2] = {VK_NULL_HANDLE, VK_NULL_HANDLE};
    for (int i = 0; i < 2; ++i) {
        VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        imageInfo.imageType = VK_IMAGE_TYPE_2D;
        imageInfo.format = i == 0 ? kFormat : kDepthFormat;
        imageInfo.extent = {extent_.width, extent_.height, 1};
        imageInfo.mipLevels = 1;
        imageInfo.arrayLayers = 1;
        imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
        imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
        imageInfo.usage = i == 0 ? VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                                 : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
        imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        VK_TRY(vkCreateImage(dev, &imageInfo, nullptr, &images_[i]));
        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(dev, images_[i], &req);
        VK_TRY(device.allocate(req,
                               i == 0 ? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT : VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT,
                               0, &memory_[i]));
        VK_TRY(vkBindImageMemory(dev, images_[i], memory_[i], 0));
        VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        viewInfo.image = images_[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = imageInfo.format;
        viewInfo.subresourceRange = {i == 0 ? VK_IMAGE_ASPECT_COLOR_BIT : VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1};
        VK_TRY(vkCreateImageView(dev, &viewInfo, nullptr, &views[i]));
        (i == 0 ? view : depthView_) = views[i];
    }
    if (renderPass_ == VK_NULL_HANDLE) VK_TRY(createRenderPass(dev, &renderPass_));
    VkFramebufferCreateInfo fbInfo{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
    fbInfo.renderPass = renderPass_;
    fbInfo.attachmentCount = 2;
    fbInfo.pAttachments = views;
    fbInfo.width = extent_.width;
    fbInfo.height = extent_.height;
    fbInfo.layers = 1;
    VK_TRY(vkCreateFramebuffer(dev, &fbInfo, nullptr, &framebuffer_));
    if (pipeline_ != VK_NULL_HANDLE) return VK_SUCCESS;
    VkPushConstantRange range{VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(StationPush)};
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &device.sceneSetLayout;
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &range;
    VK_TRY(vkCreatePipelineLayout(dev, &layoutInfo, nullptr, &layout_));
    return createPipeline(device, renderPass_, layout_, &pipeline_);
}

void StationPass::destroyTarget(VkDevice dev) {
    vkDestroyFramebuffer(dev, framebuffer_, nullptr);
    vkDestroyImageView(dev, view, nullptr);
    vkDestroyImageView(dev, depthView_, nullptr);
    for (int i = 0; i < 2; ++i) {
        vkDestroyImage(dev, images_[i], nullptr);
        vkFreeMemory(dev, memory_[i], nullptr);
        images_[i] = VK_NULL_HANDLE;
        memory_[i] = VK_NULL_HANDLE;
    }
    framebuffer_ = VK_NULL_HANDLE;
    view = VK_NULL_HANDLE;
    depthView_ = VK_NULL_HANDLE;
}

void StationPass::destroy(VkDevice dev) {
    destroyTarget(dev);
    vkDestroyPipeline(dev, pipeline_, nullptr);
    vkDestroyPipelineLayout(dev, layout_, nullptr);
    vkDestroyRenderPass(dev, renderPass_, nullptr);
    *this = StationPass{};
}

void StationPass::record(const Device&, VkCommandBuffer cmd, VkDescriptorSet scene) const {
    VkClearValue clears[2]{};  // colour (0, 0, 0, 0): no coverage; depth 0: reverse-Z far
    VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    begin.renderPass = renderPass_;
    begin.framebuffer = framebuffer_;
    begin.renderArea.extent = extent_;
    begin.clearValueCount = 2;
    begin.pClearValues = clears;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout_, 0, 1, &scene, 0, nullptr);
    VkViewport viewport{0.0f, 0.0f, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, extent_};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    const VkDeviceSize offset = 0;
    vkCmdBindVertexBuffers(cmd, 0, 1, &mesh_->vertices, &offset);
    vkCmdBindIndexBuffer(cmd, mesh_->indices, 0, VK_INDEX_TYPE_UINT32);
    for (size_t i = 0; i < pushes_.size(); ++i) {
        const StationMesh::Range& r = mesh_->ranges[nodes_[i]];
        vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_VERTEX_BIT, 0, sizeof(StationPush), &pushes_[i]);
        vkCmdDrawIndexed(cmd, r.indexCount, 1, r.firstIndex, r.vertexOffset, 0);
    }
    vkCmdEndRenderPass(cmd);
}

}  // namespace cupola
