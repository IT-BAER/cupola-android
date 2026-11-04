#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// station.mesh (CPST v1, tools/blender/README.md "Station exterior (P4.6)") and the Sun tracking of its joints.
// No Android or Vulkan: host-tested (src/test/cpp/station_mesh_test.cpp).
namespace cupola::station {

enum Kind : uint8_t { kStatic = 0, kAlpha = 1, kBeta = 2, kGamma = 3 };

struct Vertex {
    float position[3];  // m, Cupola frame (+Z nadir), rest pose
    int16_t normal[3];  // snorm
    uint16_t uv[2];     // unorm, top-left origin
};

struct Node {
    std::string name;
    float pivot[3];
    float axis[3];  // unit
    uint8_t kind;
    std::vector<Vertex> vertices;
    std::vector<uint32_t> indices;  // counter-clockwise triangles
};

// Parses and validates `data` with the checks of tools/blender/station_mesh.py; false and `*error` on any defect.
bool parse(const uint8_t* data, size_t size, std::vector<Node>* out, std::string* error);

// Rest pose to Cupola frame: row-major rotation | translation.
struct Pose {
    float m[3][4];
};

// Rest-pose normal of a wing's cell side: model_station.py puts the cells on -t, t = normalize(axis x truss) x axis.
void cellNormal(const float truss[3], const float axis[3], float out[3]);

// One pose per node that turns every wing's cell side to the unit `sun` (Cupola frame): per side (PORT_ / STBD_
// prefix) one SARJ alpha about its kAlpha node, then per wing a BGA beta about its own axis, beta applied first.
// Angles are right-handed about the node axes. kStatic and kGamma nodes keep the rest pose; a zero `sun` gives rest.
std::vector<Pose> poses(const std::vector<Node>& nodes, const float sun[3]);

}  // namespace cupola::station
