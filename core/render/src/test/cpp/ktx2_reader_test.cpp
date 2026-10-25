// Host tests of the KTX2 reader. Usage: ktx2_reader_test [case]; no argument runs every case.
// Exit 0 = pass, 1 = fail, 77 = skipped (CTest SKIP_RETURN_CODE).
#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <functional>
#include <string>
#include <vector>

#include "ktx2_reader.h"

namespace {

using namespace cupola::ktx2;

constexpr int kSkip = 77;
int gFailures = 0;

#define CHECK(cond)                                                              \
    do {                                                                         \
        if (!(cond)) {                                                           \
            std::printf("%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, #cond); \
            ++gFailures;                                                         \
        }                                                                        \
    } while (0)

#define CHECK_EQ(a, b)                                                                                     \
    do {                                                                                                   \
        long long va_ = static_cast<long long>(a), vb_ = static_cast<long long>(b);                        \
        if (va_ != vb_) {                                                                                  \
            std::printf("%s:%d: CHECK_EQ failed: %s == %s (%lld vs %lld)\n", __FILE__, __LINE__, #a, #b, va_, vb_); \
            ++gFailures;                                                                                   \
        }                                                                                                  \
    } while (0)

std::vector<uint8_t> readFile(const std::filesystem::path& path) {
    std::ifstream in(path, std::ios::binary);
    std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
    if (bytes.empty()) std::printf("cannot read %s\n", path.string().c_str());
    return bytes;
}

std::vector<uint8_t> fixture(const char* name) {
    return readFile(std::filesystem::path(CUPOLA_FIXTURE_DIR) / name);
}

Error parseAll(const std::vector<uint8_t>& bytes, Info* info) {
    return parse(bytes.data(), bytes.size(), bytes.size(), info);
}

uint64_t blocks(uint32_t size, uint32_t block) { return (size + block - 1) / block; }

// The fixtures of tools/textures/make_fixtures.ps1: 16 x 12, 5 levels, synthetic block data.
void checkFixture(const char* name, Format format, uint32_t blockW, uint32_t blockH, uint32_t blockBytes,
                  uint32_t layers, bool array, bool astc) {
    std::vector<uint8_t> bytes = fixture(name);
    CHECK_EQ(prefixBytes(bytes.data(), bytes.size()), kHeaderBytes + 5 * kLevelIndexEntryBytes);
    Info info;
    Error error = parseAll(bytes, &info);
    CHECK_EQ(error, Error::kNone);
    if (error != Error::kNone) {
        std::printf("%s: %s\n", name, errorName(error));
        return;
    }
    CHECK_EQ(info.format, format);
    CHECK_EQ(info.blockWidth, blockW);
    CHECK_EQ(info.blockHeight, blockH);
    CHECK_EQ(info.blockBytes, blockBytes);
    CHECK_EQ(info.width, 16);
    CHECK_EQ(info.height, 12);
    CHECK_EQ(info.layers, layers);
    CHECK(info.array == array);
    CHECK_EQ(info.levels, 5);
    for (uint32_t n = 0; n < info.levels; ++n) {
        uint32_t w = std::max(1u, 16u >> n), h = std::max(1u, 12u >> n);
        CHECK_EQ(info.level[n].length, blocks(w, blockW) * blocks(h, blockH) * layers * blockBytes);
        const uint8_t* first = bytes.data() + info.level[n].offset;
        if (astc) {
            // Void-extent block; red channel (bytes 8..9) encodes the level.
            CHECK_EQ(first[0], 0xFC);
            CHECK_EQ(first[8] | (first[9] << 8), (n * 0x3000) & 0xFFFF);
        } else {
            CHECK_EQ(first[0], (n * 31) & 0xFF);
        }
    }
}

// --- helpers for in-memory negative cases ---

uint32_t get32(const std::vector<uint8_t>& b, size_t at) {
    uint32_t v;
    std::memcpy(&v, b.data() + at, 4);
    return v;
}
void put32(std::vector<uint8_t>& b, size_t at, uint32_t v) { std::memcpy(b.data() + at, &v, 4); }
uint64_t get64(const std::vector<uint8_t>& b, size_t at) {
    uint64_t v;
    std::memcpy(&v, b.data() + at, 8);
    return v;
}
void put64(std::vector<uint8_t>& b, size_t at, uint64_t v) { std::memcpy(b.data() + at, &v, 8); }
size_t levelEntry(uint32_t n) { return kHeaderBytes + n * kLevelIndexEntryBytes; }

void expectError(const char* what, const std::vector<uint8_t>& bytes, Error expected) {
    Info info;
    Error error = parseAll(bytes, &info);
    if (error != expected) {
        std::printf("%s: expected %s, got %s\n", what, errorName(expected), errorName(error));
        ++gFailures;
    }
}

// Base of the negative cases: 16 x 12 ETC2 RGB, 8-byte blocks, 5 levels, level 0 stored last.
std::vector<uint8_t> base() { return fixture("etc2_rgb_srgb_2d.ktx2"); }

int negativeTruncated() {
    const std::vector<uint8_t> valid = base();
    const size_t indexEnd = kHeaderBytes + 5 * kLevelIndexEntryBytes;
    const uint32_t dfdOffset = get32(valid, 48);
    struct Cut {
        size_t size;
        Error expected;
    } cuts[] = {
        {0, Error::kTruncated},
        {11, Error::kTruncated},
        {kHeaderBytes - 1, Error::kTruncated},
        {kHeaderBytes, Error::kTruncated},
        {indexEnd - 1, Error::kTruncated},
        {dfdOffset + 4, Error::kIndexOutOfRange},
        {static_cast<size_t>(get64(valid, levelEntry(0))) + 8, Error::kLevelOutOfRange},
        {valid.size() - 1, Error::kLevelOutOfRange},
    };
    for (const Cut& cut : cuts) {
        std::vector<uint8_t> bytes(valid.begin(), valid.begin() + static_cast<std::ptrdiff_t>(cut.size));
        char what[64];
        std::snprintf(what, sizeof(what), "truncated to %zu", cut.size);
        expectError(what, bytes, cut.expected);
    }
    CHECK_EQ(prefixBytes(valid.data(), kHeaderBytes - 1), 0);
    expectError("corrupt_truncated.ktx2", fixture("corrupt_truncated.ktx2"), Error::kLevelOutOfRange);
    // Prefix-only parse (as the loader does) sees a level past the real file size.
    Info info;
    CHECK_EQ(parse(valid.data(), indexEnd, valid.size() - 1, &info), Error::kLevelOutOfRange);
    CHECK_EQ(parse(valid.data(), indexEnd, valid.size(), &info), Error::kNone);
    return 0;
}

int negativeHeader() {
    struct Patch {
        const char* what;
        size_t at;
        uint32_t value;
        Error expected;
    } patches[] = {
        {"identifier byte 5", 4, 0x30322059u, Error::kIdentifier},
        {"vkFormat R8G8B8A8_UNORM", 12, 37, Error::kUnsupportedFormat},
        {"vkFormat ASTC_4x4_UNORM", 12, 157, Error::kUnsupportedFormat},
        {"vkFormat 0", 12, 0, Error::kUnsupportedFormat},
        {"typeSize 4", 16, 4, Error::kTypeSize},
        {"pixelWidth 0", 20, 0, Error::kZeroSize},
        {"pixelHeight 0", 24, 0, Error::kZeroSize},
        {"pixelDepth 1", 28, 1, Error::kNot2D},
        {"faceCount 6", 36, 6, Error::kFaceCount},
        {"faceCount 0", 36, 0, Error::kFaceCount},
        {"levelCount 0", 40, 0, Error::kLevelCount},
        {"levelCount 6 (chain of 16 x 12 is 5)", 40, 6, Error::kLevelCount},
        {"levelCount 33", 40, 33, Error::kLevelCount},
        {"supercompression 1", 44, 1, Error::kSupercompression},
        {"supercompression 2", 44, 2, Error::kSupercompression},
        {"pixelWidth 64 (byteLength no longer matches)", 20, 64, Error::kLevelSize},
        {"layerCount 2 (byteLength no longer matches)", 32, 2, Error::kLevelSize},
    };
    for (const Patch& p : patches) {
        std::vector<uint8_t> bytes = base();
        put32(bytes, p.at, p.value);
        expectError(p.what, bytes, p.expected);
    }
    // Overflowing dimensions: blocks(w) * blocks(h) * layers * blockBytes exceeds 64 bits.
    std::vector<uint8_t> huge = base();
    put32(huge, 20, 0xFFFFFFFFu);
    put32(huge, 24, 0xFFFFFFFFu);
    put32(huge, 32, 0xFFFFFFFFu);
    expectError("overflowing dimensions and layers", huge, Error::kLevelSize);
    put32(huge, 32, 0);
    expectError("overflowing dimensions", huge, Error::kLevelSize);
    return 0;
}

int negativeLevels() {
    const std::vector<uint8_t> valid = base();
    const uint64_t level0 = get64(valid, levelEntry(0));
    const uint64_t level1 = get64(valid, levelEntry(1));
    std::vector<uint8_t> bytes;

    bytes = valid;
    put64(bytes, levelEntry(0) + 8, get64(valid, levelEntry(0) + 8) + 1);
    expectError("byteLength + 1", bytes, Error::kLevelSize);
    bytes = valid;
    put64(bytes, levelEntry(2) + 8, get64(valid, levelEntry(2) + 8) - 1);
    expectError("byteLength - 1", bytes, Error::kLevelSize);
    bytes = valid;
    put64(bytes, levelEntry(0) + 16, get64(valid, levelEntry(0) + 16) + 1);
    expectError("uncompressedByteLength + 1", bytes, Error::kLevelSize);
    bytes = valid;
    put64(bytes, levelEntry(0), valid.size());
    expectError("offset = file size", bytes, Error::kLevelOutOfRange);
    bytes = valid;
    put64(bytes, levelEntry(0), UINT64_MAX - 7);
    expectError("offset near 2^64", bytes, Error::kLevelOutOfRange);
    bytes = valid;
    put64(bytes, levelEntry(0), level0 + 4);
    expectError("offset misaligned by 4", bytes, Error::kLevelAlignment);
    bytes = valid;
    put64(bytes, levelEntry(2), level1);
    expectError("level 2 overlaps level 1", bytes, Error::kLevelOverlap);
    bytes = valid;
    put64(bytes, levelEntry(1), level0 - 8);
    expectError("level 1 runs into level 0", bytes, Error::kLevelOverlap);
    bytes = valid;
    put64(bytes, levelEntry(4), 0);
    expectError("level 4 over the header", bytes, Error::kLevelOverlap);
    return 0;
}

int negativeIndex() {
    struct Patch {
        const char* what;
        size_t at;
        uint64_t value;
        bool wide;
    } patches[] = {
        {"dfdByteLength past the end", 52, 0x10000, false},
        {"dfdByteOffset past the end", 48, 0xFFFFFFF0u, false},
        {"kvdByteOffset + length past the end", 56, 0xFFFFFFFFu, false},
        {"sgdByteOffset near 2^64", 64, UINT64_MAX - 1, true},
        {"sgdByteLength past the end", 72, 0x10000, true},
    };
    for (const Patch& p : patches) {
        std::vector<uint8_t> bytes = base();
        if (p.wide) {
            put64(bytes, p.at, p.value);
            if (p.at == 64) put64(bytes, 72, 4);
        } else {
            put32(bytes, p.at, static_cast<uint32_t>(p.value));
        }
        expectError(p.what, bytes, Error::kIndexOutOfRange);
    }
    std::vector<uint8_t> bytes = base();
    put32(bytes, 48, 40);
    expectError("dfd inside the header", bytes, Error::kIndexOutOfRange);
    return 0;
}

// --- real textures of tools/textures/build_textures.ps1 (README table) ---

struct RealTexture {
    const char* name;
    Format astc;
    uint32_t astcWidth, astcHeight, astcLevels;
    Format etc2;
    uint32_t etc2Width, etc2Height, etc2Levels;
    uint32_t layerCount;  // as stored in the file
};

constexpr RealTexture kRealTextures[] = {
    {"day_detail", Format::kAstc12x12Srgb, 7168, 5734, 13, Format::kEtc2R8G8B8Srgb, 1792, 1433, 11, 8},
    {"monthly_colour", Format::kAstc6x6Srgb, 2048, 819, 12, Format::kEtc2R8G8B8Srgb, 1024, 409, 11, 12},
    {"clouds", Format::kAstc8x8Unorm, 8192, 3277, 14, Format::kEtc2R8G8B8Unorm, 4096, 1638, 13, 0},
    {"city_lights", Format::kAstc8x8Srgb, 8192, 3277, 14, Format::kEtc2R8G8B8Srgb, 4096, 1638, 13, 0},
    {"terrain_normal", Format::kAstc6x6Unorm, 4096, 1638, 13, Format::kEtc2R8G8B8A8Unorm, 2048, 819, 12, 0},
    {"stars", Format::kAstc6x6Srgb, 4096, 2048, 13, Format::kEtc2R8G8B8Srgb, 2048, 1024, 12, 0},
    {"interior_earth", Format::kAstc6x6Srgb, 2048, 7296, 1, Format::kEtc2R8G8B8A8Srgb, 1024, 3648, 1, 0},
    {"interior_cabin", Format::kAstc8x8Srgb, 2048, 7296, 1, Format::kEtc2R8G8B8Srgb, 1024, 3648, 1, 0},
    {"interior_emissive", Format::kAstc8x8Srgb, 2048, 7296, 1, Format::kEtc2R8G8B8Srgb, 1024, 3648, 1, 0},
    {"interior_ambient", Format::kAstc8x8Srgb, 512, 1824, 1, Format::kEtc2R8G8B8Srgb, 256, 912, 1, 0},
};

// Reads only header + level index, like the loader does.
void checkRealFile(const std::filesystem::path& path, Format format, uint32_t width, uint32_t height,
                   uint32_t levels, uint32_t layerCount) {
    std::ifstream in(path, std::ios::binary);
    uint64_t fileSize = std::filesystem::file_size(path);
    std::vector<uint8_t> prefix(kHeaderBytes);
    in.read(reinterpret_cast<char*>(prefix.data()), kHeaderBytes);
    size_t need = prefixBytes(prefix.data(), static_cast<size_t>(in.gcount()));
    CHECK(need > kHeaderBytes);
    if (need <= kHeaderBytes) return;
    prefix.resize(need);
    in.read(reinterpret_cast<char*>(prefix.data() + kHeaderBytes), static_cast<std::streamsize>(need - kHeaderBytes));
    Info info;
    Error error = parse(prefix.data(), kHeaderBytes + static_cast<size_t>(in.gcount()), fileSize, &info);
    std::printf("%s: %s, format %u, %ux%u, layers %u, levels %u\n", path.string().c_str(), errorName(error),
                static_cast<unsigned>(info.format), info.width, info.height, info.layers, info.levels);
    CHECK_EQ(error, Error::kNone);
    CHECK_EQ(info.format, format);
    CHECK_EQ(info.width, width);
    CHECK_EQ(info.height, height);
    CHECK_EQ(info.levels, levels);
    CHECK_EQ(info.layers, layerCount == 0 ? 1 : layerCount);
    CHECK(info.array == (layerCount != 0));
}

int realFiles() {
    std::filesystem::path root(CUPOLA_REAL_TEXTURE_DIR);
    if (!std::filesystem::is_directory(root / "astc") || !std::filesystem::is_directory(root / "etc2")) {
        std::printf("SKIP: %s has no astc/etc2 folders (built by tools/textures/build_textures.ps1)\n",
                    root.string().c_str());
        return kSkip;
    }
    int checked = 0;
    for (const RealTexture& t : kRealTextures) {
        std::string file = std::string(t.name) + ".ktx2";
        checkRealFile(root / "astc" / file, t.astc, t.astcWidth, t.astcHeight, t.astcLevels, t.layerCount);
        checkRealFile(root / "etc2" / file, t.etc2, t.etc2Width, t.etc2Height, t.etc2Levels, t.layerCount);
        checked += 2;
    }
    std::printf("real files checked: %d\n", checked);
    return 0;
}

struct Case {
    const char* name;
    std::function<int()> run;  // returns 0 or kSkip; failures go through gFailures
};

const std::vector<Case>& cases() {
    static const std::vector<Case> all = {
        {"fixture_astc_6x6_srgb_2d",
         [] {
             checkFixture("astc_6x6_srgb_2d.ktx2", Format::kAstc6x6Srgb, 6, 6, 16, 1, false, true);
             return 0;
         }},
        {"fixture_astc_8x8_unorm_array3",
         [] {
             checkFixture("astc_8x8_unorm_array3.ktx2", Format::kAstc8x8Unorm, 8, 8, 16, 3, true, true);
             return 0;
         }},
        {"fixture_etc2_rgb_srgb_2d",
         [] {
             checkFixture("etc2_rgb_srgb_2d.ktx2", Format::kEtc2R8G8B8Srgb, 4, 4, 8, 1, false, false);
             return 0;
         }},
        {"fixture_etc2_rgba_unorm_2d",
         [] {
             checkFixture("etc2_rgba_unorm_2d.ktx2", Format::kEtc2R8G8B8A8Unorm, 4, 4, 16, 1, false, false);
             return 0;
         }},
        {"etc2_rgba_srgb",
         [] {
             // Same block layout as the UNORM fixture; only vkFormat differs (interior_earth ETC2 slice).
             std::vector<uint8_t> bytes = fixture("etc2_rgba_unorm_2d.ktx2");
             put32(bytes, 12, static_cast<uint32_t>(Format::kEtc2R8G8B8A8Srgb));
             Info info;
             Error error = parseAll(bytes, &info);
             if (error != Error::kNone) std::printf("etc2_rgba_srgb: %s\n", errorName(error));
             CHECK_EQ(error, Error::kNone);
             CHECK_EQ(info.format, Format::kEtc2R8G8B8A8Srgb);
             CHECK_EQ(info.blockWidth, 4);
             CHECK_EQ(info.blockHeight, 4);
             CHECK_EQ(info.blockBytes, 16);
             return 0;
         }},
        {"negative_truncated", negativeTruncated},
        {"negative_header", negativeHeader},
        {"negative_levels", negativeLevels},
        {"negative_index", negativeIndex},
        {"real_files", realFiles},
    };
    return all;
}

}  // namespace

int main(int argc, char** argv) {
    int result = 0;
    bool found = false;
    for (const Case& c : cases()) {
        if (argc > 1 && std::strcmp(argv[1], c.name) != 0) continue;
        found = true;
        int before = gFailures;
        int r = c.run();
        const char* status = gFailures != before ? "FAIL" : r == kSkip ? "SKIP" : "PASS";
        std::printf("[%s] %s\n", status, c.name);
        if (r == kSkip && argc > 1) result = kSkip;
    }
    if (!found) {
        std::printf("unknown case %s\n", argv[1]);
        return 1;
    }
    return gFailures != 0 ? 1 : result;
}
