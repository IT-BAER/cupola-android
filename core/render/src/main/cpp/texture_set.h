#pragma once

#include <android/asset_manager.h>

#include <cstdint>

#include "texture_loader.h"

namespace cupola {

class Device;

// Descriptor binding of slot s is s + 1 (binding 0 is the scene uniform block); see earth.frag.
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
    kTextureSlots,
};

// Binding of the scaled Earth image (EarthPass), after the texture slots.
constexpr uint32_t kEarthImageBinding = kTextureSlots + 1;

// Textures computed at load time (no asset): atmosphere LUTs (atmosphere_lut.h) and a tiling noise; bindings
// from kGeneratedBinding in this order.
enum GeneratedSlot : uint32_t {
    kTransmittanceLut = 0,
    kMultiScatterLut,
    kSkyIrradianceLut,
    kNoise,
    kGeneratedSlots,
};
constexpr uint32_t kGeneratedBinding = kEarthImageBinding + 1;
// The per-frame view LUT of SkyPass (inscatter, transmittance), then the binding count of set 0.
constexpr uint32_t kSkyInscatterBinding = kGeneratedBinding + kGeneratedSlots;
constexpr uint32_t kSkyTransmittanceBinding = kSkyInscatterBinding + 1;
constexpr uint32_t kBindingCount = kSkyTransmittanceBinding + 1;

// The Earth and sky textures of the process (decision 33): loaded once per device, shared by every
// surface target and offscreen render, destroyed with the device.
struct TextureSet {
    Texture textures[kTextureSlots];
    VkSampler wrapU = VK_NULL_HANDLE;  // REPEAT on u, CLAMP_TO_EDGE on v (equirectangular maps)
    VkSampler clamp = VK_NULL_HANDLE;  // CLAMP_TO_EDGE on u and v (day_detail tiles, interior atlases, LUTs)
    VkSampler wrap = VK_NULL_HANDLE;   // REPEAT on u and v (noise)
    Texture generated[kGeneratedSlots];
    // Bit per slot loaded from its asset; the others hold a neutral 1 x 1 fallback.
    uint32_t loadedMask = 0;

    VkSampler sampler(uint32_t slot) const { return slot == kDayDetail || slot >= kInteriorEarth ? clamp : wrapU; }
    VkSampler generatedSampler(uint32_t slot) const { return slot == kNoise ? wrap : clamp; }
};

// Loads textures/<name>.ktx2 for every slot. A missing or failing asset (or no asset manager) gets a 1 x 1
// fallback and one log line for the whole set. Returns a Vulkan error only if samplers or fallbacks fail.
VkResult loadTextureSet(Device& device, AAssetManager* assets, TextureSet* out);

void destroyTextureSet(const Device& device, TextureSet* set);

}  // namespace cupola
