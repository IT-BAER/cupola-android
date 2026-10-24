#pragma once

#include <cmath>

#include "frame_inputs.h"

namespace cupola {

// Decides when the sky view LUT (SkyPass, earth.frag MODE 3) must be re-rendered. The LUT lives in the camera's sky
// frame (local up and the Sun's azimuth axis), so it depends only on the camera radius (horizon nadir angle) and the
// Sun's zenith cosine, never on the look direction. On the ISS these move at most ~0.0011/s (cosine, terminator) and
// ~4e-6 rad/s, so 60 fps look-around reuses the LUT for most frames. Compared against the last rendered values.
class SkyLutKey {
public:
    static constexpr float kSunCosTolerance = 1e-4f;       // 0.006 deg of Sun elevation at the horizon
    static constexpr float kHorizonNadirTolerance = 5e-5f;  // about 0.1 km of camera radius

    // True (and remembers these inputs) when the LUT rendered for the last remembered inputs no longer fits.
    bool stale(const float* inputs) {
        const float* up = inputs + inputs::kSkyUp;
        const float* sun = inputs + inputs::kSunDir;
        const float sunCos = up[0] * sun[0] + up[1] * sun[1] + up[2] * sun[2];
        const float horizonNadir = inputs[inputs::kSkyHorizonNadir];
        if (valid_ && std::fabs(sunCos - sunCos_) <= kSunCosTolerance &&
            std::fabs(horizonNadir - horizonNadir_) <= kHorizonNadirTolerance) {
            return false;
        }
        valid_ = true;
        sunCos_ = sunCos;
        horizonNadir_ = horizonNadir;
        return true;
    }

    void invalidate() { valid_ = false; }

private:
    bool valid_ = false;
    float sunCos_ = 0.0f;
    float horizonNadir_ = 0.0f;
};

}  // namespace cupola
