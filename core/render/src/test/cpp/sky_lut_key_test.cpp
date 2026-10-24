// Host tests of SkyLutKey (when the sky view LUT must be re-rendered). Usage: sky_lut_key_test [case]; no argument
// runs every case. Exit 0 = pass, 1 = fail.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>

#include "frame_inputs.h"
#include "sky_lut_key.h"

namespace {

using cupola::SkyLutKey;
namespace in = cupola::inputs;

constexpr double kPi = 3.14159265358979323846;

int gFailures = 0;

#define CHECK(cond, ...)                                                          \
    do {                                                                          \
        if (!(cond)) {                                                            \
            std::printf("%s:%d: CHECK failed: %s: ", __FILE__, __LINE__, #cond);  \
            std::printf(__VA_ARGS__);                                             \
            std::printf("\n");                                                    \
            ++gFailures;                                                          \
        }                                                                         \
    } while (0)

// ISS-like frame: camera radius 6798 km (horizon nadir angle asin(6378.137 / 6798)), Sun at zenith angle z.
struct Inputs {
    float v[in::kCount] = {};
    explicit Inputs(double sunZenithRad) {
        v[in::kSkyUp + 2] = 1.0f;
        v[in::kSkyHorizonNadir] = static_cast<float>(std::asin(6378.137 / 6798.0));
        sun(sunZenithRad);
    }
    void sun(double z) {
        v[in::kSunDir] = static_cast<float>(std::sin(z));
        v[in::kSunDir + 2] = static_cast<float>(std::cos(z));
        v[in::kSkySunAxis] = 1.0f;
    }
};

void firstFrameRenders() {
    SkyLutKey key;
    Inputs i(1.0);
    CHECK(key.stale(i.v), "a fresh key must render");
    CHECK(!key.stale(i.v), "same inputs again must not render");
}

void lookDirectionDoesNotRender() {
    SkyLutKey key;
    Inputs i(1.2);
    key.stale(i.v);
    for (int k = in::kCameraRight; k < in::kCameraRight + 3; ++k) i.v[k] = 0.3f;
    for (int k = in::kCameraBack; k < in::kCameraBack + 3; ++k) i.v[k] = -0.5f;
    i.v[in::kLookYawDeg] = 12.0f;
    i.v[in::kLookPitchDeg] = -7.0f;
    i.v[in::kExposure] = 40.0f;
    CHECK(!key.stale(i.v), "camera orientation, look angles and exposure are not LUT inputs");
}

void sunMovingPastToleranceRenders() {
    SkyLutKey key;
    Inputs i(kPi / 2);  // Sun on the horizon: its cosine changes fastest
    key.stale(i.v);
    i.sun(kPi / 2 - 0.5 * SkyLutKey::kSunCosTolerance);
    CHECK(!key.stale(i.v), "half the tolerance must not render");
    i.sun(kPi / 2 - 1.5 * SkyLutKey::kSunCosTolerance);
    CHECK(key.stale(i.v), "1.5 x the tolerance since the last render must render");
}

void slowDriftRendersOnceItAccumulates() {
    SkyLutKey key;
    Inputs i(1.0);
    key.stale(i.v);
    int renders = 0;
    // 0.0011 per second (ISS terminator rate) at 60 fps for 1 s
    for (int f = 1; f <= 60; ++f) {
        i.sun(std::acos(std::cos(1.0) + 0.0011 * f / 60.0));
        if (key.stale(i.v)) ++renders;
    }
    CHECK(renders >= 5 && renders <= 12, "renders %d, expected about 11 (one per tolerance step)", renders);
}

void altitudeChangeRenders() {
    SkyLutKey key;
    Inputs i(1.0);
    key.stale(i.v);
    i.v[in::kSkyHorizonNadir] += 0.5f * SkyLutKey::kHorizonNadirTolerance;
    CHECK(!key.stale(i.v), "half the tolerance must not render");
    i.v[in::kSkyHorizonNadir] += 1.0f * SkyLutKey::kHorizonNadirTolerance;
    CHECK(key.stale(i.v), "1.5 x the tolerance must render");
}

void invalidateRenders() {
    SkyLutKey key;
    Inputs i(1.0);
    key.stale(i.v);
    key.invalidate();
    CHECK(key.stale(i.v), "an invalidated key must render");
}

}  // namespace

int main(int argc, char** argv) {
    const std::pair<const char*, std::function<void()>> cases[] = {
        {"first_frame", firstFrameRenders},
        {"look_direction", lookDirectionDoesNotRender},
        {"sun_tolerance", sunMovingPastToleranceRenders},
        {"slow_drift", slowDriftRendersOnceItAccumulates},
        {"altitude", altitudeChangeRenders},
        {"invalidate", invalidateRenders},
    };
    bool ran = false;
    for (const auto& [name, run] : cases) {
        if (argc > 1 && std::strcmp(argv[1], name) != 0) continue;
        ran = true;
        run();
    }
    if (!ran) {
        std::printf("unknown case %s\n", argv[1]);
        return 1;
    }
    std::printf(gFailures == 0 ? "PASS\n" : "FAIL (%d)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
