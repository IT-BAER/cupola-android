#include "scene_binding.h"

#include <cstring>
#include <iterator>

#include "descriptor_bindings.h"
#include "frame_inputs.h"
#include "texture_set.h"

namespace cupola {
namespace {

constexpr uint32_t kEarthSets = 3;
constexpr uint32_t kSets = kEarthSets + 1;

}  // namespace

VkResult SceneBinding::init(Device& device) {
    VkDevice dev = device.device;
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, kSets},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,
         kEarthSets * (earth_binding::kCount - 1) + station_binding::kCount - 1},
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = kSets;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = sizes;
    VK_TRY(vkCreateDescriptorPool(dev, &poolInfo, nullptr, &pool_));
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = pool_;
    const VkDescriptorSetLayout layouts[kSets] = {device.sceneSetLayout, device.sceneSetLayout, device.sceneSetLayout,
                                                  device.stationSetLayout};
    VkDescriptorSet sets[kSets];
    alloc.descriptorSetCount = kSets;
    alloc.pSetLayouts = layouts;
    VK_TRY(vkAllocateDescriptorSets(dev, &alloc, sets));
    set = sets[0];
    earthSet = sets[1];
    skySet = sets[2];
    stationSet = sets[3];

    VkBufferCreateInfo bufferInfo{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bufferInfo.size = kSceneUniformBytes;
    bufferInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    VK_TRY(vkCreateBuffer(dev, &bufferInfo, nullptr, &buffer_));
    VkMemoryRequirements req;
    vkGetBufferMemoryRequirements(dev, buffer_, &req);
    const VkMemoryPropertyFlags coherent = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    VK_TRY(device.allocate(req, coherent, coherent, &memory_));
    VK_TRY(vkBindBufferMemory(dev, buffer_, memory_, 0));
    VK_TRY(vkMapMemory(dev, memory_, 0, VK_WHOLE_SIZE, 0, &mapped_));
    std::memset(mapped_, 0, kSceneUniformBytes);

    VkDescriptorBufferInfo uniform{buffer_, 0, kSceneUniformBytes};
    VkWriteDescriptorSet writes[kSets];
    for (uint32_t i = 0; i < kSets; ++i) {
        writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        writes[i].dstSet = sets[i];
        writes[i].dstBinding = 0;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[i].pBufferInfo = &uniform;
    }
    vkUpdateDescriptorSets(dev, kSets, writes, 0, nullptr);
    return VK_SUCCESS;
}

void SceneBinding::destroy(VkDevice dev) {
    if (mapped_ != nullptr) vkUnmapMemory(dev, memory_);
    vkDestroyBuffer(dev, buffer_, nullptr);
    vkFreeMemory(dev, memory_, nullptr);
    vkDestroyDescriptorPool(dev, pool_, nullptr);
    *this = SceneBinding{};
}

VkResult SceneBinding::update(Device& device, const float* inputs, VkImageView earthImage, const SkyPass& sky,
                              VkImageView stationImage) {
    const TextureSet* textures = nullptr;
    VK_TRY(device.textures(&textures));
    // Any view of the binding's type: 2D, and 2D array for the view LUT.
    const VkImageView placeholder = textures->textures[kInteriorEarth].view;
    const VkImageView arrayPlaceholder = textures->generated[kAtmosphereLuts].view;
    auto image = [](VkSampler sampler, VkImageView view) {
        return VkDescriptorImageInfo{sampler, view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    };
    auto write = [](VkDescriptorSet dst, uint32_t binding, uint32_t count, const VkDescriptorImageInfo* images) {
        VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        w.dstSet = dst;
        w.dstBinding = binding;
        w.descriptorCount = count;
        w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        w.pImageInfo = images;
        return w;
    };
    // Written every frame, not cached by handle: EarthPass recreates its view on a scale change and the driver may
    // return the destroyed view's handle value (A35), which a cache would take for the bound image.
    if (earthImage == VK_NULL_HANDLE) earthImage = placeholder;
    if (stationImage == VK_NULL_HANDLE) stationImage = placeholder;
    const VkDescriptorImageInfo earthInfo = image(textures->clamp, earthImage);
    const VkDescriptorImageInfo stationInfo = image(textures->clamp, stationImage);
    const VkWriteDescriptorSet frameWrites[3] = {write(set, earth_binding::kEarthImage, 1, &earthInfo),
                                                 write(set, earth_binding::kStationImage, 1, &stationInfo),
                                                 write(earthSet, earth_binding::kStationImage, 1, &stationInfo)};
    vkUpdateDescriptorSets(device.device, 3, frameWrites, 0, nullptr);
    // `set` and `earthSet` read the view LUT; `skySet` (the pass that renders it) gets a placeholder.
    const VkImageView skyView = sky.view != VK_NULL_HANDLE ? sky.view : arrayPlaceholder;
    if (skyView != skyView_) {
        const VkDescriptorImageInfo info = image(textures->clamp, skyView);
        const VkWriteDescriptorSet w[2] = {write(set, earth_binding::kSkyView, 1, &info),
                                           write(earthSet, earth_binding::kSkyView, 1, &info)};
        vkUpdateDescriptorSets(device.device, 2, w, 0, nullptr);
        skyView_ = skyView;
    }
    if (!texturesBound_) {
        const auto slot = [&](uint32_t s) { return image(textures->sampler(s), textures->textures[s].view); };
        const auto generated = [&](uint32_t g) {
            return image(textures->generatedSampler(g), textures->generated[g].view);
        };
        // earth_binding::kDayDetail to kNoiseTex in binding order.
        const VkDescriptorImageInfo fixed[] = {
            slot(kDayDetail), slot(kMonthlyColour), slot(kClouds), slot(kCityLights), slot(kTerrainNormal),
            slot(kStars), slot(kWaterMask), slot(kInteriorEarth), image(textures->clamp, textures->interiorLight.view),
            slot(kInteriorAmbient), slot(kCloudDetail), generated(kAtmosphereLuts), generated(kNoise),
        };
        static_assert(std::size(fixed) == earth_binding::kNoiseTex - earth_binding::kDayDetail + 1);
        static_assert(station_binding::kOrmMap == station_binding::kAlbedoMap + 1);
        const VkDescriptorImageInfo station[] = {slot(kStationAlbedo), slot(kStationOrm)};
        const VkDescriptorImageInfo hold = image(textures->clamp, placeholder);
        const VkDescriptorImageInfo holdArray = image(textures->clamp, arrayPlaceholder);
        const VkWriteDescriptorSet w[] = {
            write(set, earth_binding::kDayDetail, std::size(fixed), fixed),
            write(earthSet, earth_binding::kDayDetail, std::size(fixed), fixed),
            write(skySet, earth_binding::kDayDetail, std::size(fixed), fixed),
            write(earthSet, earth_binding::kEarthImage, 1, &hold),
            write(skySet, earth_binding::kEarthImage, 1, &hold),
            write(skySet, earth_binding::kSkyView, 1, &holdArray),
            write(skySet, earth_binding::kStationImage, 1, &hold),
            write(stationSet, station_binding::kAlbedoMap, std::size(station), station),
        };
        vkUpdateDescriptorSets(device.device, sizeof(w) / sizeof(w[0]), w, 0, nullptr);
        texturesBound_ = true;
    }
    std::memcpy(mapped_, inputs + inputs::kSceneBlock, sizeof(float) * (inputs::kCount - inputs::kSceneBlock));
    return VK_SUCCESS;
}

}  // namespace cupola
