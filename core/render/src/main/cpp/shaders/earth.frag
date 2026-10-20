#version 450

// P3.1 Earth: per-pixel ray cast against the WGS-84 ellipsoid (no mesh), day colour, relief and sun glint.
// Texture contract: tools/textures/README.md "Runtime contract (P5.5)".

// Must match cupola::PushConstants in device.h.
layout(push_constant) uniform Push {
    ivec2 extent;
    int rotation;
    int loadIterations;
    float load;
    float time;
    float lookYawDeg;
    float lookPitchDeg;
} pc;

// FrameInputs floats from SCENE_BLOCK (frame_inputs.h); ECEF, km, unit directions.
layout(set = 0, binding = 0, std140) uniform Scene {
    vec4 camPos;   // xyz camera position, w = |q|^2 - 1 of the ellipsoid-scaled position
    vec4 right;    // xyz camera right, w = month weight
    vec4 up;       // xyz camera up, w = month layer 0
    vec4 back;     // xyz camera back (view = -back), w = month layer 1
    vec4 sun;      // xyz sun direction, w = tan(fov / 2) on the long axis
    vec4 moon;     // xyz Moon direction (ISS to Moon)
} s;

layout(set = 0, binding = 1) uniform sampler2DArray dayDetail;
layout(set = 0, binding = 2) uniform sampler2DArray monthlyColour;
layout(set = 0, binding = 5) uniform sampler2D terrainNormal;
layout(set = 0, binding = 7) uniform sampler2D waterMask;

layout(location = 0) out vec4 outColor;

const float A_KM = 6378.137;
const float B_KM = 6356.752314245179;
const float PI = 3.14159265358979;
const float LAT_MAX = 1.2566370614359172;  // 72 deg: edge of every Earth map
const float JULY_LAYER = 6.0;              // day_detail is July 2004
const vec3 POLAR_ALBEDO = vec3(0.55, 0.58, 0.62);
// Ocean slope variance (Cox-Munk, about 7 m/s wind) for the Beckmann glint lobe; water F0.
const float GLINT_M2 = 0.04;
const float WATER_F0 = 0.02;

ivec2 logicalPixel(ivec2 p) {
    int w = pc.extent.x;
    int h = pc.extent.y;
    if (pc.rotation == 1) return ivec2(p.y, w - 1 - p.x);
    if (pc.rotation == 2) return ivec2(w - 1 - p.x, h - 1 - p.y);
    if (pc.rotation == 3) return ivec2(h - 1 - p.y, p.x);
    return p;
}

float luminance(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Wraps a longitude difference into [-pi, pi], so derivatives stay small across 180 deg.
float wrapDelta(float d) { return d - 2.0 * PI * round(d / (2.0 * PI)); }

vec3 encodeSrgb(vec3 c) {
    c = clamp(c, 0.0, 1.0);
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

void main() {
    vec2 size = vec2(pc.rotation % 2 == 1 ? pc.extent.yx : pc.extent);
    vec2 p = vec2(logicalPixel(ivec2(gl_FragCoord.xy))) + 0.5;
    vec2 tanHalf = s.sun.w * size / max(size.x, size.y);
    vec2 ndc = vec2(2.0 * p.x / size.x - 1.0, 1.0 - 2.0 * p.y / size.y);
    vec3 dir = normalize(s.right.xyz * (ndc.x * tanHalf.x) + s.up.xyz * (ndc.y * tanHalf.y) - s.back.xyz);

    // Unit-ellipsoid space; c comes in precomputed in double. t = c / (-b + sqrt(disc)) avoids cancellation.
    vec3 inv = vec3(1.0 / A_KM, 1.0 / A_KM, 1.0 / B_KM);
    vec3 q = s.camPos.xyz * inv;
    vec3 d = dir * inv;
    float a = dot(d, d);
    float b = dot(q, d);
    float c = s.camPos.w;
    float disc = b * b - a * c;
    bool hit = disc >= 0.0 && b < 0.0;
    // Misses use the tangent point so derivatives stay continuous at the limb.
    float root = sqrt(max(disc, 0.0));
    float t = b < 0.0 ? c / (-b + root) : -b / a;
    vec3 pos = s.camPos.xyz + dir * t;

    vec3 n = normalize(pos * inv * inv);  // geodetic up
    float lat = asin(clamp(n.z, -1.0, 1.0));
    float lon = atan(pos.y, pos.x);
    vec2 dLonLatX = vec2(wrapDelta(dFdx(lon)), dFdx(lat));
    vec2 dLonLatY = vec2(wrapDelta(dFdy(lon)), dFdy(lat));

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

    vec3 radiance = diffuse + vec3(glint);
    outColor = vec4(hit ? encodeSrgb(radiance) : vec3(0.0), 1.0);
}
