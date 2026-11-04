// Host tests of the station.mesh reader and the solar array sun tracking (station_mesh.h). Usage:
// station_mesh_test [case]; no argument runs every case. Exit 0 = pass, 1 = fail.
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <iterator>
#include <random>
#include <string>
#include <vector>

#include "station_mesh.h"

namespace {

namespace st = cupola::station;

int gFailures = 0;

#define CHECK(cond, ...)                                                          \
    do {                                                                          \
        if (!(cond)) {                                                            \
            std::printf("%s:%d: CHECK failed: %s: ", __FILE__, __LINE__, #cond);  \
            std::printf(__VA_ARGS__);                                             \
            std::printf("\n");                                                    \
            ++gFailures;                                                          \
        }                                                                         \
    } while (0)

std::vector<uint8_t> readFile(const char* path) {
    std::ifstream f(path, std::ios::binary);
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(f), std::istreambuf_iterator<char>());
}

std::vector<uint8_t> shipped() { return readFile(CUPOLA_STATION_MESH); }

void put32(std::vector<uint8_t>& b, uint32_t v) {
    for (int i = 0; i < 4; ++i) b.push_back(static_cast<uint8_t>(v >> (8 * i)));
}
void put16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v));
    b.push_back(static_cast<uint8_t>(v >> 8));
}
void putF(std::vector<uint8_t>& b, float f) {
    uint32_t v;
    std::memcpy(&v, &f, 4);
    put32(b, v);
}

// One node "N" of kind `kind` with a triangle (indices `i0..i2`) of 3 vertices with +Z normals.
std::vector<uint8_t> tiny(uint8_t kind, uint16_t i2, float axisZ = 1.0f, float x0 = 0.0f) {
    std::vector<uint8_t> b = {'C', 'P', 'S', 'T'};
    put32(b, 1);
    put32(b, 1);
    put16(b, 1);
    b.push_back('N');
    for (float v : {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, axisZ}) putF(b, v);
    b.push_back(kind);
    put32(b, 3);
    put32(b, 3);
    for (int v = 0; v < 3; ++v) {
        putF(b, v == 0 ? x0 : static_cast<float>(v));
        putF(b, 0.0f);
        putF(b, 0.0f);
        put16(b, 0);
        put16(b, 0);
        put16(b, 32767);
        put16(b, 0);
        put16(b, 65535);
    }
    put16(b, 0);
    put16(b, 1);
    put16(b, i2);
    return b;
}

bool rejects(const std::vector<uint8_t>& data, const char* label) {
    std::vector<st::Node> nodes;
    std::string error;
    const bool ok = st::parse(data.data(), data.size(), &nodes, &error);
    if (!ok) std::printf("RED  %-10s rejected: %s\n", label, error.c_str());
    return !ok;
}

// The corruption cases of station_mesh.py --self-test, then the file itself must pass.
void selfTestCases() {
    const std::vector<uint8_t> good = shipped();
    CHECK(good.size() == 158151, "shipped station.mesh is %zu bytes", good.size());
    std::vector<uint8_t> magic = good;
    std::memcpy(magic.data(), "XXXX", 4);
    CHECK(rejects(magic, "magic"), "bad magic accepted");
    std::vector<uint8_t> version = good;
    version[4] = 2;
    CHECK(rejects(version, "version"), "version 2 accepted");
    CHECK(rejects(std::vector<uint8_t>(good.begin(), good.end() - 7), "truncated"), "truncated file accepted");
    std::vector<uint8_t> trailing = good;
    trailing.push_back(0);
    CHECK(rejects(trailing, "trailing"), "trailing byte accepted");
    CHECK(rejects(std::vector<uint8_t>(good.begin(), good.begin() + 3), "short"), "3-byte file accepted");
    CHECK(rejects(std::vector<uint8_t>(good.begin(), good.begin() + 10), "header"), "10-byte file accepted");
}

void validationCases() {
    std::vector<st::Node> nodes;
    std::string error;
    const std::vector<uint8_t> ok = tiny(st::kStatic, 2);
    CHECK(st::parse(ok.data(), ok.size(), &nodes, &error), "tiny mesh rejected: %s", error.c_str());
    CHECK(nodes.size() == 1 && nodes[0].indices.size() == 3 && nodes[0].vertices[2].position[0] == 2.0f,
          "tiny mesh content");
    CHECK(rejects(tiny(4, 2), "kind"), "kind 4 accepted");
    CHECK(rejects(tiny(st::kStatic, 3), "index"), "index 3 of 3 vertices accepted");
    CHECK(rejects(tiny(st::kStatic, 2, 0.9f), "axis"), "axis length 0.9 accepted");
    CHECK(rejects(tiny(st::kStatic, 2, 1.0f, NAN), "nan"), "NaN position accepted");
    std::vector<uint8_t> normal = tiny(st::kStatic, 2);
    normal[normal.size() - 6 - 22 + 12 + 4] = 0;  // last vertex: normal z 32767 -> 32512
    normal[normal.size() - 6 - 22 + 12 + 5] = 0x40;
    CHECK(rejects(normal, "normal"), "normal of length 0.5 accepted");
    std::vector<uint8_t> count = tiny(st::kStatic, 2);
    count[12 + 2 + 1 + 24 + 1 + 4] = 2;  // index count 2
    CHECK(rejects(count, "count"), "index count 2 accepted");
    std::vector<uint8_t> utf = tiny(st::kStatic, 2);
    utf[14] = 0xFF;
    CHECK(rejects(utf, "utf8"), "invalid UTF-8 name accepted");
}

void shippedMeshPasses() {
    const std::vector<uint8_t> good = shipped();
    std::vector<st::Node> nodes;
    std::string error;
    CHECK(st::parse(good.data(), good.size(), &nodes, &error), "shipped mesh rejected: %s", error.c_str());
    size_t vertices = 0, triangles = 0;
    for (const st::Node& n : nodes) {
        vertices += n.vertices.size();
        triangles += n.indices.size() / 3;
    }
    std::printf("GREEN %s: nodes %zu vertices %zu triangles %zu\n", CUPOLA_STATION_MESH, nodes.size(), vertices,
                triangles);
    CHECK(nodes.size() == 3 && vertices == 6320 && triangles == 3160, "summary");
    CHECK(nodes.size() == 3 && nodes[0].name == "PORT_ALPHA" && nodes[0].kind == st::kAlpha &&
              nodes[1].name == "PORT_BETA_2A" && nodes[1].kind == st::kBeta && nodes[2].kind == st::kBeta,
          "node names and kinds");
}

double dot(const float* a, const double* b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

// Cell normal and pivot of every wing after the poses for `sun`: the normal faces the Sun, the SARJ pivot stays.
double worstAlignment(const std::vector<st::Node>& nodes, const double sun[3], bool* finite) {
    const float s[3] = {static_cast<float>(sun[0]), static_cast<float>(sun[1]), static_cast<float>(sun[2])};
    const std::vector<st::Pose> poses = st::poses(nodes, s);
    double worst = 2.0;
    *finite = poses.size() == nodes.size();
    for (size_t i = 0; i < nodes.size() && *finite; ++i) {
        for (const auto& row : poses[i].m) {
            for (float v : row) *finite = *finite && std::isfinite(v);
        }
        if (nodes[i].kind != st::kBeta) continue;
        float cell[3];
        st::cellNormal(nodes[0].axis, nodes[i].axis, cell);
        const double c[3] = {cell[0], cell[1], cell[2]};
        double n[3];
        for (int r = 0; r < 3; ++r) n[r] = dot(poses[i].m[r], c);
        worst = std::fmin(worst, n[0] * sun[0] + n[1] * sun[1] + n[2] * sun[2]);
    }
    return worst;
}

void cellsFaceTheSun() {
    const std::vector<uint8_t> good = shipped();
    std::vector<st::Node> nodes;
    std::string error;
    CHECK(st::parse(good.data(), good.size(), &nodes, &error), "shipped mesh rejected: %s", error.c_str());
    if (nodes.size() != 3) return;
    std::mt19937 rng(7);
    std::normal_distribution<double> g;
    const float* truss = nodes[0].axis;
    const float* wing = nodes[1].axis;
    int tested = 0;
    double worst = 2.0;
    while (tested < 2000) {
        double s[3] = {g(rng), g(rng), g(rng)};
        const double len = std::sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2]);
        for (double& v : s) v /= len;
        if (std::fabs(dot(truss, s)) > 0.99 || std::fabs(dot(wing, s)) > 0.99) continue;
        bool finite;
        worst = std::fmin(worst, worstAlignment(nodes, s, &finite));
        CHECK(finite, "non-finite pose");
        ++tested;
    }
    std::printf("worst cell . sun over %d Sun directions: %.6f\n", tested, worst);
    CHECK(worst >= 0.999, "worst cell . sun %.6f", worst);

    // The SARJ pose fixes its pivot; a wing's pose turns its own BGA pivot only about the truss through the SARJ
    // pivot (same distance from it, same position along the truss).
    const float sun[3] = {0.3f, 0.5f, -0.812404f};
    const std::vector<st::Pose> poses = st::poses(nodes, sun);
    const double pa[3] = {nodes[0].pivot[0], nodes[0].pivot[1], nodes[0].pivot[2]};
    for (size_t i = 0; i < nodes.size(); ++i) {
        const double pivot[3] = {nodes[i].pivot[0], nodes[i].pivot[1], nodes[i].pivot[2]};
        double p[3], before[3], after[3];
        for (int r = 0; r < 3; ++r) {
            p[r] = dot(poses[i].m[r], pivot) + poses[i].m[r][3];
            before[r] = pivot[r] - pa[r];
            after[r] = p[r] - pa[r];
        }
        const double radial = std::hypot(after[0], after[1], after[2]) - std::hypot(before[0], before[1], before[2]);
        const double axial = dot(nodes[0].axis, after) - dot(nodes[0].axis, before);
        CHECK(std::fabs(radial) < 1e-3 && std::fabs(axial) < 1e-3, "node %zu pivot moves by %.6f m radially, %.6f m "
              "along the truss", i, radial, axial);
        if (i == 0) CHECK(std::hypot(after[0], after[1], after[2]) < 1e-3, "the SARJ pose moves its pivot");
    }
}

void degenerateSunsStayFinite() {
    const std::vector<uint8_t> good = shipped();
    std::vector<st::Node> nodes;
    std::string error;
    CHECK(st::parse(good.data(), good.size(), &nodes, &error), "shipped mesh rejected: %s", error.c_str());
    if (nodes.size() != 3) return;
    float cell[3];
    st::cellNormal(nodes[0].axis, nodes[1].axis, cell);
    const float* axes[] = {nodes[0].axis, nodes[1].axis, cell};
    for (const float* a : axes) {
        for (double sign : {1.0, -1.0}) {
            const double s[3] = {sign * a[0], sign * a[1], sign * a[2]};
            bool finite;
            const double worst = worstAlignment(nodes, s, &finite);
            CHECK(finite, "non-finite pose for Sun along (%g, %g, %g)", s[0], s[1], s[2]);
            std::printf("Sun along (%.3f, %.3f, %.3f): worst cell . sun %.6f\n", s[0], s[1], s[2], worst);
        }
    }
    const float zero[3] = {0.0f, 0.0f, 0.0f};
    for (const st::Pose& p : st::poses(nodes, zero)) {
        for (const auto& row : p.m) {
            for (float v : row) CHECK(std::isfinite(v), "non-finite pose for a zero Sun vector");
        }
    }
}

}  // namespace

int main(int argc, char** argv) {
    const std::pair<const char*, std::function<void()>> cases[] = {
        {"self_test", selfTestCases},
        {"validation", validationCases},
        {"shipped", shippedMeshPasses},
        {"track", cellsFaceTheSun},
        {"degenerate", degenerateSunsStayFinite},
    };
    bool ran = false;
    for (const auto& [name, run] : cases) {
        if (argc > 1 && std::strcmp(argv[1], name) != 0) continue;
        ran = true;
        run();
    }
    if (!ran) {
        std::printf("unknown case %s\n", argv[1]);
        return 1;
    }
    std::printf(gFailures == 0 ? "PASS\n" : "FAIL (%d)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
