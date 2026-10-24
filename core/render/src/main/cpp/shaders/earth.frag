#version 450
#extension GL_GOOGLE_include_directive : require

// P3.1 Earth: per-pixel ray cast against the WGS-84 ellipsoid (no mesh), day colour, relief and sun glint,
// seen through the baked Cupola interior panorama (decision 22).
// Texture contract: tools/textures/README.md "Runtime contract (P5.5)" and "interior_*";
// panorama contract: tools/blender/README.md "Runtime contract".

#include "../atmosphere_params.h"

// 0: Earth and interior in one full-resolution pass. 1: Earth only into the scaled target, skipped where the
// interior covers it. 2: interior over the scaled Earth target, full resolution. 3: the sky view LUT (SkyPass).
layout(constant_id = 0) const int MODE = 0;

// Must match cupola::PushConstants in device.h.
layout(push_constant) uniform Push {
    ivec2 extent;  // physical full-resolution extent
    int rotation;
    int loadIterations;
    float load;
    float time;
    float lookYawDeg;
    float lookPitchDeg;
    vec2 earthScale;  // scaled Earth target extent / full extent, per axis (MODE 1)
    int effects;      // FrameInputs.EFFECTS bits
} pc;

// FrameInputs.DEBUG_* bits.
const int DEBUG_NO_ATMOSPHERE = 1 << 16;
const int DEBUG_NO_INSCATTER = 1 << 17;
const int DEBUG_NO_CLOUDS = 1 << 18;
const int DEBUG_NO_CLOUD_SHADOWS = 1 << 19;
const int DEBUG_NO_CITY_LIGHTS = 1 << 20;
const int DEBUG_NO_MOONLIGHT = 1 << 21;
const int DEBUG_NO_AURORA = 1 << 22;
const int DEBUG_NO_LIGHTNING = 1 << 23;
// FrameInputs.MOTION_REDUCED: the system reduces motion, so nothing flashes.
const int MOTION_REDUCED = 1 << 8;
// FrameInputs.EFFECT_* (Settings switches).
const int EFFECT_BLOOM = 1;
const int EFFECT_WINDOW_GLARE = 2;
const int EFFECT_REFLECTIONS = 4;
const int EFFECT_DUST = 8;

// FrameInputs floats from SCENE_BLOCK (frame_inputs.h); ECEF, km, unit directions.
layout(set = 0, binding = 0, std140) uniform Scene {
    vec4 camPos;   // xyz camera position, w = |q|^2 - 1 of the ellipsoid-scaled position
    vec4 right;    // xyz camera right, w = month weight
    vec4 up;       // xyz camera up, w = month layer 0
    vec4 back;     // xyz camera back (view = -back), w = month layer 1
    vec4 sun;      // xyz sun direction, w = tan(fov / 2) on the long axis
    vec4 moon;     // xyz Moon direction (ISS to Moon), w = lunar irradiance relative to the Sun
    vec4 iRight;   // xyz camera right in the Blender interior frame, w = 1 when the interior is shown
    vec4 iUp;      // xyz camera up in the interior frame, w = Earth brightness for the Earthlight layer
    vec4 iBack;    // xyz camera back in the interior frame, w = linear exposure
    vec4 skyUp;    // xyz radial up of the camera in the ellipsoid-scaled space, w = nadir angle of the horizon
    vec4 skyAxis;  // xyz the Sun's azimuth axis (perpendicular to skyUp), w = nadir angle of the atmosphere top
    vec4 clouds;   // CloudFlow: x, y flow phases A and B (cycle + fraction), z days
    vec4 starX;    // xyz rows of the ECEF-to-galactic rotation (StarFrame)
    vec4 starY;
    vec4 starZ;
    vec4 sunLight;  // xyz sunlight at the camera per unit sunlight (0 behind the Earth), w = glass facing (Scene)
    vec4 homeCity;  // xyz home-city ground point (ECEF km), w = 1 when set (P6.7 marker)
} s;

layout(set = 0, binding = 1) uniform sampler2DArray dayDetail;
layout(set = 0, binding = 2) uniform sampler2DArray monthlyColour;
layout(set = 0, binding = 3) uniform sampler2D cloudMap;
layout(set = 0, binding = 4) uniform sampler2D cityLights;
layout(set = 0, binding = 5) uniform sampler2D terrainNormal;
layout(set = 0, binding = 6) uniform sampler2D stars;
layout(set = 0, binding = 7) uniform sampler2D waterMask;
layout(set = 0, binding = 8) uniform sampler2D interiorEarth;
layout(set = 0, binding = 9) uniform sampler2D interiorCabin;
layout(set = 0, binding = 10) uniform sampler2D interiorEmissive;
layout(set = 0, binding = 11) uniform sampler2D interiorAmbient;
layout(set = 0, binding = 12) uniform sampler2D earthImage;  // MODE 2: the scaled Earth target (linear)
// Atmosphere LUTs computed at start (atmosphere_lut.cpp, mappings mirrored below) and the per-frame view LUT.
layout(set = 0, binding = 13) uniform sampler2D transmittanceLut;
layout(set = 0, binding = 14) uniform sampler2D multiScatterLut;
layout(set = 0, binding = 15) uniform sampler2D irradianceLut;
layout(set = 0, binding = 16) uniform sampler2D noiseTex;  // tiling fBm, 4 independent channels (REPEAT)
layout(set = 0, binding = 17) uniform sampler2D skyInscatter;
layout(set = 0, binding = 18) uniform sampler2D skyTransmittance;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec4 outTransmittance;  // MODE 3 only

const float A_KM = 6378.137;
const float B_KM = 6356.752314245179;
const float PI = 3.14159265358979;
const float LAT_MAX = 1.2566370614359172;  // 72 deg: edge of every Earth map
const float JULY_LAYER = 6.0;              // day_detail is July 2004
const vec3 POLAR_ALBEDO = vec3(0.55, 0.58, 0.62);
// Deep-ocean diffuse reflectance (linear, water-leaving, no atmosphere): BMNG paints seafloor relief instead.
const vec3 OCEAN_ALBEDO = vec3(0.010, 0.022, 0.050);
// Ocean slope variance (Cox-Munk, about 7 m/s wind) for the Beckmann glint lobe; water F0.
const float GLINT_M2 = 0.04;
const float WATER_F0 = 0.02;
// P3.2 clouds: one cloud layer (tops at CLOUD_ALT_KM), flow-map advection with FLOW_PERIOD_H phases (CloudFlow),
// CLOUD_SHADOW of the direct sunlight removed under full cover.
const float CLOUD_ALT_KM = 6.0;
const float CLOUD_ALBEDO = 0.85;
const float FLOW_PERIOD_H = 6.0;
const float CLOUD_SHADOW = 0.75;
// P3.4 night side, in shader radiance (night exposure about 1.4e4, Exposure.NIGHT_FLOOR): city light per texel value 1
// in a sodium / warm LED colour (texel^CITY_GAMMA keeps the cores and drops the dim ASTC block noise around sparse
// lights), star map radiance per texel value 1, the Moon's albedo and angular radius, and the
// airglow shell (OI 557.7 nm) between AIRGLOW_LOW_KM and AIRGLOW_HIGH_KM with its radiance per km of path.
const vec3 CITY_LIGHT = vec3(1.0, 0.72, 0.42) * 1.5e-4;
const float CITY_GAMMA = 1.5;
const float STAR_RADIANCE = 6.0e-5;
const float MOON_ALBEDO = 0.12;
const float MOON_RADIUS = 0.004756;  // rad, 0.2725 deg
const float AIRGLOW_LOW_KM = 85.0;
const float AIRGLOW_HIGH_KM = 100.0;
const vec3 AIRGLOW = vec3(0.45, 1.0, 0.35) * 3.0e-9;
// Aurora: oval around the geomagnetic dipole pole (IGRF 2025: 80.8 N, 72.6 W), emitting between AURORA_LOW_KM and
// AURORA_HIGH_KM (green OI 557.7 nm low, red OI 630 nm high), AURORA_RADIANCE per km of path at full intensity.
const vec3 MAG_POLE = vec3(0.0478, -0.1526, 0.9871);
const float AURORA_LOW_KM = 90.0;
const float AURORA_HIGH_KM = 300.0;
const vec3 AURORA_GREEN = vec3(0.25, 1.0, 0.45);
const vec3 AURORA_RED = vec3(1.0, 0.25, 0.35);
const float AURORA_RADIANCE = 2.5e-8;
// Lightning: storm cells of LIGHTNING_CELL_DEG flash with LIGHTNING_P per LIGHTNING_SLOT_S slot, a glow of
// LIGHTNING_KM radius inside the cloud.
const float LIGHTNING_CELL_DEG = 0.25;
const float LIGHTNING_SLOT_S = 0.2;
const float LIGHTNING_P = 0.0015;
const float LIGHTNING_KM = 5.0;
const vec3 LIGHTNING = vec3(0.8, 0.85, 1.0) * 4.0e-4;
// P6.7 home-city marker: a ring of MARKER_RING_PX radius around a dot, in physical pixels, drawn at MARKER exposed
// brightness (the same by day and night).
const float MARKER_RING_PX = 11.0;
const vec3 MARKER = vec3(0.65, 0.9, 1.0) * 0.9;  // cool, apart from the warm city lights
// P3.5 Sun glare in shader radiance per unit sunlight at the camera: a narrow and a wide bloom lobe and a broad veil
// on the window glass, each peak / (1 + (theta / width)^2) with widths in rad (no transcendentals: full resolution).
const float BLOOM_NARROW = 4.0;
const float BLOOM_NARROW_RAD = 0.015;
const float BLOOM_WIDE = 0.44;
const float BLOOM_WIDE_RAD = 0.1;
const float WINDOW_GLARE = 0.002;  // a clean pane scatters a few tenths of a percent
const float WINDOW_GLARE_RAD = 0.6;

// Interior atlases, faces +X, -X, +Y, -Y, +Z: first atlas row of the face slot and crop row y0, on the
// 2048 grid (earth, cabin, emissive) and the 512 grid (ambient).
const float SLOT_2048[5] = float[](0.0, 1440.0, 2592.0, 4032.0, 5184.0);
const float Y0_2048[5] = float[](670.0, 925.0, 692.0, 922.0, 0.0);
const float SLOT_512[5] = float[](0.0, 360.0, 648.0, 1008.0, 1296.0);
const float Y0_512[5] = float[](167.0, 231.0, 173.0, 230.0, 0.0);
// Bake manifest `scale` per layer (eye 1.55 m from the nadir pane): linear radiance = decoded texel x scale.
const float SCALE_EARTH = 0.2255852371454239;
const float SCALE_CABIN = 3.568661689758301;
const float SCALE_EMISSIVE = 4.80310583114624;
const float SCALE_AMBIENT = 0.279502272605896;
// Static light mix of the P4.7 review (cabin light at 5 % of its built strength, laptop and LEDs at built
// strength, decision 26, a dim fill), scaled by STATIC_SCALE so the night interior keeps its photo median at
// the night exposure (Exposure.KEY / NIGHT_FLOOR); by day it is negligible next to the Earthlight.
const float CABIN_GAIN = 0.05;
const float EMISSIVE_GAIN = 1.0;
const float AMBIENT_GAIN = 0.02;
const float STATIC_SCALE = 2.0e-4;
// Earthlight gains over the mean Earth radiance (Scene.earthLight): the Earthlight layer, and the uniform fill
// (ambient layer) standing in for the bounce light the bake's low albedos lose. Tuned to the Earth-lit photos
// iss022e066972 / iss029e043374 (EarthRenderTest.dayCabinToneMatchesTheEarthLitPhotos).
const float EARTHLIGHT_GAIN = 7.5;
const float EARTH_FILL_GAIN = 1.25;
// P4.5 glass (tools/blender/build_cupola.py, cabin frame in m): the eye EYE_TO_GLASS below the round top pane at
// TOP_GLASS_Z (radius TOP_PANE_R), seen within TOP_WINDOW_COS of +Z; six side panes on facets tilted FACET_TILT from the top plane at
// 60 deg steps, HEX_APOTHEM from the axis at Z_TOP, the pane SIDE_DEPTH outwards. GLASS_F0: the pane stack's normal
// reflectance; DUST: specks' forward scatter per unit sunlight on the glass.
const float TOP_GLASS_Z = 1.4405;
const float TOP_PANE_R = 0.353;
const float EYE_TO_GLASS = 1.55;
const float EYE_Z = TOP_GLASS_Z - EYE_TO_GLASS;
const float TOP_WINDOW_COS = EYE_TO_GLASS / sqrt(EYE_TO_GLASS * EYE_TO_GLASS + TOP_PANE_R * TOP_PANE_R);
const float FACET_TILT = 1.02189;
const float HEX_APOTHEM = 0.52;
const float Z_TOP = 1.3405;
const float SIDE_DEPTH = 0.06;
const float GLASS_F0 = 0.08;
const float DUST = 0.03;

// Above this window alpha the interior hides the Earth completely.
const float OPAQUE = 0.999;

// Atmosphere (Hillaire 2020; model in atmosphere_params.h). Lengths in the ellipsoid-scaled space, where the
// WGS-84 surface is the sphere RG. The LUTs hold radiance per unit sun illuminance; shader units give the Sun an
// illuminance of pi (a white Lambertian surface facing it has radiance 1).
const float RG = ATMO_BOTTOM_KM;
const float RT = ATMO_TOP_KM;
const vec3 TO_SPHERE = vec3(1.0, 1.0, A_KM / B_KM);
const float SUN_ILLUMINANCE = PI;
const float SUN_RADIUS = 0.004654;  // rad
const float SUN_DISC = 1.0 / (SUN_RADIUS * SUN_RADIUS);  // disc radiance: irradiance pi over the disc
const vec3 RAYLEIGH_SCATTER = vec3(ATMO_RAYLEIGH_SCATTER_R, ATMO_RAYLEIGH_SCATTER_G, ATMO_RAYLEIGH_SCATTER_B);
const vec3 OZONE_ABSORB = vec3(ATMO_OZONE_ABSORB_R, ATMO_OZONE_ABSORB_G, ATMO_OZONE_ABSORB_B);
const int SKY_STEPS = 20;  // per segment of a view ray (MODE 3)

// Physical pixel position (continuous) to the logical (unrotated) image.
vec2 logicalPixel(vec2 p) {
    float w = float(pc.extent.x);
    float h = float(pc.extent.y);
    if (pc.rotation == 1) return vec2(p.y, w - p.x);
    if (pc.rotation == 2) return vec2(w - p.x, h - p.y);
    if (pc.rotation == 3) return vec2(h - p.y, p.x);
    return p;
}

// Wraps a longitude difference into [-pi, pi], so derivatives stay small across 180 deg.
float wrapDelta(float d) { return d - 2.0 * PI * round(d / (2.0 * PI)); }

// Filmic tone map (ACES fit, Narkowicz 2015) of exposed linear radiance.
vec3 toneMap(vec3 x) { return clamp(x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

// The scaled Earth target stores exposed radiance x as x / (1 + x) (8-bit sRGB, unbounded range).
vec3 encodeHdr(vec3 x) { return x / (1.0 + x); }
vec3 decodeHdr(vec3 y) { y = min(y, vec3(0.999)); return y / (1.0 - y); }

vec3 encodeSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

// Camera-space ray (unnormalised, z = -1) through the physical pixel position p.
vec3 cameraRay(vec2 p) {
    vec2 size = vec2(pc.rotation % 2 == 1 ? pc.extent.yx : pc.extent);
    vec2 q = logicalPixel(p);
    vec2 tanHalf = s.sun.w * size / max(size.x, size.y);
    return vec3((2.0 * q.x / size.x - 1.0) * tanHalf.x, (1.0 - 2.0 * q.y / size.y) * tanHalf.y, -1.0);
}

vec3 ecefRay(vec3 c) { return normalize(s.right.xyz * c.x + s.up.xyz * c.y + s.back.xyz * c.z); }

vec3 interiorRay(vec3 c) { return s.iRight.xyz * c.x + s.iUp.xyz * c.y + s.iBack.xyz * c.z; }

// Cube face (0..4 = +X, -X, +Y, -Y, +Z; 5 = -Z) and face coordinates in [-1, 1]: the two other axes in
// X, Y, Z order divided by |major axis|.
int cubeFace(vec3 d, out vec2 uv) {
    vec3 a = abs(d);
    if (a.x >= a.y && a.x >= a.z) {
        uv = d.yz / a.x;
        return d.x > 0.0 ? 0 : 1;
    }
    if (a.y >= a.z) {
        uv = d.xz / a.y;
        return d.y > 0.0 ? 2 : 3;
    }
    uv = d.xy / a.z;
    return d.z > 0.0 ? 4 : 5;
}

// Atlas coordinate of face coordinate uv on an n grid, clamped half a texel inside the face crop.
// k = atlas texels per grid texel (the ETC2 atlases are half size).
vec2 atlasUv(vec2 size, vec2 uv, float n, float slot, float y0) {
    float k = size.x / n;
    vec2 t = (uv * 0.5 + 0.5) * n * k;
    t.x = clamp(t.x, 0.5, size.x - 0.5);
    t.y = clamp(t.y, y0 * k + 0.5, n * k - 0.5);
    return vec2(t.x / size.x, (slot * k + t.y - y0 * k) / size.y);
}

float interiorAlpha(vec3 c) {
    vec2 uv;
    int f = cubeFace(interiorRay(c), uv);
    if (f == 5) return 1.0;
    vec2 size = vec2(textureSize(interiorEarth, 0));
    return textureLod(interiorEarth, atlasUv(size, uv, 2048.0, SLOT_2048[f], Y0_2048[f]), 0.0).a;
}

// Interior radiance (linear, rgb) and window alpha (1 = interior surface, 0 = view through a window) along the
// cabin-frame direction d.
vec4 interiorAt(vec3 d) {
    vec2 uv;
    int f = cubeFace(d, uv);
    if (f == 5) return vec4(0.0, 0.0, 0.0, 1.0);  // -Z (towards the hatch) is never visible
    vec2 a = atlasUv(vec2(textureSize(interiorEarth, 0)), uv, 2048.0, SLOT_2048[f], Y0_2048[f]);
    vec4 earth = textureLod(interiorEarth, a, 0.0);
    vec2 b = atlasUv(vec2(textureSize(interiorAmbient, 0)), uv, 512.0, SLOT_512[f], Y0_512[f]);
    vec3 ambient = textureLod(interiorAmbient, b, 0.0).rgb * SCALE_AMBIENT;
    vec3 cabin = textureLod(interiorCabin, a, 0.0).rgb * (SCALE_CABIN * CABIN_GAIN);
    cabin += textureLod(interiorEmissive, a, 0.0).rgb * (SCALE_EMISSIVE * EMISSIVE_GAIN) + ambient * AMBIENT_GAIN;
    vec3 earthlit = earth.rgb * (SCALE_EARTH * EARTHLIGHT_GAIN) + ambient * EARTH_FILL_GAIN;
    return vec4(earthlit * s.iUp.w + cabin * STATIC_SCALE, earth.a);
}

// interiorAt for the camera-space ray c; nothing when the interior is off.
vec4 interior(vec3 c) { return s.iRight.w == 0.0 ? vec4(0.0) : interiorAt(interiorRay(c)); }

// The cabin's own light along the cabin-frame direction d (static cabin and emissive layers, as in interiorAt); the
// window reflections use it, since the Earthlit cabin is lost against the bright Earth by day.
vec3 cabinLightAt(vec3 d) {
    vec2 uv;
    int f = cubeFace(d, uv);
    if (f == 5) return vec3(0.0);
    vec2 a = atlasUv(vec2(textureSize(interiorCabin, 0)), uv, 2048.0, SLOT_2048[f], Y0_2048[f]);
    return (textureLod(interiorCabin, a, 0.0).rgb * (SCALE_CABIN * CABIN_GAIN) +
            textureLod(interiorEmissive, a, 0.0).rgb * (SCALE_EMISSIVE * EMISSIVE_GAIN)) * STATIC_SCALE;
}

// Outward normal (cabin frame) of the pane the unit cabin-frame ray d looks through, and the ray's point on it (m).
vec3 pane(vec3 d, out vec3 hit) {
    vec3 eye = vec3(0.0, 0.0, EYE_Z);
    if (d.z > TOP_WINDOW_COS) {
        hit = eye + d * ((TOP_GLASS_Z - EYE_Z) / d.z);
        return vec3(0.0, 0.0, 1.0);
    }
    float a = round(atan(d.y, d.x) / (PI / 3.0)) * (PI / 3.0);
    vec3 n = vec3(sin(FACET_TILT) * cos(a), sin(FACET_TILT) * sin(a), cos(FACET_TILT));
    float plane = dot(n, vec3(HEX_APOTHEM * cos(a), HEX_APOTHEM * sin(a), Z_TOP)) + SIDE_DEPTH;
    hit = eye + d * ((plane - dot(n, eye)) / max(dot(n, d), 1e-3));
    return n;
}


// Bruneton 2017 mapping between [0, 1] and the texel centres of an n-texel axis.
float texcoordFromUnit(float x, float n) { return 0.5 / n + x * (1.0 - 1.0 / n); }
float unitFromTexcoord(float u, float n) { return (u - 0.5 / n) / (1.0 - 1.0 / n); }

// Transmittance LUT (atmosphere_lut.cpp transmittanceUv): from radius r along cos(zenith) mu to the top boundary.
vec3 transmittanceToTop(float r, float mu) {
    float h = sqrt(RT * RT - RG * RG);
    float rho = sqrt(max((r - RG) * (r + RG), 0.0));
    float sinZ = sqrt(max(1.0 - mu * mu, 0.0));
    float d = max(-r * mu + sqrt(max((RT - r * sinZ) * (RT + r * sinZ), 0.0)), 0.0);
    float dMin = RT - r;
    float dMax = rho + h;
    vec2 uv = vec2(texcoordFromUnit(clamp((d - dMin) / (dMax - dMin), 0.0, 1.0), float(ATMO_TRANSMITTANCE_W)),
                   texcoordFromUnit(clamp(rho / h, 0.0, 1.0), float(ATMO_TRANSMITTANCE_H)));
    return textureLod(transmittanceLut, uv, 0.0).rgb;
}

// Sunlight reaching radius r with the Sun at cos(zenith) muS: the LUT transmittance, faded over the Sun's disc as
// it sets behind the horizon of r.
vec3 sunlight(float r, float muS) {
    float muH = -sqrt(max(1.0 - (RG / r) * (RG / r), 0.0));
    float visible = smoothstep(muH - SUN_RADIUS, muH + SUN_RADIUS, muS);
    return visible == 0.0 ? vec3(0.0) : transmittanceToTop(r, max(muS, muH)) * visible;
}

// Sky irradiance on the ground per unit sun illuminance (irradiance LUT, u = 0.5 + 0.5 muS).
vec3 skyIrradiance(float muS) { return textureLod(irradianceLut, vec2(0.5 + 0.5 * muS, 0.5), 0.0).rgb; }

float rayleighPhase(float nu) { return 3.0 / (16.0 * PI) * (1.0 + nu * nu); }

float miePhase(float nu) {
    const float g = ATMO_MIE_G;
    return 3.0 / (8.0 * PI) * (1.0 - g * g) * (1.0 + nu * nu) / ((2.0 + g * g) * pow(1.0 + g * g - 2.0 * g * nu, 1.5));
}

// Integral of exp(-k t) over [0, dt] per channel, without cancellation for small k dt.
vec3 stepIntegral(vec3 k, float dt) {
    vec3 x = k * dt;
    return mix(dt * (1.0 - 0.5 * x), (1.0 - exp(-x)) / max(k, vec3(1e-12)), step(vec3(1e-3), x));
}

// Single plus multiple scattering along one view-ray segment [a, b] from the camera at radius r (cos zenith mu,
// cos angle to the Sun nu, the Sun's cos zenith at the camera muS0). Samples crowd at b (denseAtEnd) or at a.
void marchSegment(float r, float mu, float nu, float muS0, float a, float b, bool denseAtEnd, inout vec3 inscatter,
                  inout vec3 transmittance) {
    float phaseR = rayleighPhase(nu);
    float phaseM = miePhase(nu);
    float t0 = a;
    for (int i = 0; i < SKY_STEPS; ++i) {
        float x = float(i + 1) / float(SKY_STEPS);
        float f = denseAtEnd ? 1.0 - (1.0 - x) * (1.0 - x) : x * x;
        float t1 = a + (b - a) * f;
        float dt = t1 - t0;
        float t = 0.5 * (t0 + t1);
        t0 = t1;
        float ri = sqrt(max(r * r + t * t + 2.0 * r * mu * t, RG * RG));
        float h = ri - RG;
        vec3 rayleigh = RAYLEIGH_SCATTER * exp(-h / ATMO_RAYLEIGH_HEIGHT_KM);
        float mieDensity = exp(-h / ATMO_MIE_HEIGHT_KM);
        float mie = ATMO_MIE_SCATTER * mieDensity;
        vec3 extinction = rayleigh + ATMO_MIE_EXTINCTION * mieDensity +
                          OZONE_ABSORB * max(0.0, 1.0 - abs(h - ATMO_OZONE_CENTER_KM) / ATMO_OZONE_HALF_WIDTH_KM);
        float muS = clamp((r * muS0 + t * nu) / ri, -1.0, 1.0);
        vec3 multi = textureLod(multiScatterLut, vec2(0.5 + 0.5 * muS, h / (RT - RG)), 0.0).rgb;
        vec3 source = sunlight(ri, muS) * (rayleigh * phaseR + mie * phaseM) + (rayleigh + mie) * multi;
        inscatter += transmittance * source * stepIntegral(extinction, dt);
        transmittance *= exp(-extinction * dt);
    }
}

// Sky view LUT (ATMO_VIEW_W x ATMO_VIEW_H) in the camera's sky frame: u = azimuth from the Sun's axis, 0..pi (the
// sky is mirror-symmetric about the Sun's vertical plane); v in [0, 0.5): nadir angle 0 to the horizon (ground
// rays), v in [0.5, 1]: horizon to the atmosphere top (limb rays); both halves crowd their rows at the horizon and
// put their end texel centres on their end angles, so bilinear lookups never mix the two.
vec3 skyDirection(vec2 fragCoord, out bool ground) {
    float halfH = 0.5 * float(ATMO_VIEW_H);
    ground = fragCoord.y < halfH;
    float phi = PI * unitFromTexcoord(fragCoord.x / float(ATMO_VIEW_W), float(ATMO_VIEW_W));
    float k = unitFromTexcoord((ground ? fragCoord.y : fragCoord.y - halfH) / halfH, halfH);
    float thetaH = s.skyUp.w;
    float theta = ground ? thetaH * (1.0 - (1.0 - k) * (1.0 - k)) : thetaH + (s.skyAxis.w - thetaH) * k * k;
    vec3 y = cross(s.skyUp.xyz, s.skyAxis.xyz);
    return -s.skyUp.xyz * cos(theta) + (s.skyAxis.xyz * cos(phi) + y * sin(phi)) * sin(theta);
}

// MODE 3: inscattered radiance (per unit sun illuminance) and transmittance from the camera along the LUT texel's
// ray, to the ground or through the limb.
void skyTexel(vec2 fragCoord, out vec3 inscatter, out vec3 transmittance) {
    bool ground;
    vec3 d = skyDirection(fragCoord, ground);
    float r = length(s.camPos.xyz * TO_SPHERE);
    float mu = dot(d, s.skyUp.xyz);
    float nu = dot(d, s.sun.xyz);
    float muS0 = dot(s.skyUp.xyz, s.sun.xyz);
    inscatter = vec3(0.0);
    transmittance = vec3(1.0);
    float sinZ = sqrt(max(1.0 - mu * mu, 0.0));
    float topDisc = (RT - r * sinZ) * (RT + r * sinZ);
    if (topDisc <= 0.0) return;
    float mid = -r * mu;  // closest approach to the centre
    float entry = max(mid - sqrt(topDisc), 0.0);
    if (ground) {
        float groundHit = mid - sqrt(max((RG - r * sinZ) * (RG + r * sinZ), 0.0));
        marchSegment(r, mu, nu, muS0, entry, groundHit, true, inscatter, transmittance);
    } else {
        marchSegment(r, mu, nu, muS0, entry, mid, true, inscatter, transmittance);
        marchSegment(r, mu, nu, muS0, mid, mid + sqrt(topDisc), false, inscatter, transmittance);
    }
}

// View LUT lookup for the ECEF ray dir: inscatter (shader units) and transmittance to the ground (hit) or through
// the limb. Rays past the atmosphere top get none.
void skyLookup(vec3 dir, bool hit, out vec3 inscatter, out vec3 transmittance) {
    inscatter = vec3(0.0);
    transmittance = vec3(1.0);
    vec3 d = normalize(dir * TO_SPHERE);
    float theta = acos(clamp(-dot(d, s.skyUp.xyz), -1.0, 1.0));
    float thetaH = s.skyUp.w;
    float thetaT = s.skyAxis.w;
    if (!hit && theta >= thetaT) return;
    vec3 y = cross(s.skyUp.xyz, s.skyAxis.xyz);
    float phi = atan(abs(dot(d, y)), dot(d, s.skyAxis.xyz));
    float halfH = 0.5 * float(ATMO_VIEW_H);
    float v = hit ? 0.5 * texcoordFromUnit(1.0 - sqrt(1.0 - clamp(theta / thetaH, 0.0, 1.0)), halfH)
                  : 0.5 + 0.5 * texcoordFromUnit(sqrt(clamp((theta - thetaH) / (thetaT - thetaH), 0.0, 1.0)), halfH);
    vec2 uv = vec2(texcoordFromUnit(phi / PI, float(ATMO_VIEW_W)), v);
    inscatter = textureLod(skyInscatter, uv, 0.0).rgb * SUN_ILLUMINANCE;
    transmittance = textureLod(skyTransmittance, uv, 0.0).rgb;
}

// Unit-ellipsoid ray cast; c comes in precomputed in double. t = c / (-b + sqrt(disc)) avoids cancellation.
// Misses return the tangent point so neighbour differences stay continuous at the limb.
vec3 castEarth(vec3 dir, out bool hit) {
    vec3 inv = vec3(1.0 / A_KM, 1.0 / A_KM, 1.0 / B_KM);
    vec3 q = s.camPos.xyz * inv;
    vec3 d = dir * inv;
    float a = dot(d, d);
    float b = dot(q, d);
    float c = s.camPos.w;
    float disc = b * b - a * c;
    hit = disc >= 0.0 && b < 0.0;
    float root = sqrt(max(disc, 0.0));
    float t = b < 0.0 ? c / (-b + root) : -b / a;
    return s.camPos.xyz + dir * t;
}

vec2 lonLat(vec3 pos) {
    vec3 n = normalize(pos * vec3(1.0 / (A_KM * A_KM), 1.0 / (A_KM * A_KM), 1.0 / (B_KM * B_KM)));
    return vec2(atan(pos.y, pos.x), asin(clamp(n.z, -1.0, 1.0)));
}

// Zonal wind in m/s (east positive) by latitude: trade easterlies, westerlies, polar easterlies.
float zonalWind(float latRad) {
    float a = abs(degrees(latRad));
    return -6.0 + 16.0 * smoothstep(20.0, 35.0, a) - 13.0 * smoothstep(55.0, 70.0, a);
}

// Map uv shift after `hours` of the wind field that `cycle` seeds (zonal profile plus noise, about 1000 km scale).
// uv.x grows eastwards, uv.y southwards.
vec2 flowShift(vec2 uv, float latRad, float cycle, float hours) {
    vec2 seed = fract(vec2(0.618034, 0.414214) * cycle);
    vec2 n = textureLod(noiseTex, vec2(uv.x * 4.0, uv.y * 1.6) + seed, 0.0).rg * 2.0 - 1.0;
    vec2 km = vec2(zonalWind(latRad) + 5.0 * n.x, 3.0 * n.y) * (hours * 3.6);  // m/s x h = 3.6 km
    return vec2(km.x / (2.0 * PI * A_KM * max(cos(latRad), 0.2)), -km.y / (2.0 * LAT_MAX * A_KM));
}

// Cloud cover 0..1 at map uv: the Blue Marble 2002 map advected in two flow phases half a period apart (each fades
// out where it restarts), thinned or thickened over days by a slowly moving low-frequency field.
float cloudCover(vec2 uv, float latRad, vec2 gx, vec2 gy) {
    if (abs(latRad) > LAT_MAX) return 0.0;
    float fa = fract(s.clouds.x);
    float fb = fract(s.clouds.y);
    vec2 ua = uv - flowShift(uv, latRad, floor(s.clouds.x), fa * FLOW_PERIOD_H);
    vec2 ub = uv - flowShift(uv, latRad, floor(s.clouds.y), fb * FLOW_PERIOD_H);
    float c = mix(textureGrad(cloudMap, ub, gx, gy).r, textureGrad(cloudMap, ua, gx, gy).r, 1.0 - abs(2.0 * fa - 1.0));
    float d = textureLod(noiseTex, vec2(uv.x, uv.y * 0.4) + s.clouds.z * vec2(1.0 / 64.0, 1.0 / 128.0), 0.0).b;
    float thin = max(d - 0.5, 0.0) * 0.5;
    float thick = max(0.5 - d, 0.0) * 0.6;
    return clamp((c - thin) / (1.0 - thin) * (1.0 + thick), 0.0, 1.0);
}

// Stars map coordinate of an ECEF direction (galactic plate carree, l increasing to the left).
vec2 starUv(vec3 dir) {
    vec3 g = vec3(dot(s.starX.xyz, dir), dot(s.starY.xyz, dir), dot(s.starZ.xyz, dir));
    return vec2(0.5 - atan(g.y, g.x) / (2.0 * PI), 0.5 - asin(clamp(g.z, -1.0, 1.0)) / PI);
}

// Stars and the Milky Way along dir. stepPx as in earthRadiance.
vec3 starRadiance(vec3 dir, vec2 p, vec2 stepPx) {
    vec2 uv = starUv(dir);
    vec2 dx = starUv(ecefRay(cameraRay(p + vec2(stepPx.x, 0.0)))) - uv;
    vec2 dy = starUv(ecefRay(cameraRay(p + vec2(0.0, stepPx.y)))) - uv;
    dx.x -= round(dx.x);
    dy.x -= round(dy.x);
    return textureGrad(stars, uv, dx, dy).rgb * STAR_RADIANCE;
}

// How much of dir the Sun or Moon disc covers, 0..1 (the stars behind them are hidden).
float discCover(vec3 dir) {
    if (dot(dir, s.sun.xyz) > 0.0 && length(cross(dir, s.sun.xyz)) < SUN_RADIUS) return 1.0;
    float c = dot(dir, s.moon.xyz);
    if (c <= cos(MOON_RADIUS * 1.05)) return 0.0;
    return 1.0 - smoothstep(0.96, 1.0, length(dir - s.moon.xyz * c) / sin(MOON_RADIUS));
}

// Space behind the atmosphere along dir: stars and the Milky Way, and the Moon disc with its phase (it hides the
// stars behind it). stepPx as in earthRadiance. The scaled Earth pass leaves the stars to the full-resolution
// composite: one-texel stars upscaled from it twinkle as the view moves by fractions of a pixel.
vec3 spaceRadiance(vec3 dir, vec2 p, vec2 stepPx) {
    vec3 space = MODE == 1 ? vec3(0.0) : starRadiance(dir, p, stepPx);
    float sinSun = length(cross(dir, s.sun.xyz));
    if (dot(dir, s.sun.xyz) > 0.0 && sinSun < SUN_RADIUS) {
        float x = sinSun / SUN_RADIUS;
        return vec3(SUN_DISC * (1.0 - 0.6 * (1.0 - sqrt(max(1.0 - x * x, 0.0)))));
    }
    vec3 m = s.moon.xyz;
    float c = dot(dir, m);
    if (c > cos(MOON_RADIUS * 1.05)) {
        // Sphere normal under the ray (towards the viewer), lit by the Sun; soft one-pixel edge.
        vec3 o = (dir - m * c) / sin(MOON_RADIUS);
        float o2 = dot(o, o);
        vec3 n = o - m * sqrt(max(1.0 - o2, 0.0));
        float edge = 1.0 - smoothstep(0.96, 1.0, sqrt(o2));
        space = mix(space, vec3(MOON_ALBEDO * max(dot(normalize(n), s.sun.xyz), 0.0)), edge);
    }
    return space;
}

// Path (km) of the ray through the airglow shell, up to the ground if it hits; the camera is above the shell.
float airglowPath(vec3 dir, bool hit) {
    vec3 q = s.camPos.xyz * TO_SPHERE;
    vec3 d = normalize(dir * TO_SPHERE);
    float r = length(q);
    float tm = -dot(q, d);
    float b2 = r * r - tm * tm;
    float ro = RG + AIRGLOW_HIGH_KM;
    float ri = RG + AIRGLOW_LOW_KM;
    if (tm <= 0.0 || b2 >= ro * ro) return 0.0;
    float ho = sqrt(ro * ro - b2);
    float hi = b2 < ri * ri ? sqrt(ri * ri - b2) : 0.0;
    return hit ? ho - hi : 2.0 * (ho - hi);
}

// Integer hash (pcg3d) of a lattice point, three uniforms in [0, 1].
vec3 hash3(vec3 p) {
    uvec3 v = uvec3(ivec3(floor(p))) * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    return vec3(v) / 4294967295.0;
}

// Lightning glow at a night ground point under thick tropical cloud: each storm cell (and its neighbours, so glows
// cross cell edges) may flash in an animation-time slot and fades within it.
vec3 lightning(float lonRad, float latRad, float cover) {
    float storm = smoothstep(0.4, 0.8, cover) * (1.0 - smoothstep(radians(25.0), radians(40.0), abs(latRad)));
    if (storm <= 0.0) return vec3(0.0);
    vec2 g = vec2(degrees(lonRad), degrees(latRad)) / LIGHTNING_CELL_DEG;
    float slot = floor(pc.time / LIGHTNING_SLOT_S);
    float fade = exp(-4.0 * fract(pc.time / LIGHTNING_SLOT_S));
    float kmPerCell = LIGHTNING_CELL_DEG * 111.2;
    float sum = 0.0;
    for (int j = -1; j <= 1; ++j) {
        for (int i = -1; i <= 1; ++i) {
            vec2 cell = floor(g) + vec2(float(i), float(j));
            vec3 h = hash3(vec3(cell, slot));
            if (h.x > LIGHTNING_P) continue;
            vec2 km = (g - (cell + 0.25 + 0.5 * h.yz)) * kmPerCell * vec2(cos(latRad), 1.0);
            sum += exp(-dot(km, km) / (2.0 * LIGHTNING_KM * LIGHTNING_KM));
        }
    }
    return LIGHTNING * sum * fade * storm;
}

// Aurora emission strength at unit position u: the oval in magnetic latitude (75 deg at magnetic noon, 66 at
// midnight), curtains that drift along it in animation time, and an activity level that changes over days.
float auroraIntensity(vec3 u) {
    float aml = degrees(abs(asin(clamp(dot(u, MAG_POLE), -1.0, 1.0))));
    if (aml < 55.0) return 0.0;
    vec3 e1 = normalize(cross(MAG_POLE, vec3(0.0, 0.0, 1.0)));
    vec3 e2 = cross(MAG_POLE, e1);
    vec3 sunH = s.sun.xyz - MAG_POLE * dot(s.sun.xyz, MAG_POLE);
    vec3 uH = u - MAG_POLE * dot(u, MAG_POLE);
    float cosMlt = dot(normalize(uH), normalize(sunH));
    float oval = exp(-pow((aml - (70.5 + 4.5 * cosMlt)) / (2.0 + 1.5 * (1.0 - cosMlt)), 2.0));
    float mlon = atan(dot(u, e2), dot(u, e1)) / (2.0 * PI);
    float t = pc.time / 86400.0;  // whole tiles per day keep the drift continuous across the wrap
    float fold = textureLod(noiseTex, vec2(mlon * 24.0 + t * 48.0, aml / 10.0 + t * 24.0), 0.0).r;
    float activity = 0.35 + 0.65 * smoothstep(0.25, 0.75, textureLod(noiseTex, vec2(s.clouds.z / 32.0, 0.61), 0.0).g);
    return oval * (0.25 + 1.5 * fold * fold * fold) * activity;
}

// Aurora radiance along the near part of the ray through the emitting shell (8 steps), where that air is in the
// Earth's shadow; the camera is above the shell.
vec3 auroraRadiance(vec3 dir) {
    vec3 q = s.camPos.xyz * TO_SPHERE;
    vec3 d = normalize(dir * TO_SPHERE);
    float r = length(q);
    float tm = -dot(q, d);
    float b2 = r * r - tm * tm;
    float ro = RG + AURORA_HIGH_KM;
    float ri = RG + AURORA_LOW_KM;
    if (tm <= 0.0 || b2 >= ro * ro) return vec3(0.0);
    float t0 = tm - sqrt(ro * ro - b2);
    float t1 = b2 < ri * ri ? tm - sqrt(ri * ri - b2) : tm + sqrt(ro * ro - b2);
    vec3 mid = normalize(q + d * (0.5 * (t0 + t1)));
    if (abs(dot(mid, MAG_POLE)) < 0.64) return vec3(0.0);  // segment middle below 40 deg magnetic latitude
    float dt = (t1 - t0) / 8.0;
    vec3 sum = vec3(0.0);
    for (int i = 0; i < 8; ++i) {
        vec3 p = q + d * (t0 + (float(i) + 0.5) * dt);
        float rp = length(p);
        vec3 u = p / rp;
        float dark = 1.0 - smoothstep(-0.20, -0.10, dot(u, s.sun.xyz));
        if (dark <= 0.0) continue;
        float h = rp - RG;
        vec3 colour = AURORA_GREEN * exp(-pow((h - 115.0) / 25.0, 2.0)) + AURORA_RED * 0.15 * exp(-pow((h - 230.0) / 45.0, 2.0));
        sum += colour * auroraIntensity(u) * dark;
    }
    return sum * dt * AURORA_RADIANCE;
}

// day_detail (4 x 2 tiles of 90 x 72 deg, one per layer, CLAMP_TO_EDGE). Within half a filtered texel of an inner tile
// edge the neighbour tile's edge texels are blended in, as one continuous texture would filter: clamping alone repeats
// each side's edge texel and leaves a seam band (R7 seam sweep).
vec3 dayDetailAt(float lon, float lat, vec2 dLonLatX, vec2 dLonLatY) {
    float colF = (lon + PI) / (0.5 * PI);
    float col = clamp(floor(colF), 0.0, 3.0);
    float row = lat < 0.0 ? 1.0 : 0.0;
    vec2 uv = vec2(colF - col, (row == 0.0 ? LAT_MAX - lat : -lat) / LAT_MAX);
    vec2 scaleTile = vec2(1.0 / (0.5 * PI), -1.0 / LAT_MAX);
    vec2 gx = dLonLatX * scaleTile;
    vec2 gy = dLonLatY * scaleTile;
    float layer = col + 4.0 * row;
    vec3 c = textureGrad(dayDetail, vec3(uv, layer), gx, gy).rgb;
    vec2 size = vec2(textureSize(dayDetail, 0).xy);
    vec2 halfTexel = 0.5 * max(vec2(1.0), max(abs(gx), abs(gy)) * size) / size;
    if (uv.x < halfTexel.x) {
        vec3 n = textureGrad(dayDetail, vec3(1.0, uv.y, mod(col + 3.0, 4.0) + 4.0 * row), gx, gy).rgb;
        c = mix(n, c, 0.5 + 0.5 * uv.x / halfTexel.x);
    } else if (uv.x > 1.0 - halfTexel.x) {
        vec3 n = textureGrad(dayDetail, vec3(0.0, uv.y, mod(col + 1.0, 4.0) + 4.0 * row), gx, gy).rgb;
        c = mix(n, c, 0.5 + 0.5 * (1.0 - uv.x) / halfTexel.x);
    }
    // The equator is the only inner edge in v (72 deg is the map's end).
    if (row == 0.0 && uv.y > 1.0 - halfTexel.y) {
        vec3 n = textureGrad(dayDetail, vec3(uv.x, 0.0, col + 4.0), gx, gy).rgb;
        c = mix(n, c, 0.5 + 0.5 * (1.0 - uv.y) / halfTexel.y);
    } else if (row == 1.0 && uv.y < halfTexel.y) {
        vec3 n = textureGrad(dayDetail, vec3(uv.x, 1.0, col), gx, gy).rgb;
        c = mix(n, c, 0.5 + 0.5 * uv.y / halfTexel.y);
    }
    return c;
}

// Linear Earth radiance at physical pixel position p; stepPx = one rendered pixel in physical pixels.
// Texture footprints come from the neighbouring pixels' ground points, not dFdx, so callers may branch.
vec3 earthRadiance(vec2 p, vec2 stepPx) {
    vec3 dir = ecefRay(cameraRay(p));
    bool hit;
    vec3 pos = castEarth(dir, hit);
    bool atmosphere = (pc.effects & DEBUG_NO_ATMOSPHERE) == 0;
    vec3 inscatter = vec3(0.0);
    vec3 viewTransmittance = vec3(1.0);
    if (atmosphere) skyLookup(dir, hit, inscatter, viewTransmittance);
    if ((pc.effects & DEBUG_NO_INSCATTER) != 0) inscatter = vec3(0.0);
    vec3 airglow = atmosphere ? AIRGLOW * airglowPath(dir, hit) : vec3(0.0);
    if (atmosphere && (pc.effects & DEBUG_NO_AURORA) == 0) airglow += auroraRadiance(dir);
    if (!hit) return spaceRadiance(dir, p, stepPx) * viewTransmittance + inscatter + airglow;
    bool unused;
    vec2 ll = lonLat(pos);
    vec2 llx = lonLat(castEarth(ecefRay(cameraRay(p + vec2(stepPx.x, 0.0))), unused));
    vec2 lly = lonLat(castEarth(ecefRay(cameraRay(p + vec2(0.0, stepPx.y))), unused));
    vec2 dLonLatX = vec2(wrapDelta(llx.x - ll.x), llx.y - ll.y);
    vec2 dLonLatY = vec2(wrapDelta(lly.x - ll.x), lly.y - ll.y);
    float lon = ll.x;
    float lat = ll.y;
    vec3 inv = vec3(1.0 / A_KM, 1.0 / A_KM, 1.0 / B_KM);
    vec3 n = normalize(pos * inv * inv);  // geodetic up

    // Equirectangular maps: u = lon / 2pi + 0.5 (REPEAT), v = (72 deg - lat) / 144 deg.
    vec2 uv = vec2(lon / (2.0 * PI) + 0.5, (LAT_MAX - lat) / (2.0 * LAT_MAX));
    vec2 scaleUv = vec2(1.0 / (2.0 * PI), -1.0 / (2.0 * LAT_MAX));
    vec2 gx = dLonLatX * scaleUv;
    vec2 gy = dLonLatY * scaleUv;

    vec3 detail = dayDetailAt(lon, lat, dLonLatX, dLonLatY);

    vec3 month0 = textureGrad(monthlyColour, vec3(uv, s.up.w), gx, gy).rgb;
    vec3 month1 = textureGrad(monthlyColour, vec3(uv, s.back.w), gx, gy).rgb;
    vec3 july = textureGrad(monthlyColour, vec3(uv, JULY_LAYER), gx, gy).rgb;
    // day_detail is the July layer at full resolution: month / July is the low-frequency seasonal tint per channel.
    vec3 albedo = detail * clamp(mix(month0, month1, s.right.w) / max(july, vec3(1e-3)), 0.25, 4.0);

    vec2 nxy = textureGrad(terrainNormal, uv, gx, gy).ga * 2.0 - 1.0;
    float water = textureGrad(waterMask, uv, gx, gy).r;
    vec3 east = normalize(vec3(-n.y, n.x, 0.0));
    vec3 north = cross(n, east);
    vec3 relief = normalize(east * nxy.x + north * nxy.y + n * sqrt(max(0.0, 1.0 - dot(nxy, nxy))));

    // Water: flat surface of one colour; the box-filtered mask blends the coastline.
    albedo = mix(albedo, OCEAN_ALBEDO, water);
    relief = normalize(mix(relief, n, water));

    if (abs(lat) > LAT_MAX) {
        albedo = POLAR_ALBEDO;
        relief = n;
        water = 0.0;
    }

    vec3 l = s.sun.xyz;
    vec3 v = -dir;
    // With the atmosphere: sunlight through the transmittance LUT plus sky irradiance. Without: a soft terminator on
    // the ellipsoid normal, sun elevation from about -1 to +2.3 degrees.
    float muS = dot(n, l);
    float daylight = smoothstep(-0.02, 0.04, muS);
    vec3 sun = atmosphere ? sunlight(RG, muS) : vec3(daylight);
    vec3 sky = atmosphere ? skyIrradiance(muS) : vec3(0.0);

    // Clouds and their shadows: the shadow is the cover where the ray to the Sun crosses the cloud layer.
    bool clouds = (pc.effects & DEBUG_NO_CLOUDS) == 0;
    float cover = clouds ? cloudCover(uv, lat, gx, gy) : 0.0;
    float shadow = 1.0;
    if (clouds && (pc.effects & DEBUG_NO_CLOUD_SHADOWS) == 0 && muS > 0.0) {
        vec2 toSun = vec2(dot(l, east), dot(l, north));
        float horizontal = length(toSun);
        vec2 km = horizontal > 1e-4 ? toSun / horizontal * CLOUD_ALT_KM * min(horizontal / max(muS, 0.05), 20.0)
                                    : vec2(0.0);
        vec2 suv = uv + vec2(km.x / (2.0 * PI * A_KM * max(cos(lat), 0.2)), -km.y / (2.0 * LAT_MAX * A_KM));
        shadow = 1.0 - CLOUD_SHADOW * cloudCover(suv, lat + km.y / A_KM, gx, gy);
    }
    vec3 diffuse = albedo * (max(dot(relief, l), 0.0) * sun * shadow + sky);

    // Sun glint: Beckmann lobe on the flat sea surface, Schlick Fresnel; radiance with irradiance pi.
    vec3 h = normalize(l + v);
    float nh = max(dot(n, h), 1e-4);
    float nh2 = nh * nh;
    float beckmann = exp((nh2 - 1.0) / (GLINT_M2 * nh2)) / (PI * GLINT_M2 * nh2 * nh2);
    float fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - max(dot(v, h), 0.0), 5.0);
    float nv = max(dot(n, v), 1e-3);
    vec3 glint = water * fresnel * beckmann * PI / (4.0 * nv) * sun * step(0.0, muS) * shadow;

    // Cloud tops: sunlit until the Sun sets below their own (higher) horizon, plus the ground's sky light.
    float rc = RG + CLOUD_ALT_KM;
    float muHc = -sqrt(max(1.0 - (RG / rc) * (RG / rc), 0.0));
    vec3 cloudSun = atmosphere ? sunlight(rc, muS) : vec3(daylight);
    vec3 cloud = CLOUD_ALBEDO * (clamp((muS - muHc) / (1.0 - muHc), 0.0, 1.0) * cloudSun + sky);

    // Moonlight on the ground and the cloud tops (Scene.moonLight, relative to the Sun), through the same air.
    float moonLight = (pc.effects & DEBUG_NO_MOONLIGHT) == 0 ? s.moon.w : 0.0;
    if (moonLight > 0.0) {
        vec3 m = s.moon.xyz;
        float muM = dot(n, m);
        vec3 moonGround = atmosphere ? sunlight(RG, muM) : vec3(step(0.0, muM));
        vec3 moonCloud = atmosphere ? sunlight(rc, muM) : vec3(step(muHc, muM));
        diffuse += albedo * max(dot(relief, m), 0.0) * moonGround * moonLight * shadow;
        cloud += CLOUD_ALBEDO * clamp((muM - muHc) / (1.0 - muHc), 0.0, 1.0) * moonCloud * moonLight;
    }

    // City lights from civil twilight on, glowing dimmer through clouds.
    vec3 lights = vec3(0.0);
    if ((pc.effects & DEBUG_NO_CITY_LIGHTS) == 0 && muS < 0.0) {
        float night = 1.0 - smoothstep(-0.10, 0.0, muS);
        lights = pow(textureGrad(cityLights, uv, gx, gy).r, CITY_GAMMA) * CITY_LIGHT * night * (1.0 - 0.6 * cover);
    }
    if ((pc.effects & (DEBUG_NO_LIGHTNING | MOTION_REDUCED)) == 0 && muS < -0.05 && cover > 0.4) {
        lights += lightning(lon, lat, cover);
    }

    vec3 radiance = (mix(diffuse + glint, cloud, cover) + lights) * viewTransmittance + inscatter + airglow;
    if (s.homeCity.w != 0.0) {
        // Ground km per physical pixel from the neighbouring ground point; the marker keeps its screen size.
        float kmPerPx = A_KM * length(dLonLatX * vec2(cos(lat), 1.0)) / stepPx.x;
        float r = distance(pos, s.homeCity.xyz) / max(kmPerPx, 1e-6);
        float mark = max(1.0 - smoothstep(1.0, 2.0, abs(r - MARKER_RING_PX)), 1.0 - smoothstep(2.0, 3.0, r));
        radiance += MARKER * mark / s.iBack.w;
    }
    return radiance;
}

// Dust on the pane at hit (normal n, m): in DUST_P of the DUST_CELL_M cells one speck of 0.3-0.8 mm at a random
// spot; 0..1.
const float DUST_CELL_M = 0.003;
const float DUST_P = 0.15;
float dustAt(vec3 hit, vec3 n) {
    vec3 u = abs(n.z) > 0.99 ? vec3(1.0, 0.0, 0.0) : normalize(vec3(-n.y, n.x, 0.0));
    vec2 q = vec2(dot(hit, u), dot(hit, cross(n, u))) / DUST_CELL_M;
    vec2 cell = floor(q);
    vec3 h = hash3(vec3(cell, 7.0));
    float speck = 0.0;
    if (h.x < DUST_P) {
        float radius = (0.15 + 0.25 * h.x / DUST_P) * 0.001 / DUST_CELL_M;
        speck = 1.0 - smoothstep(0.3 * radius, radius, length(q - cell - 0.2 - 0.6 * h.yz));
    }
    return speck;
}

// How far opaque cabin structure leaves the Sun open to the camera, 0..1 (one value per frame: the Earth pass writes
// it into its target's alpha for the composite).
float sunOpen() {
    if (s.iRight.w == 0.0) return 1.0;
    vec3 c = vec3(dot(s.sun.xyz, s.right.xyz), dot(s.sun.xyz, s.up.xyz), dot(s.sun.xyz, s.back.xyz));
    return 1.0 - smoothstep(0.5, OPAQUE, interiorAlpha(c));
}
// Sunlight on the outer window glass per unit sunlight: the Sun not behind the Earth and on the outer side of the
// cupola (cabin +Z, the round window's axis; the side windows tilt outwards from it). The camera need not see the Sun.
vec3 sunOnGlass() { return s.iRight.w != 0.0 ? s.sunLight.xyz * s.sunLight.w : vec3(0.0); }

// Squared angle (rad^2) between the camera ray at physical pixel p and the Sun.
float sunAngle2(vec2 p) {
    vec3 sunCam = vec3(dot(s.sun.xyz, s.right.xyz), dot(s.sun.xyz, s.up.xyz), dot(s.sun.xyz, s.back.xyz));
    return 2.0 * (1.0 - dot(normalize(cameraRay(p)), sunCam));
}

// Bloom of the Sun in sight at physical pixel p (linear radiance before exposure); open = sunOpen().
vec3 bloom(vec2 p, float open) {
    if ((pc.effects & EFFECT_BLOOM) == 0 || open <= 0.0 || max(s.sunLight.r, max(s.sunLight.g, s.sunLight.b)) <= 0.0) {
        return vec3(0.0);
    }
    float theta2 = sunAngle2(p);
    return s.sunLight.xyz * open * (BLOOM_NARROW / (1.0 + theta2 / (BLOOM_NARROW_RAD * BLOOM_NARROW_RAD)) +
                                    BLOOM_WIDE / (1.0 + theta2 / (BLOOM_WIDE_RAD * BLOOM_WIDE_RAD)));
}

// Share of the light the pane with normal n reflects for interior ray d (Schlick).
float glassFresnel(vec3 d, vec3 n) { return GLASS_F0 + (1.0 - GLASS_F0) * pow(1.0 - abs(dot(d, n)), 5.0); }

// What the window glass does to the exposed Earth radiance at physical pixel p: cabin reflections (Fresnel), and the
// forward scatter of sunlit glass (window glare) and its dust. Runs where the Earth shows (the scaled Earth pass).
vec3 throughGlass(vec2 p, vec3 earth) {
    if (s.iRight.w == 0.0 || (pc.effects & (EFFECT_REFLECTIONS | EFFECT_WINDOW_GLARE | EFFECT_DUST)) == 0) return earth;
    vec3 d = normalize(interiorRay(cameraRay(p)));
    vec3 hit;
    vec3 n = pane(d, hit);
    if ((pc.effects & EFFECT_REFLECTIONS) != 0) earth = mix(earth, cabinLightAt(reflect(d, n)) * s.iBack.w, glassFresnel(d, n));
    vec3 lit = sunOnGlass();
    if ((pc.effects & (EFFECT_WINDOW_GLARE | EFFECT_DUST)) != 0 && max(lit.r, max(lit.g, lit.b)) > 0.0) {
        float scatter = 0.0;
        if ((pc.effects & EFFECT_WINDOW_GLARE) != 0) scatter += WINDOW_GLARE;
        if ((pc.effects & EFFECT_DUST) != 0) scatter += DUST * dustAt(hit, n);
        earth += lit * scatter / (1.0 + sunAngle2(p) / (WINDOW_GLARE_RAD * WINDOW_GLARE_RAD)) * s.iBack.w;
    }
    return earth;
}

void main() {
    if (MODE == 3) {
        vec3 inscatter;
        vec3 transmittance;
        skyTexel(gl_FragCoord.xy, inscatter, transmittance);
        outColor = vec4(inscatter, 1.0);
        outTransmittance = vec4(transmittance, 1.0);
        return;
    }
    if (MODE == 1) {
        // Scaled target: skip texels whose whole bilinear footprint (one target texel around) is interior. Alpha
        // carries sunOpen() to the composite.
        vec2 stepPx = 1.0 / pc.earthScale;
        vec2 p = gl_FragCoord.xy * stepPx;
        float open = sunOpen();
        if (s.iRight.w != 0.0) {
            float a = min(min(interiorAlpha(cameraRay(p + vec2(-stepPx.x, -stepPx.y))),
                              interiorAlpha(cameraRay(p + vec2(stepPx.x, -stepPx.y)))),
                          min(interiorAlpha(cameraRay(p + vec2(-stepPx.x, stepPx.y))),
                              interiorAlpha(cameraRay(p + vec2(stepPx.x, stepPx.y)))));
            if (a > OPAQUE) {
                outColor = vec4(0.0, 0.0, 0.0, open);
                return;
            }
        }
        outColor = vec4(encodeHdr(throughGlass(p, earthRadiance(p, stepPx) * s.iBack.w)), open);  // sRGB attachment
        return;
    }
    vec2 p = gl_FragCoord.xy;
    vec4 inside = interior(cameraRay(p));
    vec3 earth = vec3(0.0);
    float open;
    if (MODE == 2) {
        vec4 scaled = textureLod(earthImage, p / vec2(pc.extent), 0.0);
        open = scaled.a;
        if (inside.a <= OPAQUE) {
            earth = decodeHdr(scaled.rgb);
            // The stars at full resolution (spaceRadiance), through the atmosphere and the glass.
            vec3 dir = ecefRay(cameraRay(p));
            bool hit;
            castEarth(dir, hit);
            if (!hit) {
                vec3 inscatter;
                vec3 t = vec3(1.0);
                if ((pc.effects & DEBUG_NO_ATMOSPHERE) == 0) skyLookup(dir, hit, inscatter, t);
                if (s.iRight.w != 0.0 && (pc.effects & EFFECT_REFLECTIONS) != 0) {
                    vec3 d = normalize(interiorRay(cameraRay(p)));
                    vec3 paneHit;
                    t *= 1.0 - glassFresnel(d, pane(d, paneHit));
                }
                earth += starRadiance(dir, p, vec2(1.0)) * (1.0 - discCover(dir)) * t * s.iBack.w;
            }
        }
    } else {
        if (inside.a <= OPAQUE) earth = throughGlass(p, earthRadiance(p, vec2(1.0)) * s.iBack.w);
        open = sunOpen();
    }
    vec3 glare = bloom(p, open) * s.iBack.w;
    outColor = vec4(encodeSrgb(toneMap(earth * (1.0 - inside.a) + inside.rgb * s.iBack.w + glare)), 1.0);
}