#version 450

// P3.1 Earth: per-pixel ray cast against the WGS-84 ellipsoid (no mesh), day colour, relief and sun glint,
// seen through the baked Cupola interior panorama (decision 22).
// Texture contract: tools/textures/README.md "Runtime contract (P5.5)" and "interior_*";
// panorama contract: tools/blender/README.md "Runtime contract".

// 0: Earth and interior in one full-resolution pass. 1: Earth only into the scaled target, skipped where the
// interior covers it. 2: interior over the scaled Earth target, full resolution.
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
} pc;

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
} s;

layout(set = 0, binding = 1) uniform sampler2DArray dayDetail;
layout(set = 0, binding = 2) uniform sampler2DArray monthlyColour;
layout(set = 0, binding = 5) uniform sampler2D terrainNormal;
layout(set = 0, binding = 7) uniform sampler2D waterMask;
layout(set = 0, binding = 8) uniform sampler2D interiorEarth;
layout(set = 0, binding = 9) uniform sampler2D interiorCabin;
layout(set = 0, binding = 10) uniform sampler2D interiorEmissive;
layout(set = 0, binding = 11) uniform sampler2D interiorAmbient;
layout(set = 0, binding = 12) uniform sampler2D earthImage;  // MODE 2: the scaled Earth target (linear)

layout(location = 0) out vec4 outColor;

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

// Linear Earth radiance at physical pixel position p; stepPx = one rendered pixel in physical pixels.
// Texture footprints come from the neighbouring pixels' ground points, not dFdx, so callers may branch.
vec3 earthRadiance(vec2 p, vec2 stepPx) {
    vec3 dir = ecefRay(cameraRay(p));
    bool hit;
    vec3 pos = castEarth(dir, hit);
    if (!hit) return vec3(0.0);
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
    // Soft terminator on the ellipsoid normal: sun elevation from about -1 to +2.3 degrees.
    float daylight = smoothstep(-0.02, 0.04, dot(n, l));
    vec3 diffuse = albedo * max(dot(relief, l), 0.0) * daylight;

    // Sun glint: Beckmann lobe on the flat sea surface, Schlick Fresnel; radiance with irradiance pi.
    vec3 h = normalize(l + v);
    float nh = max(dot(n, h), 1e-4);
    float nh2 = nh * nh;
    float beckmann = exp((nh2 - 1.0) / (GLINT_M2 * nh2)) / (PI * GLINT_M2 * nh2 * nh2);
    float fresnel = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - max(dot(v, h), 0.0), 5.0);
    float nv = max(dot(n, v), 1e-3);
    float glint = water * fresnel * beckmann * PI / (4.0 * nv) * daylight * step(0.0, dot(n, l));

    return diffuse + vec3(glint);
}

void main() {
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
