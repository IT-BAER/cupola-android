// Host tests of the CPU atmosphere LUTs. Usage: atmosphere_lut_test [case]; no argument runs every case.
// Exit 0 = pass, 1 = fail.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <string>

#include "atmosphere_lut.h"
#include "atmosphere_params.h"

namespace {

using namespace cupola::atmosphere;

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

const Luts& luts() {
    static Luts l = [] {
        Luts x;
        computeLuts(&x);
        return x;
    }();
    return l;
}

const float* texel(const Lut& lut, uint32_t x, uint32_t y) { return &lut.rgba[(size_t{y} * lut.width + x) * 4]; }

void sizesAndFinite() {
    const Luts& l = luts();
    CHECK(l.transmittance.width == ATMO_TRANSMITTANCE_W && l.transmittance.height == ATMO_TRANSMITTANCE_H, "%ux%u",
          l.transmittance.width, l.transmittance.height);
    CHECK(l.multiScatter.width == ATMO_MULTI_SCATTER_N && l.multiScatter.height == ATMO_MULTI_SCATTER_N, "%ux%u",
          l.multiScatter.width, l.multiScatter.height);
    CHECK(l.irradiance.width == ATMO_IRRADIANCE_W && l.irradiance.height == 1, "%ux%u", l.irradiance.width,
          l.irradiance.height);
    for (const Lut* lut : {&l.transmittance, &l.multiScatter, &l.irradiance}) {
        CHECK(lut->rgba.size() == size_t{lut->width} * lut->height * 4, "size %zu", lut->rgba.size());
        bool ok = true;
        for (float v : lut->rgba) ok = ok && std::isfinite(v) && v >= 0.0f;
        CHECK(ok, "non-finite or negative texel");
    }
}

// Zenith from the ground: exp(-optical depth) of the three exponential/tent profiles, within 1 %.
void zenithTransmittanceMatchesOpticalDepth() {
    const double thick = ATMO_TOP_KM - ATMO_BOTTOM_KM;
    const double rayleigh[3] = {ATMO_RAYLEIGH_SCATTER_R, ATMO_RAYLEIGH_SCATTER_G, ATMO_RAYLEIGH_SCATTER_B};
    const double ozone[3] = {ATMO_OZONE_ABSORB_R, ATMO_OZONE_ABSORB_G, ATMO_OZONE_ABSORB_B};
    float t[3];
    sampleTransmittance(luts().transmittance, ATMO_BOTTOM_KM, 1.0, t);
    for (int c = 0; c < 3; ++c) {
        const double tau = rayleigh[c] * ATMO_RAYLEIGH_HEIGHT_KM * (1 - std::exp(-thick / ATMO_RAYLEIGH_HEIGHT_KM)) +
                           ATMO_MIE_EXTINCTION * ATMO_MIE_HEIGHT_KM * (1 - std::exp(-thick / ATMO_MIE_HEIGHT_KM)) +
                           ozone[c] * ATMO_OZONE_HALF_WIDTH_KM;
        CHECK(std::fabs(t[c] / std::exp(-tau) - 1) < 0.01, "channel %d: %f vs %f", c, t[c], std::exp(-tau));
    }
}

// Low sun reddens: transmittance near the horizon is far below zenith and blue drops most; outward at the top ~ 1.
void horizonReddensAndTopIsClear() {
    float zen[3], low[3], top[3];
    sampleTransmittance(luts().transmittance, ATMO_BOTTOM_KM, 1.0, zen);
    sampleTransmittance(luts().transmittance, ATMO_BOTTOM_KM, 0.05, low);
    sampleTransmittance(luts().transmittance, ATMO_TOP_KM, 0.0, top);
    CHECK(low[1] < 0.5 * zen[1], "green low %f zenith %f", low[1], zen[1]);
    CHECK(low[2] / low[0] < 0.5 * zen[2] / zen[0], "blue/red low %f zenith %f", low[2] / low[0], zen[2] / zen[0]);
    CHECK(top[0] > 0.999f && top[2] > 0.999f, "top %f %f", top[0], top[2]);
}

// Multiple scattering: blue-dominant under a high sun at the ground, far weaker with the sun deep below the horizon.
void multiScatterFollowsTheSun() {
    const Lut& ms = luts().multiScatter;
    const float* noon = texel(ms, ms.width - 1, 0);
    const float* night = texel(ms, ms.width / 4, 0);  // mu_s about -0.5
    CHECK(noon[2] > noon[0] && noon[0] > 0.0f, "noon %g %g %g", noon[0], noon[1], noon[2]);
    CHECK(night[1] < 0.2 * noon[1], "night %g noon %g", night[1], noon[1]);
}

// Clear-sky diffuse irradiance at noon is 5..25 % of the direct beam (green); twilight 6 deg below the horizon is
// dim but not zero; deep night is dark.
void skyIrradianceIsPlausible() {
    const Lut& e = luts().irradiance;
    const float* noon = texel(e, e.width - 1, 0);
    const int twilightX = static_cast<int>(std::lround((0.5 + 0.5 * std::sin(-6.0 * 3.14159265358979 / 180)) * e.width - 0.5));
    const float* twilight = texel(e, twilightX, 0);
    const float* night = texel(e, e.width / 4, 0);
    CHECK(noon[1] > 0.05f && noon[1] < 0.25f, "noon green %f", noon[1]);
    CHECK(noon[2] > noon[0], "noon not blue: %f %f", noon[0], noon[2]);
    CHECK(twilight[1] > 1e-5f && twilight[1] < 0.01f * noon[1], "twilight %g", twilight[1]);
    CHECK(night[1] < 1e-6f, "night %g", night[1]);
}

}  // namespace

int main(int argc, char** argv) {
    const std::pair<const char*, std::function<void()>> cases[] = {
        {"sizes_and_finite", sizesAndFinite},
        {"zenith_transmittance", zenithTransmittanceMatchesOpticalDepth},
        {"horizon_reddens", horizonReddensAndTopIsClear},
        {"multi_scatter", multiScatterFollowsTheSun},
        {"sky_irradiance", skyIrradianceIsPlausible},
    };
    bool ran = false;
    for (const auto& [name, fn] : cases) {
        if (argc > 1 && std::strcmp(argv[1], name) != 0) continue;
        ran = true;
        const int before = gFailures;
        fn();
        std::printf("%s %s\n", gFailures == before ? "PASS" : "FAIL", name);
    }
    if (!ran) {
        std::printf("unknown case %s\n", argv[1]);
        return 1;
    }
    return gFailures == 0 ? 0 : 1;
}
