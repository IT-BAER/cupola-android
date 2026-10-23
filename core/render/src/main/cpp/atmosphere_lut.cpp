#include "atmosphere_lut.h"

#include <algorithm>
#include <cmath>

#include "atmosphere_params.h"

namespace cupola::atmosphere {
namespace {

constexpr double kPi = 3.14159265358979323846;
constexpr double kRg = ATMO_BOTTOM_KM;
constexpr double kRt = ATMO_TOP_KM;

struct Rgb {
    double r = 0, g = 0, b = 0;
    Rgb operator+(const Rgb& o) const { return {r + o.r, g + o.g, b + o.b}; }
    Rgb operator*(const Rgb& o) const { return {r * o.r, g * o.g, b * o.b}; }
    Rgb operator*(double k) const { return {r * k, g * k, b * k}; }
    Rgb& operator+=(const Rgb& o) { return *this = *this + o; }
};

Rgb expRgb(const Rgb& x) { return {std::exp(-x.r), std::exp(-x.g), std::exp(-x.b)}; }

struct Medium {
    Rgb rayleigh;  // scattering
    double mie;    // scattering
    Rgb extinction;
};

Medium medium(double h) {
    const double dr = std::exp(-h / ATMO_RAYLEIGH_HEIGHT_KM);
    const double dm = std::exp(-h / ATMO_MIE_HEIGHT_KM);
    const double dozone = std::max(0.0, 1.0 - std::fabs(h - ATMO_OZONE_CENTER_KM) / ATMO_OZONE_HALF_WIDTH_KM);
    Medium m;
    m.rayleigh = Rgb{ATMO_RAYLEIGH_SCATTER_R, ATMO_RAYLEIGH_SCATTER_G, ATMO_RAYLEIGH_SCATTER_B} * dr;
    m.mie = ATMO_MIE_SCATTER * dm;
    m.extinction = m.rayleigh + Rgb{1, 1, 1} * (ATMO_MIE_EXTINCTION * dm) +
                   Rgb{ATMO_OZONE_ABSORB_R, ATMO_OZONE_ABSORB_G, ATMO_OZONE_ABSORB_B} * dozone;
    return m;
}

// Distance along mu from radius r to the sphere of radius R (far root), or -1 when it misses.
double distanceToSphere(double r, double mu, double R, bool nearRoot) {
    const double disc = r * r * (mu * mu - 1) + R * R;
    if (disc < 0) return -1;
    const double s = std::sqrt(disc);
    const double t = nearRoot ? -r * mu - s : -r * mu + s;
    return t;
}

bool hitsGround(double r, double mu) { return mu < 0 && r * r * (mu * mu - 1) + kRg * kRg >= 0; }

// Bruneton 2017 mapping between [0, 1] and texel centres.
double unitFromTexcoord(double u, int n) { return (u - 0.5 / n) / (1 - 1.0 / n); }
double texcoordFromUnit(double x, int n) { return 0.5 / n + x * (1 - 1.0 / n); }

const double kH = std::sqrt(kRt * kRt - kRg * kRg);

void transmittanceUv(double r, double mu, double* u, double* v) {
    const double rho = std::sqrt(std::max(0.0, r * r - kRg * kRg));
    const double d = std::max(0.0, distanceToSphere(r, mu, kRt, false));
    const double dMin = kRt - r;
    const double dMax = rho + kH;
    *u = texcoordFromUnit((d - dMin) / (dMax - dMin), ATMO_TRANSMITTANCE_W);
    *v = texcoordFromUnit(rho / kH, ATMO_TRANSMITTANCE_H);
}

Rgb sample(const Lut& lut, double u, double v) {
    const double x = std::clamp(u * lut.width - 0.5, 0.0, lut.width - 1.0);
    const double y = std::clamp(v * lut.height - 0.5, 0.0, lut.height - 1.0);
    const uint32_t x0 = static_cast<uint32_t>(x), y0 = static_cast<uint32_t>(y);
    const uint32_t x1 = std::min(x0 + 1, lut.width - 1), y1 = std::min(y0 + 1, lut.height - 1);
    const double fx = x - x0, fy = y - y0;
    auto at = [&](uint32_t xi, uint32_t yi) {
        const float* p = &lut.rgba[(size_t{yi} * lut.width + xi) * 4];
        return Rgb{p[0], p[1], p[2]};
    };
    return (at(x0, y0) * (1 - fx) + at(x1, y0) * fx) * (1 - fy) + (at(x0, y1) * (1 - fx) + at(x1, y1) * fx) * fy;
}

Rgb transmittance(const Lut& lut, double r, double mu) {
    double u, v;
    transmittanceUv(r, mu, &u, &v);
    return sample(lut, u, v);
}

// Sun transmittance at (r, mu_s), zero below the horizon of that radius.
Rgb sunTransmittance(const Lut& lut, double r, double muS) {
    return hitsGround(r, muS) ? Rgb{} : transmittance(lut, r, muS);
}

void store(Lut* lut, uint32_t x, uint32_t y, const Rgb& c) {
    float* p = &lut->rgba[(size_t{y} * lut->width + x) * 4];
    p[0] = static_cast<float>(c.r);
    p[1] = static_cast<float>(c.g);
    p[2] = static_cast<float>(c.b);
    p[3] = 1.0f;
}

void allocate(Lut* lut, uint32_t w, uint32_t h) {
    lut->width = w;
    lut->height = h;
    lut->rgba.assign(size_t{w} * h * 4, 0.0f);
}

void computeTransmittance(Lut* lut) {
    allocate(lut, ATMO_TRANSMITTANCE_W, ATMO_TRANSMITTANCE_H);
    constexpr int kSteps = 64;
    for (uint32_t y = 0; y < lut->height; ++y) {
        for (uint32_t x = 0; x < lut->width; ++x) {
            const double xMu = unitFromTexcoord((x + 0.5) / lut->width, ATMO_TRANSMITTANCE_W);
            const double xR = unitFromTexcoord((y + 0.5) / lut->height, ATMO_TRANSMITTANCE_H);
            const double rho = kH * xR;
            const double r = std::sqrt(rho * rho + kRg * kRg);
            const double dMin = kRt - r;
            const double dMax = rho + kH;
            const double d = dMin + xMu * (dMax - dMin);
            const double mu = d == 0 ? 1.0 : std::clamp((kH * kH - rho * rho - d * d) / (2 * r * d), -1.0, 1.0);
            Rgb depth;
            for (int i = 0; i < kSteps; ++i) {
                const double t = (i + 0.5) / kSteps * d;
                const double ri = std::sqrt(r * r + t * t + 2 * r * mu * t);
                depth += medium(ri - kRg).extinction * (d / kSteps);
            }
            store(lut, x, y, expRgb(depth));
        }
    }
}

// Uniform sphere direction k of n (Fibonacci), as (cos zenith, azimuth).
void sphereDirection(int k, int n, double* mu, double* phi) {
    *mu = 1 - 2 * (k + 0.5) / n;
    *phi = kPi * (3 - std::sqrt(5.0)) * k;
}

// Hillaire 2020 section 5.5: second-order isotropic luminance L2 and transfer f_ms, Psi_ms = L2 / (1 - f_ms).
void computeMultiScatter(const Lut& trans, Lut* lut) {
    const int n = ATMO_MULTI_SCATTER_N;
    allocate(lut, n, n);
    constexpr int kDirections = 64;
    constexpr int kSteps = 20;
    const double iso = 1 / (4 * kPi);
    for (int y = 0; y < n; ++y) {
        for (int x = 0; x < n; ++x) {
            const double muS = std::clamp(2 * (x + 0.5) / n - 1, -1.0, 1.0);
            const double r = kRg + std::max(0.01, (y + 0.5) / n * (kRt - kRg));
            const double sunX = std::sqrt(std::max(0.0, 1 - muS * muS));
            Rgb l2, fms;
            for (int k = 0; k < kDirections; ++k) {
                double mu, phi;
                sphereDirection(k, kDirections, &mu, &phi);
                const double sinZ = std::sqrt(std::max(0.0, 1 - mu * mu));
                const double dx = sinZ * std::cos(phi), dy = sinZ * std::sin(phi), dz = mu;
                const bool ground = hitsGround(r, mu);
                const double tMax = ground ? distanceToSphere(r, mu, kRg, true) : distanceToSphere(r, mu, kRt, false);
                const double dt = tMax / kSteps;
                Rgb t{1, 1, 1}, lum, f;
                for (int i = 0; i < kSteps; ++i) {
                    const double s = (i + 0.5) * dt;
                    const double px = s * dx, py = s * dy, pz = r + s * dz;
                    const double ri = std::sqrt(px * px + py * py + pz * pz);
                    const Medium m = medium(ri - kRg);
                    const double muSi = (px * sunX + pz * muS) / ri;
                    const Rgb scatter = m.rayleigh + Rgb{1, 1, 1} * m.mie;
                    const Rgb step = expRgb(m.extinction * dt);
                    const Rgb inv{1 / m.extinction.r, 1 / m.extinction.g, 1 / m.extinction.b};
                    const Rgb weight = t * (Rgb{1, 1, 1} + step * -1.0) * inv;  // integral of T over the step
                    lum += weight * scatter * sunTransmittance(trans, ri, muSi) * iso;
                    f += weight * scatter;
                    t = t * step;
                }
                if (ground) {
                    const double px = tMax * dx, py = tMax * dy, pz = r + tMax * dz;
                    const double ri = std::sqrt(px * px + py * py + pz * pz);
                    const double muG = (px * sunX + pz * muS) / ri;
                    lum += t * sunTransmittance(trans, ri, muG) * (std::max(0.0, muG) * ATMO_GROUND_ALBEDO / kPi);
                }
                l2 += lum * (1.0 / kDirections);
                fms += f * (iso * 4 * kPi / kDirections);
            }
            store(lut, x, y, Rgb{l2.r / (1 - fms.r), l2.g / (1 - fms.g), l2.b / (1 - fms.b)});
        }
    }
}

double rayleighPhase(double nu) { return 3 / (16 * kPi) * (1 + nu * nu); }

double miePhase(double nu) {
    const double g = ATMO_MIE_G;
    return 3 / (8 * kPi) * (1 - g * g) * (1 + nu * nu) / ((2 + g * g) * std::pow(1 + g * g - 2 * g * nu, 1.5));
}

// Ground sky irradiance per unit sun illuminance: single scattering plus Psi_ms over the upper hemisphere.
void computeIrradiance(const Lut& trans, const Lut& ms, Lut* lut) {
    allocate(lut, ATMO_IRRADIANCE_W, 1);
    constexpr int kZenith = 16;
    constexpr int kAzimuth = 32;
    constexpr int kSteps = 32;
    const double r = kRg;
    for (uint32_t x = 0; x < lut->width; ++x) {
        const double muS = 2 * (x + 0.5) / lut->width - 1;
        const double sunX = std::sqrt(std::max(0.0, 1 - muS * muS));
        Rgb e;
        for (int iz = 0; iz < kZenith; ++iz) {
            const double zen = (iz + 0.5) / kZenith * kPi / 2;
            const double mu = std::cos(zen);
            const double dOmega = std::sin(zen) * (kPi / 2 / kZenith) * (2 * kPi / kAzimuth);
            const double tMax = distanceToSphere(r, mu, kRt, false);
            for (int ia = 0; ia < kAzimuth; ++ia) {
                const double phi = (ia + 0.5) / kAzimuth * 2 * kPi;
                const double dx = std::sin(zen) * std::cos(phi), dy = std::sin(zen) * std::sin(phi);
                const double nu = dx * sunX + mu * muS;
                // Non-uniform steps: denser near the ground where the density is highest.
                Rgb t{1, 1, 1}, lum;
                double prev = 0;
                for (int i = 0; i < kSteps; ++i) {
                    const double u1 = (i + 1.0) / kSteps;
                    const double s1 = tMax * u1 * u1;
                    const double dt = s1 - prev;
                    const double s = 0.5 * (s1 + prev);
                    prev = s1;
                    const double px = s * dx, pz = r + s * mu;
                    const double ri = std::sqrt(px * px + s * dy * s * dy + pz * pz);
                    const Medium m = medium(ri - kRg);
                    const double muSi = (px * sunX + pz * muS) / ri;
                    const Rgb sunT = sunTransmittance(trans, ri, muSi);
                    const Rgb msv = sample(ms, 0.5 + 0.5 * muSi, (ri - kRg) / (kRt - kRg));
                    const Rgb scatter = m.rayleigh + Rgb{1, 1, 1} * m.mie;
                    const Rgb src = sunT * (m.rayleigh * rayleighPhase(nu) + Rgb{1, 1, 1} * (m.mie * miePhase(nu))) +
                                    scatter * msv;
                    const Rgb step = expRgb(m.extinction * dt);
                    const Rgb inv{1 / m.extinction.r, 1 / m.extinction.g, 1 / m.extinction.b};
                    lum += t * (Rgb{1, 1, 1} + step * -1.0) * inv * src;
                    t = t * step;
                }
                e += lum * (mu * dOmega);
            }
        }
        store(lut, x, 0, e);
    }
}

}  // namespace

void computeLuts(Luts* out) {
    computeTransmittance(&out->transmittance);
    computeMultiScatter(out->transmittance, &out->multiScatter);
    computeIrradiance(out->transmittance, out->multiScatter, &out->irradiance);
}

void sampleTransmittance(const Lut& lut, double r, double mu, float rgb[3]) {
    const Rgb t = transmittance(lut, r, mu);
    rgb[0] = static_cast<float>(t.r);
    rgb[1] = static_cast<float>(t.g);
    rgb[2] = static_cast<float>(t.b);
}

}  // namespace cupola::atmosphere
