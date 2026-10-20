#pragma once

#include <cstdint>

// SPIR-V words generated at build time by glslc -mfmt=c (see CMakeLists.txt).
namespace cupola::shaders {
inline constexpr uint32_t kFullscreenVert[] =
#include "fullscreen.vert.inc"
    ;
inline constexpr uint32_t kPatternFrag[] =
#include "pattern.frag.inc"
    ;
inline constexpr uint32_t kEarthFrag[] =
#include "earth.frag.inc"
    ;
}  // namespace cupola::shaders
