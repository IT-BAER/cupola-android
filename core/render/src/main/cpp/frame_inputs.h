#pragma once

// Float layout of per-frame inputs; must match com.baer.cupola.core.render.FrameInputs.
namespace cupola::inputs {
constexpr int kLoad = 0;
constexpr int kLoadIterations = 1;
constexpr int kTimeSeconds = 2;
constexpr int kCount = 3;
}  // namespace cupola::inputs
