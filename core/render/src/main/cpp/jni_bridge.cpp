#include <android/asset_manager_jni.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <algorithm>
#include <map>
#include <memory>

#include "atmosphere_lut.h"
#include "device.h"
#include "frame_inputs.h"
#include "offscreen.h"
#include "surface_target.h"
#include "texture_loader.h"
#include "texture_set.h"

// JNI entry points of com.baer.cupola.core.render.NativeBridge; all run on the CupolaRender thread.
namespace {

using cupola::Device;
using cupola::SurfaceTarget;

std::map<jint, std::unique_ptr<SurfaceTarget>> gTargets;
// Keeps the Java AssetManager (and so its AAssetManager) alive for the process.
jobject gAssetManagerRef = nullptr;

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

JNIEXPORT void JNICALL Java_com_baer_cupola_core_render_NativeBridge_setAssetManager(JNIEnv* env, jclass,
                                                                                   jobject assetManager) {
    cupola::assertRenderThread("setAssetManager");
    if (gAssetManagerRef != nullptr) return;
    gAssetManagerRef = env->NewGlobalRef(assetManager);
    if (gAssetManagerRef != nullptr) Device::setAssetManager(AAssetManager_fromJava(env, gAssetManagerRef));
}

// Loads the texture set if needed; returns its loaded-slot mask, or a negative VkResult.
JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_textureSetMask(JNIEnv*, jclass) {
    cupola::assertRenderThread("textureSetMask");
    VkResult error;
    Device* device = Device::acquire(&error);
    if (device == nullptr) return error;
    const cupola::TextureSet* set = nullptr;
    VkResult result = device->textures(&set);
    jint mask = result == VK_SUCCESS ? static_cast<jint>(set->loadedMask) : result;
    Device::release();
    return mask;
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

JNIEXPORT jlong JNICALL Java_com_baer_cupola_core_render_NativeBridge_lastEarthPassGpuTimeNanos(JNIEnv*, jclass) {
    cupola::assertRenderThread("lastEarthPassGpuTimeNanos");
    return Device::lastEarthPassGpuTimeNs();
}

JNIEXPORT jlong JNICALL Java_com_baer_cupola_core_render_NativeBridge_lastSkyPassGpuTimeNanos(JNIEnv*, jclass) {
    cupola::assertRenderThread("lastSkyPassGpuTimeNanos");
    return Device::lastSkyPassGpuTimeNs();
}

// hold = true acquires one device reference (VkResult on failure), false releases it.
JNIEXPORT jint JNICALL Java_com_baer_cupola_core_render_NativeBridge_holdDevice(JNIEnv*, jclass, jboolean hold) {
    cupola::assertRenderThread("holdDevice");
    if (!hold) {
        Device::release();
        return VK_SUCCESS;
    }
    VkResult error;
    return Device::acquire(&error) == nullptr ? error : VK_SUCCESS;
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

// Returns [status, VkResult, format, width, height, layers, levels]; see TextureInfo.kt.
JNIEXPORT jintArray JNICALL Java_com_baer_cupola_core_render_NativeBridge_loadTextureInfo(JNIEnv* env, jclass,
                                                                                       jobject assetManager,
                                                                                       jstring path) {
    cupola::assertRenderThread("loadTextureInfo");
    jint values[7] = {};
    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return nullptr;  // OutOfMemoryError pending
    if (assets == nullptr) {
        values[0] = static_cast<jint>(cupola::TextureStatus::kAssetOpen);
    } else {
        VkResult error;
        Device* device = Device::acquire(&error);
        if (device == nullptr) {
            values[0] = static_cast<jint>(cupola::TextureStatus::kVulkan);
            values[1] = error;
        } else {
            cupola::Texture texture;
            VkResult vkResult;
            values[0] = cupola::loadTexture(*device, assets, chars, &texture, &vkResult);
            values[1] = vkResult;
            if (values[0] == static_cast<jint>(cupola::TextureStatus::kOk)) {
                const cupola::ktx2::Info& info = texture.info;
                values[2] = static_cast<jint>(info.format);
                values[3] = static_cast<jint>(info.width);
                values[4] = static_cast<jint>(info.height);
                values[5] = static_cast<jint>(info.layers);
                values[6] = static_cast<jint>(info.levels);
                cupola::destroyTexture(*device, &texture);
            }
            Device::release();
        }
    }
    env->ReleaseStringUTFChars(path, chars);
    jintArray result = env->NewIntArray(7);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, 7, values);
    return result;
}

// Computes the atmosphere LUTs on the CPU and returns LUT `index` (0 transmittance, 1 multiple scattering, 2 sky
// irradiance) as [width, height, rgba texels...]; null for an unknown index.
JNIEXPORT jfloatArray JNICALL Java_com_baer_cupola_core_render_NativeBridge_atmosphereLut(JNIEnv* env, jclass,
                                                                                      jint index) {
    cupola::assertRenderThread("atmosphereLut");
    if (index < 0 || index > 2) return nullptr;
    cupola::atmosphere::Luts luts;
    cupola::atmosphere::computeLuts(&luts);
    const cupola::atmosphere::Lut& lut =
        index == 0 ? luts.transmittance : index == 1 ? luts.multiScatter : luts.irradiance;
    const jsize size = static_cast<jsize>(lut.rgba.size() + 2);
    jfloatArray result = env->NewFloatArray(size);
    if (result == nullptr) return nullptr;
    const jfloat head[2] = {static_cast<jfloat>(lut.width), static_cast<jfloat>(lut.height)};
    env->SetFloatArrayRegion(result, 0, 2, head);
    env->SetFloatArrayRegion(result, 2, size - 2, lut.rgba.data());
    return result;
}

}  // extern "C"
