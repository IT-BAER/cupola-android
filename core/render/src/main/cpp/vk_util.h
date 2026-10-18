#pragma once

#define VK_USE_PLATFORM_ANDROID_KHR 1
#include <android/log.h>
#include <android/trace.h>
#include <vulkan/vulkan.h>

#define CUPOLA_LOG_TAG "CupolaRender"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, CUPOLA_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, CUPOLA_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, CUPOLA_LOG_TAG, __VA_ARGS__)

// Returns the VkResult from the enclosing function when the call fails.
#define VK_TRY(expr)                                                     \
    do {                                                                 \
        VkResult vk_try_result_ = (expr);                                \
        if (vk_try_result_ < 0) {                                        \
            LOGE("%s failed: %d (%s:%d)", #expr, vk_try_result_, __FILE__, __LINE__); \
            return vk_try_result_;                                       \
        }                                                                \
    } while (0)

namespace cupola {

// Binds the render thread; every renderer entry point must run on it (checked in debug builds).
void bindRenderThread();
void assertRenderThread(const char* where);

class TraceSection {
public:
    explicit TraceSection(const char* name) { ATrace_beginSection(name); }
    ~TraceSection() { ATrace_endSection(); }
    TraceSection(const TraceSection&) = delete;
    TraceSection& operator=(const TraceSection&) = delete;
};

}  // namespace cupola
