#pragma once

#include <android/asset_manager.h>

#include <cstdint>

#include "texture_loader.h"

namespace cupola {

class Device;

// Assets textures/<name>.ktx2, bit s of TextureSet::loadedMask (Gate.TEXTURE_SLOTS); bindings: descriptor_bindings.h.
enum TextureSlot : uint32_t {
    kDayDetail = 0,
    kMonthlyColour,
    kClouds,
    kCityLights,
    kTerrainNormal,
    kStars,
    kWaterMask,
    kInteriorEarth,
    kInteriorCabin,
    kInteriorEmissive,
    kInteriorAmbient,
    kStationAlbedo,
    kStationOrm,
    kCloudDetail,
    kTextureSlots,
};

// Textures computed at load time (no asset): the atmosphere LUTs (atmosphere_lut.h) as the layers of one array
// (earth.frag LUT_*) and a tiling noise.
enum GeneratedSlot : uint32_t {
    kAtmosphereLuts = 0,
    kNoise,
    kGeneratedSlots,
};

// The Earth and sky textures of the process (decision 33): loaded once per device, shared by every
// surface target and offscreen render, destroyed with the device.
struct TextureSet {
    // The kInteriorCabin and kInteriorEmissive entries are empty: those assets are the layers of interiorLight.
    Texture textures[kTextureSlots];
    Texture interiorLight;
    VkSampler wrapU = VK_NULL_HANDLE;  // REPEAT on u, CLAMP_TO_EDGE on v (equirectangular maps)
    VkSampler clamp = VK_NULL_HANDLE;  // CLAMP_TO_EDGE on u and v (day_detail tiles, interior atlases, LUTs)
    VkSampler wrap = VK_NULL_HANDLE;   // REPEAT on u and v (noise, cloud_detail tile)
    Texture generated[kGeneratedSlots];
    // Bit per slot loaded from its asset; the others hold a neutral 1 x 1 fallback.
    uint32_t loadedMask = 0;

    VkSampler sampler(uint32_t slot) const {
        if (slot == kCloudDetail) return wrap;
        return slot == kDayDetail || slot >= kInteriorEarth ? clamp : wrapU;
    }
    VkSampler generatedSampler(uint32_t slot) const { return slot == kNoise ? wrap : clamp; }
};

// Loads textures/<name>.ktx2 for every slot. A missing or failing asset (or no asset manager) gets a 1 x 1
// fallback and one log line for the whole set; interior_cabin and interior_emissive fall back together unless both
// load with the same format, extent and levels. Returns a Vulkan error only if samplers, fallbacks or copies fail.
VkResult loadTextureSet(Device& device, AAssetManager* assets, TextureSet* out);

void destroyTextureSet(const Device& device, TextureSet* set);

}  // namespace cupola
