#include "station_mesh.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <utility>

namespace cupola::station {
namespace {

constexpr uint32_t kVersion = 1;
constexpr size_t kVertexBytes = 22;
constexpr size_t kNodeFixedBytes = 33;  // 3 f32 pivot, 3 f32 axis, u8 kind, u32 vertex count, u32 index count

// The file and every supported target are little-endian.
template <typename T>
T load(const uint8_t* p) {
    T v;
    std::memcpy(&v, p, sizeof(v));
    return v;
}

// Strict UTF-8 as Python's bytes.decode: no overlong forms, surrogates or code points above U+10FFFF.
bool validUtf8(const uint8_t* s, size_t n) {
    for (size_t i = 0; i < n;) {
        const uint8_t c = s[i];
        size_t len;
        uint32_t cp, min;
        if (c < 0x80) {
            ++i;
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            len = 2, cp = c & 0x1Fu, min = 0x80;
        } else if ((c & 0xF0) == 0xE0) {
            len = 3, cp = c & 0x0Fu, min = 0x800;
        } else if ((c & 0xF8) == 0xF0) {
            len = 4, cp = c & 0x07u, min = 0x10000;
        } else {
            return false;
        }
        if (i + len > n) return false;
        for (size_t k = 1; k < len; ++k) {
            if ((s[i + k] & 0xC0) != 0x80) return false;
            cp = cp << 6 | (s[i + k] & 0x3Fu);
        }
        if (cp < min || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) return false;
        i += len;
    }
    return true;
}

bool fail(std::string* error, std::string message) {
    *error = std::move(message);
    return false;
}

using Vec = std::array<double, 3>;

double dot(const Vec& a, const Vec& b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
Vec cross(const Vec& a, const Vec& b) {
    return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
}
Vec scaled(const Vec& a, double k) { return {a[0] * k, a[1] * k, a[2] * k}; }
Vec sub(const Vec& a, const Vec& b) { return {a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
Vec vec(const float* v) { return {v[0], v[1], v[2]}; }

struct Mat {
    double m[3][3];
    Vec operator*(const Vec& v) const {
        return {m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2], m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
    }
    Mat operator*(const Mat& o) const {
        Mat r{};
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) r.m[i][j] = m[i][0] * o.m[0][j] + m[i][1] * o.m[1][j] + m[i][2] * o.m[2][j];
        }
        return r;
    }
    Mat transposed() const {
        Mat r{};
        for (int i = 0; i < 3; ++i) {
            for (int j = 0; j < 3; ++j) r.m[i][j] = m[j][i];
        }
        return r;
    }
};

constexpr Mat kIdentity{{{1, 0, 0}, {0, 1, 0}, {0, 0, 1}}};

// Right-handed rotation by `angle` about the unit axis k (Rodrigues).
Mat rotation(const Vec& k, double angle) {
    const double c = std::cos(angle), s = std::sin(angle), t = 1.0 - c;
    return {{{c + t * k[0] * k[0], t * k[0] * k[1] - s * k[2], t * k[0] * k[2] + s * k[1]},
             {t * k[1] * k[0] + s * k[2], c + t * k[1] * k[1], t * k[1] * k[2] - s * k[0]},
             {t * k[2] * k[0] - s * k[1], t * k[2] * k[1] + s * k[0], c + t * k[2] * k[2]}}};
}

// SARJ angle about the truss t that brings the wing axis a to the cone the BGA can reach for the Sun s:
// (R(t, alpha) a) . s = a . cell. Always the atan2 + acos root: continuous in s away from the truss axis.
double sarjAlpha(const Vec& t, const Vec& a, const Vec& cell, const Vec& s) {
    const Vec perp = sub(a, scaled(t, dot(a, t)));
    const double b = dot(perp, s);
    const double c = dot(cross(t, perp), s);
    const double d = dot(a, cell) - dot(a, t) * dot(t, s);
    const double r = std::hypot(b, c);
    if (r < 1e-9) return 0.0;  // Sun along the truss: every alpha is equivalent
    return std::atan2(c, b) + std::acos(std::clamp(d / r, -1.0, 1.0));
}

// BGA angle about the wing axis a that turns the rest cell normal toward s (both projected across a).
double bgaBeta(const Vec& a, const Vec& cell, const Vec& s) {
    const Vec u = sub(cell, scaled(a, dot(cell, a)));
    const Vec v = sub(s, scaled(a, dot(s, a)));
    return std::atan2(dot(a, cross(u, v)), dot(u, v));
}

std::string side(const std::string& name) { return name.substr(0, name.find('_')); }

Pose toPose(const Mat& r, const Vec& t) {
    Pose p{};
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) p.m[i][j] = static_cast<float>(r.m[i][j]);
        p.m[i][3] = static_cast<float>(t[i]);
    }
    return p;
}

}  // namespace

bool parse(const uint8_t* data, size_t size, std::vector<Node>* out, std::string* error) {
    out->clear();
    if (size < 4 || std::memcmp(data, "CPST", 4) != 0) return fail(error, "bad magic");
    if (size < 12) return fail(error, "truncated header");
    const uint32_t version = load<uint32_t>(data + 4);
    if (version != kVersion) return fail(error, "unsupported version " + std::to_string(version));
    const uint32_t count = load<uint32_t>(data + 8);
    size_t off = 12;
    for (uint32_t k = 0; k < count; ++k) {
        if (off + 2 > size) return fail(error, "truncated node header");
        const size_t n = load<uint16_t>(data + off);
        if (off + 2 + n + kNodeFixedBytes > size) return fail(error, "truncated node header");
        if (!validUtf8(data + off + 2, n)) return fail(error, "node name not UTF-8");
        Node node;
        node.name.assign(reinterpret_cast<const char*>(data + off + 2), n);
        off += 2 + n;
        for (int i = 0; i < 3; ++i) {
            node.pivot[i] = load<float>(data + off + 4 * i);
            node.axis[i] = load<float>(data + off + 12 + 4 * i);
        }
        node.kind = data[off + 24];
        const size_t nv = load<uint32_t>(data + off + 25);
        const size_t ni = load<uint32_t>(data + off + 29);
        off += kNodeFixedBytes;
        if (node.kind > kGamma) return fail(error, node.name + ": bad kind " + std::to_string(node.kind));
        if (ni % 3 != 0) return fail(error, node.name + ": index count " + std::to_string(ni) + " not a multiple of 3");
        const size_t indexBytes = nv > 65535 ? 4 : 2;
        if (nv * kVertexBytes + ni * indexBytes > size - off) {
            return fail(error, node.name + ": truncated vertex or index data");
        }
        node.vertices.resize(nv);
        for (size_t v = 0; v < nv; ++v, off += kVertexBytes) {
            Vertex& x = node.vertices[v];
            for (int i = 0; i < 3; ++i) {
                x.position[i] = load<float>(data + off + 4 * i);
                x.normal[i] = load<int16_t>(data + off + 12 + 2 * i);
            }
            x.uv[0] = load<uint16_t>(data + off + 18);
            x.uv[1] = load<uint16_t>(data + off + 20);
        }
        node.indices.resize(ni);
        uint32_t maxIndex = 0;
        for (size_t i = 0; i < ni; ++i, off += indexBytes) {
            node.indices[i] = indexBytes == 4 ? load<uint32_t>(data + off) : load<uint16_t>(data + off);
            maxIndex = std::max(maxIndex, node.indices[i]);
        }
        if (ni > 0 && maxIndex >= nv) {
            return fail(error, node.name + ": index " + std::to_string(maxIndex) + " out of range (" +
                                   std::to_string(nv) + " vertices)");
        }
        const double axisLength = std::sqrt(dot(vec(node.axis), vec(node.axis)));
        if (std::fabs(axisLength - 1.0) > 1e-3) return fail(error, node.name + ": axis length " + std::to_string(axisLength));
        for (const Vertex& x : node.vertices) {
            if (!std::isfinite(x.position[0]) || !std::isfinite(x.position[1]) || !std::isfinite(x.position[2])) {
                return fail(error, node.name + ": non-finite position");
            }
            const Vec nrm{static_cast<double>(x.normal[0]), static_cast<double>(x.normal[1]),
                          static_cast<double>(x.normal[2])};
            if (std::fabs(std::sqrt(dot(nrm, nrm)) / 32767.0 - 1.0) > 0.01) {
                return fail(error, node.name + ": normals not unit length");
            }
        }
        out->push_back(std::move(node));
    }
    if (off != size) return fail(error, std::to_string(size - off) + " trailing bytes");
    return true;
}

void cellNormal(const float truss[3], const float axis[3], float out[3]) {
    const Vec a = vec(axis);
    const Vec w = cross(a, vec(truss));
    const double len = std::sqrt(dot(w, w));
    const Vec t = len < 1e-9 ? Vec{0, 0, 0} : cross(scaled(w, 1.0 / len), a);
    for (int i = 0; i < 3; ++i) out[i] = static_cast<float>(-t[i]);
}

std::vector<Pose> poses(const std::vector<Node>& nodes, const float sun[3]) {
    std::vector<Pose> out(nodes.size(), toPose(kIdentity, {0, 0, 0}));
    const double len = std::sqrt(dot(vec(sun), vec(sun)));
    if (!(len > 1e-9)) return out;
    const Vec s = scaled(vec(sun), 1.0 / len);
    // SARJ angle per kAlpha node, set by the first wing of its side (all wings of a side share it).
    std::vector<double> alpha(nodes.size(), 0.0);
    for (size_t i = 0; i < nodes.size(); ++i) {
        if (nodes[i].kind != kAlpha) continue;
        for (const Node& wing : nodes) {
            if (wing.kind != kBeta || side(wing.name) != side(nodes[i].name)) continue;
            float cell[3];
            cellNormal(nodes[i].axis, wing.axis, cell);
            alpha[i] = sarjAlpha(vec(nodes[i].axis), vec(wing.axis), vec(cell), s);
            break;
        }
    }
    auto sarjOf = [&](const Node& node) -> const Node* {
        for (const Node& n : nodes) {
            if (n.kind == kAlpha && side(n.name) == side(node.name)) return &n;
        }
        return nullptr;
    };
    for (size_t i = 0; i < nodes.size(); ++i) {
        const Node& node = nodes[i];
        const Node* sarj = node.kind == kAlpha ? &node : node.kind == kBeta ? sarjOf(node) : nullptr;
        // A wing without a SARJ keeps the rest pose: its cell normal comes from the truss axis.
        if (sarj == nullptr) continue;
        const Vec pa = vec(sarj->pivot);
        const Mat ra = rotation(vec(sarj->axis), alpha[static_cast<size_t>(sarj - nodes.data())]);
        if (node.kind == kAlpha) {
            out[i] = toPose(ra, sub(pa, ra * pa));
            continue;
        }
        float cell[3];
        cellNormal(sarj->axis, node.axis, cell);
        const Vec a = vec(node.axis);
        const Mat rb = rotation(a, bgaBeta(a, vec(cell), ra.transposed() * s));
        const Vec pb = vec(node.pivot);
        // world = Ra (Rb (p - pb) + pb - pa) + pa
        const Vec t = ra * sub(sub(pb, rb * pb), pa);
        out[i] = toPose(ra * rb, {t[0] + pa[0], t[1] + pa[1], t[2] + pa[2]});
    }
    return out;
}

}  // namespace cupola::station
