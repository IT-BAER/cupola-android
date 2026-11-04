#version 450

// P3.6 station exterior (station.mesh, Cupola frame in m): one draw per node, posed on the CPU (StationPass).
// Must match cupola::StationPush in station_pass.h.
layout(push_constant) uniform Push {
    mat4 clip;     // rest-pose position to clip space (pose, camera, reverse-Z projection, pre-rotation)
    vec4 pose[3];  // rest pose to Cupola frame, rows (rotation | translation)
} pc;

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;  // snorm
layout(location = 2) in vec2 uv;

layout(location = 0) out vec3 outPosition;  // Cupola frame, m
layout(location = 1) out vec3 outNormal;
layout(location = 2) out vec2 outUv;

void main() {
    vec4 p = vec4(position, 1.0);
    outPosition = vec3(dot(pc.pose[0], p), dot(pc.pose[1], p), dot(pc.pose[2], p));
    outNormal = vec3(dot(pc.pose[0].xyz, normal), dot(pc.pose[1].xyz, normal), dot(pc.pose[2].xyz, normal));
    outUv = uv;
    gl_Position = pc.clip * p;
}
