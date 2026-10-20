#include "scene_binding.h"

#include <cstring>

#include "frame_inputs.h"
#include "texture_set.h"

namespace cupola {

VkResult SceneBinding::init(Device& device) {
    VkDevice dev = device.device;
    VkDescriptorPoolSize sizes[] = {
        {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2},
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2 * (kTextureSlots + 1)},
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 2;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = sizes;
    VK_TRY(vkCreateDescriptorPool(dev, &poolInfo, nullptr, &pool_));
    VkDescriptorSetAllocateInfo alloc{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    alloc.descriptorPool = pool_;
    const VkDescriptorSetLayout layouts[2] = {device.sceneSetLayout, device.sceneSetLayout};
    VkDescriptorSet sets[2];
    alloc.descriptorSetCount = 2;
    alloc.pSetLayouts = layouts;
    VK_TRY(vkAllocateDescriptorSets(dev, &alloc, sets));
    set = sets[0];
    earthSet = sets[1];

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
    VkWriteDescriptorSet writes[2];
    for (int i = 0; i < 2; ++i) {
        writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        writes[i].dstSet = sets[i];
        writes[i].dstBinding = 0;
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        writes[i].pBufferInfo = &uniform;
    }
    vkUpdateDescriptorSets(dev, 2, writes, 0, nullptr);
    return VK_SUCCESS;
}

void SceneBinding::destroy(VkDevice dev) {
    if (mapped_ != nullptr) vkUnmapMemory(dev, memory_);
    vkDestroyBuffer(dev, buffer_, nullptr);
    vkFreeMemory(dev, memory_, nullptr);
    vkDestroyDescriptorPool(dev, pool_, nullptr);
    *this = SceneBinding{};
}

VkResult SceneBinding::update(Device& device, const float* inputs, VkImageView earthImage) {
    const TextureSet* textures = nullptr;
    VK_TRY(device.textures(&textures));
    const VkImageView placeholder = textures->textures[kInteriorEarth].view;
    if (earthImage == VK_NULL_HANDLE) earthImage = placeholder;
    if (earthImage != earthImage_) {
        VkDescriptorImageInfo image{textures->clamp, earthImage, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet write{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        write.dstSet = set;
        write.dstBinding = kEarthImageBinding;
        write.descriptorCount = 1;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &image;
        vkUpdateDescriptorSets(device.device, 1, &write, 0, nullptr);
        earthImage_ = earthImage;
    }
    if (!texturesBound_) {
        VkDescriptorImageInfo images[kTextureSlots];
        for (uint32_t s = 0; s < kTextureSlots; ++s) {
            images[s] = {textures->sampler(s), textures->textures[s].view, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        }
        VkDescriptorImageInfo image{textures->clamp, placeholder, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
        VkWriteDescriptorSet writes[3];
        for (int i = 0; i < 3; ++i) {
            writes[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[i].dstSet = i == 0 ? set : earthSet;
            writes[i].dstBinding = i == 2 ? kEarthImageBinding : 1;
            writes[i].descriptorCount = i == 2 ? 1 : kTextureSlots;
            writes[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            writes[i].pImageInfo = i == 2 ? &image : images;
        }
        vkUpdateDescriptorSets(device.device, 3, writes, 0, nullptr);
        texturesBound_ = true;
    }
    std::memcpy(mapped_, inputs + inputs::kSceneBlock, sizeof(float) * (inputs::kCount - inputs::kSceneBlock));
    return VK_SUCCESS;
}

}  // namespace cupola
