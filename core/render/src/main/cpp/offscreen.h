#pragma once

#include <cstdint>

#include "device.h"

namespace cupola {

// Renders the frame pipeline into an RGBA8 image and copies it to `out`
// (width * height * 4 bytes, tightly packed, row-major, top row first).
VkResult renderOffscreen(Device& device, uint32_t width, uint32_t height, const float* inputs, uint8_t* out);

}  // namespace cupola
