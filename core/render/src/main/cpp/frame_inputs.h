#pragma once

// Float layout of per-frame inputs; must match com.baer.cupola.core.render.FrameInputs.
namespace cupola::inputs {
constexpr int kLoad = 0;
constexpr int kLoadIterations = 1;
constexpr int kTimeSeconds = 2;
constexpr int kLookYawDeg = 3;
constexpr int kLookPitchDeg = 4;
constexpr int kCount = 5;
}  // namespace cupola::inputs
