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
constexpr int kInteriorRight = 32;
constexpr int kInterior = 35;
constexpr int kInteriorUp = 36;
constexpr int kEarthLight = 39;
constexpr int kInteriorBack = 40;
constexpr int kCount = 44;
}  // namespace cupola::inputs
