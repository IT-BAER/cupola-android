#include "scene_binding.h"

#include <cstring>

#include "frame_inputs.h"
#include "texture_set.h"

namespace cupola {
namespace {

constexpr uint32_t kSets = 3;

}  // namespace

VkResult SceneBinding::init(Device& device) {
    VkDevice dev = device.device;
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, kSets},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, kSets * (kBindingCount - 1)},
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = kSets;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = sizes;
    VK_TRY(vkCreateDescriptorPool(dev, &poolInfo, nullptr, &pool_));
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = pool_;
    const VkDescriptorSetLayout layouts[kSets] = {device.sceneSetLayout, device.sceneSetLayout, device.sceneSetLayout};
    VkDescriptorSet sets[kSets];
    alloc.descriptorSetCount = kSets;
    alloc.pSetLayouts = layouts;
    VK_TRY(vkAllocateDescriptorSets(dev, &alloc, sets));
    set = sets[0];
    earthSet = sets[1];
    skySet = sets[2];

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

VkResult SceneBinding::update(Device& device, const float* inputs, VkImageView earthImage, const SkyPass& sky) {
    const TextureSet* textures = nullptr;
    VK_TRY(device.textures(&textures));
    const VkImageView placeholder = textures->textures[kInteriorEarth].view;
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
    const VkDescriptorImageInfo earthInfo = image(textures->clamp, earthImage);
    const VkWriteDescriptorSet earthWrite = write(set, kEarthImageBinding, 1, &earthInfo);
    vkUpdateDescriptorSets(device.device, 1, &earthWrite, 0, nullptr);
    // `set` and `earthSet` read the view LUT; `skySet` (the pass that renders it) gets placeholders.
    const VkImageView inscatter = sky.inscatter != VK_NULL_HANDLE ? sky.inscatter : placeholder;
    const VkImageView transmittance = sky.transmittance != VK_NULL_HANDLE ? sky.transmittance : placeholder;
    if (inscatter != skyInscatter_) {
        const VkDescriptorImageInfo views[2] = {image(textures->clamp, inscatter), image(textures->clamp, transmittance)};
        const VkWriteDescriptorSet w[2] = {write(set, kSkyInscatterBinding, 2, views),
                                           write(earthSet, kSkyInscatterBinding, 2, views)};
        vkUpdateDescriptorSets(device.device, 2, w, 0, nullptr);
        skyInscatter_ = inscatter;
    }
    if (!texturesBound_) {
        VkDescriptorImageInfo images[kTextureSlots];
        for (uint32_t s = 0; s < kTextureSlots; ++s) images[s] = image(textures->sampler(s), textures->textures[s].view);
        VkDescriptorImageInfo generated[kGeneratedSlots];
        for (uint32_t g = 0; g < kGeneratedSlots; ++g) {
            generated[g] = image(textures->generatedSampler(g), textures->generated[g].view);
        }
        const VkDescriptorImageInfo hold = image(textures->clamp, placeholder);
        const VkDescriptorImageInfo holdPair[2] = {hold, hold};
        const VkWriteDescriptorSet w[] = {
            write(set, 1, kTextureSlots, images),
            write(earthSet, 1, kTextureSlots, images),
            write(skySet, 1, kTextureSlots, images),
            write(set, kGeneratedBinding, kGeneratedSlots, generated),
            write(earthSet, kGeneratedBinding, kGeneratedSlots, generated),
            write(skySet, kGeneratedBinding, kGeneratedSlots, generated),
            write(earthSet, kEarthImageBinding, 1, &hold),
            write(skySet, kEarthImageBinding, 1, &hold),
            write(skySet, kSkyInscatterBinding, 2, holdPair),
        };
        vkUpdateDescriptorSets(device.device, sizeof(w) / sizeof(w[0]), w, 0, nullptr);
        texturesBound_ = true;
    }
    std::memcpy(mapped_, inputs + inputs::kSceneBlock, sizeof(float) * (inputs::kCount - inputs::kSceneBlock));
    return VK_SUCCESS;
}

}  // namespace cupola
