#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <map>
#include <memory>

#include "device.h"
#include "frame_inputs.h"
#include "offscreen.h"
#include "surface_target.h"

// JNI entry points of com.baer.cupola.core.render.NativeBridge; all run on the CupolaRender thread.
namespace {

using cupola::Device;
using cupola::SurfaceTarget;

std::map<jint, std::unique_ptr<SurfaceTarget>> gTargets;

SurfaceTarget* findTarget(jint id) {
    auto it = gTargets.find(id);
    return it == gTargets.end() ? nullptr : it->second.get();
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_baer_cupola_core_render_NativeBridge_bindThread(JNIEnv*, jclass) {
    cupola::bindRenderThread();
}

JNIEXPORT jlongArray JNICALL Java_com_baer_cupola_core_render_NativeBridge_deviceInfo(JNIEnv* env, jclass,
                                                                                   jobjectArray nameOut) {
    cupola::assertRenderThread("deviceInfo");
    VkResult error;
    Device* device = Device::acquire(&error);
    if (device == nullptr) return nullptr;
    const VkPhysicalDeviceProperties& p = device->properties;
    float period = p.limits.timestampPeriod;
    jint periodBits;
    static_assert(sizeof(periodBits) == sizeof(period));
    __builtin_memcpy(&periodBits, &period, sizeof(period));
    jlong values[] = {
        static_cast<jlong>(p.apiVersion),
        static_cast<jlong>(p.driverVersion),
        device->astcLdr ? 1 : 0,
        static_cast<jlong>(p.limits.maxImageDimension2D),
        static_cast<jlong>(periodBits),
        device->timestamps ? 1 : 0,
    };
    jstring name = env->NewStringUTF(p.deviceName);
    Device::release();
    if (name == nullptr) return nullptr;
    env->SetObjectArrayElement(nameOut, 0, name);
    jlongArray result = env->NewLongArray(sizeof(values) / sizeof(values[0]));
    if (result != nullptr) env->SetLongArrayRegion(result, 0, sizeof(values) / sizeof(values[0]), values);
    return result;
}

JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_attach(JNIEnv* env, jclass, jint id,
                                                                         jobject surface) {
    cupola::assertRenderThread("attach");
    gTargets.erase(id);
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) return VK_ERROR_SURFACE_LOST_KHR;
    SurfaceTarget* target = nullptr;
    VkResult result = SurfaceTarget::create(window, &target);
    if (result == VK_SUCCESS) gTargets[id].reset(target);
    return result;
}

JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_resize(JNIEnv*, jclass, jint id, jint width,
                                                                         jint height) {
    cupola::assertRenderThread("resize");
    SurfaceTarget* target = findTarget(id);
    if (target == nullptr || width < 0 || height < 0) return VK_ERROR_INITIALIZATION_FAILED;
    target->requestResize(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
    return VK_SUCCESS;
}

JNIEXPORT void JNICALL Java_com_baer_cupola_core_render_NativeBridge_detach(JNIEnv*, jclass, jint id) {
    cupola::assertRenderThread("detach");
    gTargets.erase(id);
}

JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_setInputs(JNIEnv* env, jclass, jint id,
                                                                            jfloatArray values) {
    cupola::assertRenderThread("setInputs");
    SurfaceTarget* target = findTarget(id);
    if (target == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
    float copy[cupola::inputs::kCount] = {};
    jsize count = std::min<jsize>(env->GetArrayLength(values), cupola::inputs::kCount);
    env->GetFloatArrayRegion(values, 0, count, copy);
    target->setInputs(copy, cupola::inputs::kCount);
    return VK_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_renderFrame(JNIEnv*, jclass, jint id) {
    cupola::assertRenderThread("renderFrame");
    SurfaceTarget* target = findTarget(id);
    if (target == nullptr) return VK_ERROR_INITIALIZATION_FAILED;
    return target->renderFrame();
}

JNIEXPORT jlong JNICALL Java_com_baer_cupola_core_render_NativeBridge_lastGpuTimeNanos(JNIEnv*, jclass) {
    cupola::assertRenderThread("lastGpuTimeNanos");
    return Device::lastGpuTimeNs();
}

JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_renderOffscreen(JNIEnv* env, jclass,
                                                                                  jint width, jint height,
                                                                                  jfloatArray inputs,
                                                                                  jbyteArray out) {
    cupola::assertRenderThread("renderOffscreen");
    if (width <= 0 || height <= 0 || env->GetArrayLength(out) != static_cast<jsize>(width) * height * 4) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    float copy[cupola::inputs::kCount] = {};
    jsize count = std::min<jsize>(env->GetArrayLength(inputs), cupola::inputs::kCount);
    env->GetFloatArrayRegion(inputs, 0, count, copy);
    VkResult error;
    Device* device = Device::acquire(&error);
    if (device == nullptr) return error;
    jbyte* bytes = env->GetByteArrayElements(out, nullptr);
    if (bytes == nullptr) {
        Device::release();
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    VkResult result = cupola::renderOffscreen(*device, static_cast<uint32_t>(width), static_cast<uint32_t>(height),
                                              copy, reinterpret_cast<uint8_t*>(bytes));
    env->ReleaseByteArrayElements(out, bytes, result == VK_SUCCESS ? 0 : JNI_ABORT);
    Device::release();
    return result;
}

}  // extern "C"
