#pragma once

#include <cstdint>

// Set 0 of each pipeline layout: binding 0 the scene uniform block, then one combined image sampler per binding, all
// fragment stage. C++ kFooBar is GLSL fooBar (block FooBar); DescriptorLayoutTest checks both against the shaders.
namespace cupola {

// Vulkan minimum of maxPerStageDescriptorSamplers and maxPerStageDescriptorSampledImages: every device has at least 16.
constexpr uint32_t kMaxStageSamplers = 16;

// earth.frag, all MODES.
namespace earth_binding {
constexpr uint32_t kScene = 0;
constexpr uint32_t kDayDetail = 1;
constexpr uint32_t kMonthlyColour = 2;
constexpr uint32_t kCloudMap = 3;
constexpr uint32_t kCityLights = 4;
constexpr uint32_t kTerrainNormal = 5;
constexpr uint32_t kStars = 6;
constexpr uint32_t kWaterMask = 7;
constexpr uint32_t kInteriorEarth = 8;
constexpr uint32_t kInteriorLight = 9;
constexpr uint32_t kInteriorAmbient = 10;
constexpr uint32_t kCloudDetail = 11;
constexpr uint32_t kAtmosphereLuts = 12;
constexpr uint32_t kNoiseTex = 13;
constexpr uint32_t kEarthImage = 14;
constexpr uint32_t kSkyView = 15;
constexpr uint32_t kStationImage = 16;
constexpr uint32_t kCount = 17;
}  // namespace earth_binding

// station.frag.
namespace station_binding {
constexpr uint32_t kScene = 0;
constexpr uint32_t kAlbedoMap = 1;
constexpr uint32_t kOrmMap = 2;
constexpr uint32_t kCount = 3;
}  // namespace station_binding

static_assert(earth_binding::kCount - 1 <= kMaxStageSamplers && station_binding::kCount - 1 <= kMaxStageSamplers);

}  // namespace cupola
