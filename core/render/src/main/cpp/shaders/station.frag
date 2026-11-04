#version 450

// P3.6 station exterior shading in the Cupola frame: sunlight (diffuse plus GGX specular from the ORM texture) and
// the Earth below (+Z) as a sphere light. Writes exposed radiance as earth.frag's encodeHdr, alpha 1 (coverage).

// The start of earth.frag's Scene block (same std140 offsets).
layout(set = 0, binding = 0, std140) uniform Scene {
    vec4 camPos;  // xyz camera position (ECEF km)
    vec4 right;   // xyz camera right (ECEF)
    vec4 up;
    vec4 back;
    vec4 sun;     // xyz Sun direction (ECEF)
    vec4 moon;
    vec4 iRight;  // xyz camera right in the Cupola frame
    vec4 iUp;     // xyz camera up in the Cupola frame, w = Earth brightness (mean Earth radiance)
    vec4 iBack;   // xyz camera back in the Cupola frame, w = linear exposure
    vec4 skyUp;
    vec4 skyAxis;
    vec4 clouds;
    vec4 starX;
    vec4 starY;
    vec4 starZ;
    vec4 sunLight;  // xyz sunlight at the camera per unit sunlight (0 behind the Earth)
} s;

layout(set = 0, binding = 12) uniform sampler2D albedoMap;  // station_albedo (sRGB)
layout(set = 0, binding = 13) uniform sampler2D ormMap;     // station_orm: occlusion, roughness, metalness

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in vec2 uv;

layout(location = 0) out vec4 outColor;

const float PI = 3.14159265358979;
const float A_KM = 6378.137;
const float EYE_Z = 1.4405 - 1.55;  // earth.frag EYE_Z

// Illuminance per unit radiance of a sphere of angular radius sigma at angle theta from the normal (Lagarde and
// de Rousiers 2014, Frostbite, horizon-clipped form).
float sphereIlluminance(float cosTheta, float sinSigma2) {
    if (cosTheta * cosTheta > sinSigma2) return PI * sinSigma2 * max(cosTheta, 0.0);
    float sinTheta = sqrt(max(1.0 - cosTheta * cosTheta, 1e-6));
    float x = sqrt(1.0 / sinSigma2 - 1.0);
    float y = -x * cosTheta / sinTheta;
    float sinThetaSqrtY = sinTheta * sqrt(max(1.0 - y * y, 0.0));
    return max((cosTheta * acos(clamp(y, -1.0, 1.0)) - x * sinThetaSqrtY) * sinSigma2 + atan(sinThetaSqrtY / x), 0.0);
}

void main() {
    vec3 albedo = texture(albedoMap, uv).rgb;
    vec3 orm = texture(ormMap, uv).rgb;
    float rough = max(orm.g, 0.04);
    float metal = orm.b;
    vec3 v = normalize(vec3(0.0, 0.0, EYE_Z) - position);
    vec3 n = normalize(normal);
    if (dot(n, v) < 0.0) n = -n;  // thin two-sided parts (wings, radiators)
    vec3 sunCam = vec3(dot(s.sun.xyz, s.right.xyz), dot(s.sun.xyz, s.up.xyz), dot(s.sun.xyz, s.back.xyz));
    vec3 l = normalize(s.iRight.xyz * sunCam.x + s.iUp.xyz * sunCam.y + s.iBack.xyz * sunCam.z);
    float nl = max(dot(n, l), 0.0);
    float nv = max(dot(n, v), 1e-4);
    vec3 h = normalize(l + v);
    float a2 = rough * rough * rough * rough;
    float nh = max(dot(n, h), 0.0);
    float dd = nh * nh * (a2 - 1.0) + 1.0;
    float d = a2 / (PI * dd * dd);
    float k = rough * rough * 0.5;
    float vis = 0.25 / ((nl * (1.0 - k) + k) * (nv * (1.0 - k) + k));
    vec3 f0 = mix(vec3(0.04), albedo, metal);
    vec3 f = f0 + (1.0 - f0) * pow(1.0 - max(dot(v, h), 0.0), 5.0);
    // Shader units: unit sunlight is an illuminance of pi, so a white Lambertian surface facing it has radiance 1.
    vec3 c = s.sunLight.xyz * nl * ((1.0 - metal) * albedo + PI * d * vis * f);
    float sinSigma = A_KM / length(s.camPos.xyz);
    c += albedo * (1.0 - metal) * orm.r * s.iUp.w * sphereIlluminance(n.z, sinSigma * sinSigma) / PI;
    c *= s.iBack.w;
    outColor = vec4(c / (1.0 + c), 1.0);
}
