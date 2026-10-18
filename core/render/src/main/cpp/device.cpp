#include "device.h"

#include <unistd.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

#include "frame_inputs.h"
#include "shaders.h"

namespace cupola {
namespace {

Device* gDevice = nullptr;
int gRefs = 0;
int64_t gLastGpuTimeNs = -1;

#if CUPOLA_DEBUG
pid_t gRenderTid = 0;

VKAPI_ATTR VkBool32 VKAPI_CALL onDebugMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
                                              VkDebugUtilsMessageTypeFlagsEXT,
                                              const VkDebugUtilsMessengerCallbackDataEXT* data, void*) {
    int priority = severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? ANDROID_LOG_ERROR
                   : severity >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT ? ANDROID_LOG_WARN
                                                                                : ANDROID_LOG_INFO;
    __android_log_print(priority, "CupolaVulkan", "%s", data->pMessage);
    return VK_FALSE;
}

bool hasLayer(const char* name) {
    uint32_t count = 0;
    vkEnumerateInstanceLayerProperties(&count, nullptr);
    std::vector<VkLayerProperties> layers(count);
    vkEnumerateInstanceLayerProperties(&count, layers.data());
    return std::any_of(layers.begin(), layers.end(),
                       [name](const VkLayerProperties& l) { return std::strcmp(l.layerName, name) == 0; });
}

bool hasInstanceExtension(const char* layer, const char* name) {
    uint32_t count = 0;
    vkEnumerateInstanceExtensionProperties(layer, &count, nullptr);
    std::vector<VkExtensionProperties> exts(count);
    vkEnumerateInstanceExtensionProperties(layer, &count, exts.data());
    return std::any_of(exts.begin(), exts.end(),
                       [name](const VkExtensionProperties& e) { return std::strcmp(e.extensionName, name) == 0; });
}
#endif

constexpr const char* kValidationLayer = "VK_LAYER_KHRONOS_validation";

}  // namespace

void bindRenderThread() {
#if CUPOLA_DEBUG
    if (gRenderTid != 0 && gRenderTid != gettid() && gDevice != nullptr) {
        __android_log_assert(nullptr, CUPOLA_LOG_TAG, "render thread rebound while the device is alive");
    }
    gRenderTid = gettid();
#endif
}

void assertRenderThread(const char* where) {
#if CUPOLA_DEBUG
    if (gRenderTid != gettid()) {
        __android_log_assert(nullptr, CUPOLA_LOG_TAG, "%s called off the render thread (tid %d, render %d)", where,
                             gettid(), gRenderTid);
    }
#else
    (void)where;
#endif
}

PushConstants makePushConstants(const float* inputs, VkExtent2D extent, int32_t rotationQuarterTurns) {
    float iterations = std::clamp(inputs[inputs::kLoadIterations], 0.0f, 1.0e6f);
    return PushConstants{
        static_cast<int32_t>(extent.width),
        static_cast<int32_t>(extent.height),
        rotationQuarterTurns,
        static_cast<int32_t>(iterations),
        inputs[inputs::kLoad],
        inputs[inputs::kTimeSeconds],
    };
}

Device* Device::acquire(VkResult* error) {
    if (gDevice == nullptr) {
        auto* device = new Device();
        VkResult result = device->init();
        if (result != VK_SUCCESS) {
            delete device;
            *error = result;
            return nullptr;
        }
        gDevice = device;
    }
    ++gRefs;
    *error = VK_SUCCESS;
    return gDevice;
}

void Device::release() {
    if (gDevice == nullptr || --gRefs > 0) return;
    delete gDevice;
    gDevice = nullptr;
    gRefs = 0;
}

Device* Device::current() { return gDevice; }

int64_t Device::lastGpuTimeNs() { return gLastGpuTimeNs; }

VkResult Device::init() {
    uint32_t loaderVersion = VK_API_VERSION_1_0;
    vkEnumerateInstanceVersion(&loaderVersion);
    if (loaderVersion < VK_API_VERSION_1_1) return VK_ERROR_INCOMPATIBLE_DRIVER;

    std::vector<const char*> layers;
    std::vector<const char*> extensions = {VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME};
    bool debugUtils = false;
#if CUPOLA_DEBUG
    bool validation = hasLayer(kValidationLayer);
    if (validation) layers.push_back(kValidationLayer);
    debugUtils = hasInstanceExtension(nullptr, VK_EXT_DEBUG_UTILS_EXTENSION_NAME) ||
                 (validation && hasInstanceExtension(kValidationLayer, VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
    if (debugUtils) extensions.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
    LOGI("validation layer %s, debug utils %s", validation ? "on" : "absent", debugUtils ? "on" : "absent");
#else
    (void)kValidationLayer;
    (void)debugUtils;
#endif

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Cupola";
    app.pEngineName = "Cupola";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    instanceInfo.pApplicationInfo = &app;
    instanceInfo.enabledLayerCount = static_cast<uint32_t>(layers.size());
    instanceInfo.ppEnabledLayerNames = layers.data();
    instanceInfo.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    instanceInfo.ppEnabledExtensionNames = extensions.data();
    VK_TRY(vkCreateInstance(&instanceInfo, nullptr, &instance));

#if CUPOLA_DEBUG
    if (debugUtils) {
        auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
            vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT"));
        VkDebugUtilsMessengerCreateInfoEXT info{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
        info.messageSeverity =
            VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
        info.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT |
                           VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT |
                           VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
        info.pfnUserCallback = onDebugMessage;
        if (create != nullptr) create(instance, &info, nullptr, &messenger_);
    }
#endif

    uint32_t count = 0;
    VK_TRY(vkEnumeratePhysicalDevices(instance, &count, nullptr));
    std::vector<VkPhysicalDevice> candidates(count);
    VK_TRY(vkEnumeratePhysicalDevices(instance, &count, candidates.data()));
    int bestScore = -1;
    for (VkPhysicalDevice candidate : candidates) {
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(candidate, &props);
        if (props.apiVersion < VK_API_VERSION_1_1) continue;
        uint32_t familyCount = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
        std::vector<VkQueueFamilyProperties> families(familyCount);
        vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
        for (uint32_t i = 0; i < familyCount; ++i) {
            if ((families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            int score = props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU ? 0 : 1;
            if (score > bestScore) {
                bestScore = score;
                physical = candidate;
                queueFamily = i;
                properties = props;
                timestampMask = families[i].timestampValidBits >= 64
                                    ? ~0ull
                                    : ((1ull << families[i].timestampValidBits) - 1);
            }
            break;
        }
    }
    if (physical == VK_NULL_HANDLE) return VK_ERROR_INCOMPATIBLE_DRIVER;
    timestamps = timestampMask != 0 && properties.limits.timestampPeriod > 0.0f;

    VkPhysicalDeviceFeatures supported;
    vkGetPhysicalDeviceFeatures(physical, &supported);
    astcLdr = supported.textureCompressionASTC_LDR == VK_TRUE;
    VkPhysicalDeviceFeatures enabled{};
    enabled.textureCompressionASTC_LDR = supported.textureCompressionASTC_LDR;

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;
    const char* deviceExtensions[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = 1;
    deviceInfo.ppEnabledExtensionNames = deviceExtensions;
    deviceInfo.pEnabledFeatures = &enabled;
    VK_TRY(vkCreateDevice(physical, &deviceInfo, nullptr, &device));
    vkGetDeviceQueue(device, queueFamily, 0, &queue);

    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    VK_TRY(vkCreateCommandPool(device, &poolInfo, nullptr, &commandPool));

    VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    shaderInfo.codeSize = sizeof(shaders::kFullscreenVert);
    shaderInfo.pCode = shaders::kFullscreenVert;
    VK_TRY(vkCreateShaderModule(device, &shaderInfo, nullptr, &vertexShader));
    shaderInfo.codeSize = sizeof(shaders::kPatternFrag);
    shaderInfo.pCode = shaders::kPatternFrag;
    VK_TRY(vkCreateShaderModule(device, &shaderInfo, nullptr, &fragmentShader));

    VkPushConstantRange range{VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PushConstants)};
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.pushConstantRangeCount = 1;
    layoutInfo.pPushConstantRanges = &range;
    VK_TRY(vkCreatePipelineLayout(device, &layoutInfo, nullptr, &pipelineLayout));

    LOGI("device %s api %u.%u.%u driver 0x%x astc %d timestamps %d", properties.deviceName,
         VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion),
         VK_API_VERSION_PATCH(properties.apiVersion), properties.driverVersion, astcLdr, timestamps);
    return VK_SUCCESS;
}

Device::~Device() {
    if (device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(device);
        vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
        vkDestroyShaderModule(device, fragmentShader, nullptr);
        vkDestroyShaderModule(device, vertexShader, nullptr);
        vkDestroyCommandPool(device, commandPool, nullptr);
        vkDestroyDevice(device, nullptr);
    }
    if (instance != VK_NULL_HANDLE) {
        if (messenger_ != VK_NULL_HANDLE) {
            auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT"));
            if (destroy != nullptr) destroy(instance, messenger_, nullptr);
        }
        vkDestroyInstance(instance, nullptr);
    }
}

VkResult Device::createRenderPass(VkFormat format, VkImageLayout finalLayout, VkRenderPass* out) const {
    VkAttachmentDescription color{};
    color.format = format;
    color.samples = VK_SAMPLE_COUNT_1_BIT;
    color.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    color.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    color.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    color.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    color.finalLayout = finalLayout;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &ref;
    VkSubpassDependency deps[2]{};
    // Orders the layout transition after the acquire semaphore wait (waited at colour output).
    deps[0].srcSubpass = VK_SUBPASS_EXTERNAL;
    deps[0].dstSubpass = 0;
    deps[0].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[0].dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].srcSubpass = 0;
    deps[1].dstSubpass = VK_SUBPASS_EXTERNAL;
    deps[1].srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    deps[1].dstStageMask = VK_PIPELINE_STAGE_TRANSFER_BIT;
    deps[1].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    deps[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    VkRenderPassCreateInfo info{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    info.attachmentCount = 1;
    info.pAttachments = &color;
    info.subpassCount = 1;
    info.pSubpasses = &subpass;
    info.dependencyCount = 2;
    info.pDependencies = deps;
    return vkCreateRenderPass(device, &info, nullptr, out);
}

VkResult Device::createPipeline(VkRenderPass renderPass, VkPipeline* out) const {
    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vertexShader;
    stages[0].pName = "main";
    stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = fragmentShader;
    stages[1].pName = "main";
    VkPipelineVertexInputStateCreateInfo vertexInput{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo assembly{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    assembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo viewport{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    viewport.viewportCount = 1;
    viewport.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo raster{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    raster.polygonMode = VK_POLYGON_MODE_FILL;
    raster.cullMode = VK_CULL_MODE_NONE;
    raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    raster.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo multisample{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState blendAttachment{};
    blendAttachment.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT |
                                     VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
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
    info.pColorBlendState = &blend;
    info.pDynamicState = &dynamic;
    info.layout = pipelineLayout;
    info.renderPass = renderPass;
    return vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &info, nullptr, out);
}

uint32_t Device::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags flags) const {
    VkPhysicalDeviceMemoryProperties memory;
    vkGetPhysicalDeviceMemoryProperties(physical, &memory);
    for (uint32_t i = 0; i < memory.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) != 0 && (memory.memoryTypes[i].propertyFlags & flags) == flags) return i;
    }
    return UINT32_MAX;
}

VkResult Device::createTimestampPool(uint32_t count, VkQueryPool* out) const {
    *out = VK_NULL_HANDLE;
    if (!timestamps) return VK_SUCCESS;
    VkQueryPoolCreateInfo info{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    info.queryType = VK_QUERY_TYPE_TIMESTAMP;
    info.queryCount = count;
    return vkCreateQueryPool(device, &info, nullptr, out);
}

void Device::recordFrame(VkCommandBuffer cmd, VkRenderPass renderPass, VkFramebuffer framebuffer,
                         VkPipeline pipeline, VkExtent2D extent, const PushConstants& push, VkQueryPool queries,
                         uint32_t first) const {
    if (queries != VK_NULL_HANDLE) {
        vkCmdResetQueryPool(cmd, queries, first, 2);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries, first);
    }
    VkRenderPassBeginInfo begin{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    begin.renderPass = renderPass;
    begin.framebuffer = framebuffer;
    begin.renderArea.extent = extent;
    vkCmdBeginRenderPass(cmd, &begin, VK_SUBPASS_CONTENTS_INLINE);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
    VkViewport viewport{0.0f, 0.0f, static_cast<float>(extent.width), static_cast<float>(extent.height), 0.0f, 1.0f};
    VkRect2D scissor{{0, 0}, extent};
    vkCmdSetViewport(cmd, 0, 1, &viewport);
    vkCmdSetScissor(cmd, 0, 1, &scissor);
    vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, sizeof(PushConstants), &push);
    vkCmdDraw(cmd, 3, 1, 0, 0);
    vkCmdEndRenderPass(cmd);
    if (queries != VK_NULL_HANDLE) {
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queries, first + 1);
    }
}

void Device::collectGpuTime(VkQueryPool queries, uint32_t first) {
    if (queries == VK_NULL_HANDLE) return;
    uint64_t ticks[2] = {};
    if (vkGetQueryPoolResults(device, queries, first, 2, sizeof(ticks), ticks, sizeof(uint64_t),
                              VK_QUERY_RESULT_64_BIT) != VK_SUCCESS) {
        return;
    }
    uint64_t delta = ((ticks[1] & timestampMask) - (ticks[0] & timestampMask)) & timestampMask;
    gLastGpuTimeNs = static_cast<int64_t>(std::llround(static_cast<double>(delta) * properties.limits.timestampPeriod));
}

}  // namespace cupola
