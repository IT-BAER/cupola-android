#pragma once

#include <cstddef>
#include <cstdint>

// KTX2 header and level index reader for the texture contract in tools/textures/README.md
// ("Runtime contract (P5.5)"). Pure C++, no Android or Vulkan headers; host-tested.
namespace cupola::ktx2 {

// Numeric VkFormat values of the allowlisted formats (checked against vulkan.h in texture_loader.cpp).
enum class Format : uint32_t {
    kEtc2R8G8B8Unorm = 147,
    kEtc2R8G8B8Srgb = 148,
    kEtc2R8G8B8A8Unorm = 151,
    kAstc6x6Unorm = 165,
    kAstc6x6Srgb = 166,
    kAstc8x8Unorm = 171,
    kAstc8x8Srgb = 172,
};

enum class Error : int32_t {
    kNone = 0,
    kTruncated,             // header or level index runs past the end of the data
    kIdentifier,
    kUnsupportedFormat,     // vkFormat outside the allowlist
    kTypeSize,
    kZeroSize,              // pixelWidth or pixelHeight is 0
    kNot2D,                 // pixelDepth != 0
    kFaceCount,
    kLevelCount,            // 0 or more than the full mip chain
    kSupercompression,
    kIndexOutOfRange,       // DFD, KVD or SGD outside the file
    kLevelSize,             // byteLength or uncompressedByteLength differs from the block count
    kLevelAlignment,
    kLevelOutOfRange,       // level data outside the file
    kLevelOverlap,
};

const char* errorName(Error error);

constexpr size_t kHeaderBytes = 80;
constexpr size_t kLevelIndexEntryBytes = 24;
constexpr uint32_t kMaxLevels = 32;

struct Level {
    uint64_t offset = 0;
    uint64_t length = 0;
};

struct Info {
    Format format{};
    uint32_t blockWidth = 0;
    uint32_t blockHeight = 0;
    uint32_t blockBytes = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    uint32_t layers = 0;  // layerCount with 0 mapped to 1 (Vulkan arrayLayers)
    bool array = false;   // layerCount != 0 in the file
    uint32_t levels = 0;
    Level level[kMaxLevels];
};

// Bytes of header plus level index, from the first kHeaderBytes of the file. Returns 0 when fewer
// than kHeaderBytes are given or the level count is outside 1..kMaxLevels.
size_t prefixBytes(const uint8_t* data, size_t size);

// Parses and validates header and level index. `data` holds at least the prefix (whole file is fine);
// `fileSize` is the length of the whole file, used for every range check.
Error parse(const uint8_t* data, size_t size, uint64_t fileSize, Info* out);

}  // namespace cupola::ktx2
