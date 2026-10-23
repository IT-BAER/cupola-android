#pragma once

#include <cstdint>
#include <vector>

// Atmosphere lookup tables computed once at start on the CPU (Hillaire 2020): RGBA float texels, row-major,
// row 0 first. Model and sizes: atmosphere_params.h; texel mappings mirrored in earth.frag.
namespace cupola::atmosphere {

struct Lut {
    uint32_t width = 0;
    uint32_t height = 0;
    std::vector<float> rgba;  // width * height * 4
};

struct Luts {
    Lut transmittance;  // T to the top boundary (Bruneton mapping of r and mu)
    Lut multiScatter;   // Psi_ms per unit sun illuminance (u = 0.5 + 0.5 mu_s, v = altitude / thickness)
    Lut irradiance;     // sky irradiance on the ground per unit sun illuminance (u = 0.5 + 0.5 mu_s), height 1
};

void computeLuts(Luts* out);

// Transmittance from radius r (km) along cos(zenith) mu to the top boundary, by bilinear lookup.
void sampleTransmittance(const Lut& lut, double r, double mu, float rgb[3]);

}  // namespace cupola::atmosphere
