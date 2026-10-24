#pragma once

// Float layout of per-frame inputs; must match com.baer.cupola.core.render.FrameInputs.
namespace cupola::inputs {
constexpr int kLoad = 0;
constexpr int kLoadIterations = 1;
constexpr int kTimeSeconds = 2;
constexpr int kLookYawDeg = 3;
constexpr int kLookPitchDeg = 4;
constexpr int kScene = 5;
constexpr int kRenderScale = 6;
// Bitmask (FrameInputs.EFFECT_* and DEBUG_* bits), pushed to earth.frag as an int.
constexpr int kEffects = 7;
// Floats [kSceneBlock, kCount) are the per-frame uniform block of earth.frag (std140 vec4s).
constexpr int kSceneBlock = 8;
constexpr int kCameraPosKm = 8;
constexpr int kCameraEllipsoidC = 11;
constexpr int kCameraRight = 12;
constexpr int kMonthWeight = 15;
constexpr int kCameraUp = 16;
constexpr int kMonthLayer0 = 19;
constexpr int kCameraBack = 20;
constexpr int kMonthLayer1 = 23;
constexpr int kSunDir = 24;
constexpr int kTanHalfFovLong = 27;
constexpr int kMoonDir = 28;
constexpr int kMoonLight = 31;
constexpr int kInteriorRight = 32;
constexpr int kInterior = 35;
constexpr int kInteriorUp = 36;
constexpr int kEarthLight = 39;
constexpr int kInteriorBack = 40;
constexpr int kExposure = 43;
constexpr int kSkyUp = 44;
constexpr int kSkyHorizonNadir = 47;
constexpr int kSkySunAxis = 48;
constexpr int kSkyTopNadir = 51;
constexpr int kCloudFlow = 52;
constexpr int kStarRowX = 56;
constexpr int kStarRowY = 60;
constexpr int kStarRowZ = 64;
constexpr int kCount = 68;
}  // namespace cupola::inputs
