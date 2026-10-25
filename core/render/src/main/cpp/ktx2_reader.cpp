#include "ktx2_reader.h"

#include <bit>
#include <cstring>
#include <numeric>

namespace cupola::ktx2 {
namespace {

constexpr uint8_t kIdentifier[12] = {0xAB, 0x4B, 0x54, 0x58, 0x20, 0x32, 0x30, 0xBB, 0x0D, 0x0A, 0x1A, 0x0A};

struct FormatSpec {
    Format format;
    uint32_t blockWidth;
    uint32_t blockHeight;
    uint32_t blockBytes;
};

constexpr FormatSpec kFormats[] = {
    {Format::kEtc2R8G8B8Unorm, 4, 4, 8},  {Format::kEtc2R8G8B8Srgb, 4, 4, 8}, {Format::kEtc2R8G8B8A8Unorm, 4, 4, 16},
    {Format::kEtc2R8G8B8A8Srgb, 4, 4, 16}, {Format::kAstc6x6Unorm, 6, 6, 16}, {Format::kAstc6x6Srgb, 6, 6, 16},
    {Format::kAstc8x8Unorm, 8, 8, 16},    {Format::kAstc8x8Srgb, 8, 8, 16},
    {Format::kAstc12x12Srgb, 12, 12, 16},
};

// KTX2 is little-endian; byte-wise reads are independent of host endianness and alignment.
uint32_t u32(const uint8_t* p) {
    return static_cast<uint32_t>(p[0]) | static_cast<uint32_t>(p[1]) << 8 | static_cast<uint32_t>(p[2]) << 16 |
           static_cast<uint32_t>(p[3]) << 24;
}

uint64_t u64(const uint8_t* p) { return static_cast<uint64_t>(u32(p)) | static_cast<uint64_t>(u32(p + 4)) << 32; }

// a * b, false on 64-bit overflow.
bool mul(uint64_t a, uint64_t b, uint64_t* out) {
    if (a != 0 && b > UINT64_MAX / a) return false;
    *out = a * b;
    return true;
}

// [offset, offset + length) lies inside [begin, fileSize), without overflow.
bool inside(uint64_t offset, uint64_t length, uint64_t begin, uint64_t fileSize) {
    return offset >= begin && length <= fileSize && offset <= fileSize - length;
}

struct Range {
    uint64_t offset;
    uint64_t length;
};

bool overlaps(Range a, Range b) {
    return a.length != 0 && b.length != 0 && a.offset < b.offset + b.length && b.offset < a.offset + a.length;
}

}  // namespace

const char* errorName(Error error) {
    switch (error) {
        case Error::kNone: return "none";
        case Error::kTruncated: return "truncated";
        case Error::kIdentifier: return "identifier";
        case Error::kUnsupportedFormat: return "unsupported_format";
        case Error::kTypeSize: return "type_size";
        case Error::kZeroSize: return "zero_size";
        case Error::kNot2D: return "not_2d";
        case Error::kFaceCount: return "face_count";
        case Error::kLevelCount: return "level_count";
        case Error::kSupercompression: return "supercompression";
        case Error::kIndexOutOfRange: return "index_out_of_range";
        case Error::kLevelSize: return "level_size";
        case Error::kLevelAlignment: return "level_alignment";
        case Error::kLevelOutOfRange: return "level_out_of_range";
        case Error::kLevelOverlap: return "level_overlap";
    }
    return "unknown";
}

size_t prefixBytes(const uint8_t* data, size_t size) {
    if (size < kHeaderBytes) return 0;
    uint32_t levels = u32(data + 40);
    if (levels == 0 || levels > kMaxLevels) return 0;
    return kHeaderBytes + levels * kLevelIndexEntryBytes;
}

Error parse(const uint8_t* data, size_t size, uint64_t fileSize, Info* out) {
    *out = Info{};
    if (size > fileSize) size = static_cast<size_t>(fileSize);
    if (size < kHeaderBytes) return Error::kTruncated;
    if (std::memcmp(data, kIdentifier, sizeof(kIdentifier)) != 0) return Error::kIdentifier;

    const uint32_t vkFormat = u32(data + 12);
    const FormatSpec* spec = nullptr;
    for (const FormatSpec& f : kFormats) {
        if (static_cast<uint32_t>(f.format) == vkFormat) spec = &f;
    }
    if (spec == nullptr) return Error::kUnsupportedFormat;

    const uint32_t width = u32(data + 20);
    const uint32_t height = u32(data + 24);
    const uint32_t layerCount = u32(data + 32);
    const uint32_t levels = u32(data + 40);
    if (u32(data + 16) != 1) return Error::kTypeSize;
    if (width == 0 || height == 0) return Error::kZeroSize;
    if (u32(data + 28) != 0) return Error::kNot2D;
    if (u32(data + 36) != 1) return Error::kFaceCount;
    if (u32(data + 44) != 0) return Error::kSupercompression;
    // Full chain to 1 x 1: floor(log2(max(w, h))) + 1 levels.
    if (levels == 0 || levels > static_cast<uint32_t>(std::bit_width(width > height ? width : height))) {
        return Error::kLevelCount;
    }
    const uint64_t indexEnd = kHeaderBytes + uint64_t{levels} * kLevelIndexEntryBytes;
    if (size < indexEnd) return Error::kTruncated;

    // DFD, KVD and SGD: not interpreted, only kept after the level index and inside the file.
    Range ranges[3 + kMaxLevels + 1] = {{0, indexEnd}};
    uint32_t rangeCount = 1;
    const Range index[] = {
        {u32(data + 48), u32(data + 52)}, {u32(data + 56), u32(data + 60)}, {u64(data + 64), u64(data + 72)}};
    for (Range r : index) {
        if (r.length == 0) continue;
        if (!inside(r.offset, r.length, indexEnd, fileSize)) return Error::kIndexOutOfRange;
        ranges[rangeCount++] = r;
    }

    const uint64_t alignment = std::lcm(uint64_t{spec->blockBytes}, uint64_t{4});
    const uint64_t layers = layerCount == 0 ? 1 : layerCount;
    for (uint32_t n = 0; n < levels; ++n) {
        const uint8_t* entry = data + kHeaderBytes + n * kLevelIndexEntryBytes;
        const Range level{u64(entry), u64(entry + 8)};
        const uint64_t w = (width >> n) == 0 ? 1 : width >> n;
        const uint64_t h = (height >> n) == 0 ? 1 : height >> n;
        const uint64_t blocksW = (w + spec->blockWidth - 1) / spec->blockWidth;
        const uint64_t blocksH = (h + spec->blockHeight - 1) / spec->blockHeight;
        uint64_t expected = 0;
        if (!mul(blocksW, blocksH, &expected) || !mul(expected, layers, &expected) ||
            !mul(expected, spec->blockBytes, &expected) || level.length != expected || u64(entry + 16) != expected) {
            return Error::kLevelSize;
        }
        if (level.offset % alignment != 0) return Error::kLevelAlignment;
        if (!inside(level.offset, level.length, 0, fileSize)) return Error::kLevelOutOfRange;
        for (uint32_t i = 0; i < rangeCount; ++i) {
            if (overlaps(level, ranges[i])) return Error::kLevelOverlap;
        }
        ranges[rangeCount++] = level;
    }

    Info info;
    info.format = spec->format;
    info.blockWidth = spec->blockWidth;
    info.blockHeight = spec->blockHeight;
    info.blockBytes = spec->blockBytes;
    info.width = width;
    info.height = height;
    info.layers = layerCount == 0 ? 1 : layerCount;
    info.array = layerCount != 0;
    info.levels = levels;
    for (uint32_t n = 0; n < levels; ++n) {
        const uint8_t* entry = data + kHeaderBytes + n * kLevelIndexEntryBytes;
        info.level[n] = Level{u64(entry), u64(entry + 8)};
    }
    *out = info;
    return Error::kNone;
}

}  // namespace cupola::ktx2
