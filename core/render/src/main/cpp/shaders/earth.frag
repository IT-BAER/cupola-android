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

// Interior atlases, faces +X, -X, +Y, -Y, +Z: first atlas row of the face slot and crop row y0, on the
// 2048 grid (earth, cabin, emissive) and the 512 grid (ambient).
const float SLOT_2048[5] = float[](0.0, 1824.0, 2976.0, 4800.0, 5952.0);
const float Y0_2048[5] = float[](311.0, 925.0, 301.0, 922.0, 0.0);
const float SLOT_512[5] = float[](0.0, 456.0, 744.0, 1200.0, 1488.0);
const float Y0_512[5] = float[](77.0, 231.0, 75.0, 230.0, 0.0);
// Bake manifest `scale` per layer (r3): linear radiance = decoded texel x scale.
const float SCALE_EARTH = 0.37785929441452026;
const float SCALE_CABIN = 5.627439498901367;
const float SCALE_EMISSIVE = 4.801466941833496;
const float SCALE_AMBIENT = 0.3945154845714569;
// Static light mix of the P4.7 review (cabin light at 5 % of its built strength, laptop and LEDs at built
// strength, decision 26, a dim fill), scaled by STATIC_SCALE so the night interior keeps its photo median at
// the night exposure (Exposure.KEY / NIGHT_FLOOR); by day it is negligible next to the Earthlight.
const float CABIN_GAIN = 0.05;
const float EMISSIVE_GAIN = 1.0;
const float AMBIENT_GAIN = 0.02;
const float STATIC_SCALE = 2.0e-4;
// Earthlight gains over the mean Earth radiance (Scene.earthLight): the Earthlight layer, and the uniform fill
// (ambient layer) standing in for the bounce light the bake's low albedos lose. Tuned to the P4.7 day photo.
const float EARTHLIGHT_GAIN = 18.0;
const float EARTH_FILL_GAIN = 6.0;
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

float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

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

// Interior radiance (linear, rgb) and window alpha (1 = interior surface, 0 = view through a window).
vec4 interior(vec3 c) {
    if (s.iRight.w == 0.0) return vec4(0.0);
    vec2 uv;
    int f = cubeFace(interiorRay(c), uv);
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

// Space behind the atmosphere along dir: stars and the Milky Way, and the Moon disc with its phase (it hides the
// stars behind it). stepPx as in earthRadiance.
vec3 spaceRadiance(vec3 dir, vec2 p, vec2 stepPx) {
    vec2 uv = starUv(dir);
    vec2 dx = starUv(ecefRay(cameraRay(p + vec2(stepPx.x, 0.0)))) - uv;
    vec2 dy = starUv(ecefRay(cameraRay(p + vec2(0.0, stepPx.y)))) - uv;
    dx.x -= round(dx.x);
    dy.x -= round(dy.x);
    vec3 space = textureGrad(stars, uv, dx, dy).rgb * STAR_RADIANCE;
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

    // day_detail: 4 x 2 tiles of 90 x 72 deg, one per layer, each sampled with CLAMP_TO_EDGE.
    float col = clamp(floor((lon + PI) / (0.5 * PI)), 0.0, 3.0);
    float row = lat < 0.0 ? 1.0 : 0.0;
    vec2 tileUv = vec2((lon + PI) / (0.5 * PI) - col, (row == 0.0 ? LAT_MAX - lat : -lat) / LAT_MAX);
    vec2 scaleTile = vec2(1.0 / (0.5 * PI), -1.0 / LAT_MAX);
    vec3 detail = textureGrad(dayDetail, vec3(tileUv, col + 4.0 * row), dLonLatX * scaleTile, dLonLatY * scaleTile).rgb;

    vec3 month0 = textureGrad(monthlyColour, vec3(uv, s.up.w), gx, gy).rgb;
    vec3 month1 = textureGrad(monthlyColour, vec3(uv, s.back.w), gx, gy).rgb;
    vec3 july = textureGrad(monthlyColour, vec3(uv, JULY_LAYER), gx, gy).rgb;
    float detailRatio = clamp(luminance(detail) / max(luminance(july), 1e-3), 0.25, 4.0);
    vec3 albedo = mix(month0, month1, s.right.w) * detailRatio;

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

    return (mix(diffuse + glint, cloud, cover) + lights) * viewTransmittance + inscatter + airglow;
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
        // Scaled target: skip texels whose whole bilinear footprint (one target texel around) is interior.
        vec2 stepPx = 1.0 / pc.earthScale;
        vec2 p = gl_FragCoord.xy * stepPx;
        if (s.iRight.w != 0.0) {
            float a = min(min(interiorAlpha(cameraRay(p + vec2(-stepPx.x, -stepPx.y))),
                              interiorAlpha(cameraRay(p + vec2(stepPx.x, -stepPx.y)))),
                          min(interiorAlpha(cameraRay(p + vec2(-stepPx.x, stepPx.y))),
                              interiorAlpha(cameraRay(p + vec2(stepPx.x, stepPx.y)))));
            if (a > OPAQUE) {
                outColor = vec4(0.0, 0.0, 0.0, 1.0);
                return;
            }
        }
        outColor = vec4(encodeHdr(earthRadiance(p, stepPx) * s.iBack.w), 1.0);  // sRGB attachment
        return;
    }
    vec2 p = gl_FragCoord.xy;
    vec4 inside = interior(cameraRay(p));
    vec3 earth = vec3(0.0);
    if (inside.a <= OPAQUE) {
        earth = MODE == 2 ? decodeHdr(textureLod(earthImage, p / vec2(pc.extent), 0.0).rgb)
                          : earthRadiance(p, vec2(1.0)) * s.iBack.w;
    }
    outColor = vec4(encodeSrgb(toneMap(earth * (1.0 - inside.a) + inside.rgb * s.iBack.w)), 1.0);
}
