#version 450

// Must match cupola::PushConstants in device.h.
layout(push_constant) uniform Push {
    ivec2 extent;
    int rotation;
    int loadIterations;
    float load;
    float time;
} pc;

layout(location = 0) out vec4 outColor;

// Maps a swapchain pixel back to the logical (unrotated) pixel; extent is the swapchain image size.
ivec2 logicalPixel(ivec2 p) {
    int w = pc.extent.x;
    int h = pc.extent.y;
    if (pc.rotation == 1) return ivec2(p.y, w - 1 - p.x);
    if (pc.rotation == 2) return ivec2(w - 1 - p.x, h - 1 - p.y);
    if (pc.rotation == 3) return ivec2(h - 1 - p.y, p.x);
    return p;
}

void main() {
    ivec2 p = logicalPixel(ivec2(gl_FragCoord.xy));
    if (pc.load != 0.0) {
        vec2 v = vec2(p) * 0.001 + pc.time;
        for (int i = 0; i < pc.loadIterations; ++i) {
            v = vec2(sin(v.x * 1.7 + v.y), cos(v.y * 1.3 - v.x));
        }
        outColor = vec4(fract(v), 0.5, 1.0);
        return;
    }
    uint x = uint(p.x);
    uint y = uint(p.y);
    // Integer values divided by 255 convert exactly to UNORM8.
    outColor = vec4(float((x * 4u) & 255u), float((y * 4u) & 255u), float(((x / 8u + y / 8u) & 1u) * 255u), 255.0) / 255.0;
}
