package com.baer.cupola.core.render

import android.content.res.AssetManager
import android.view.Surface

/** JNI surface of `libcupola_render.so`. Every call must happen on the [RenderThread]. Results are VkResult codes. */
internal object NativeBridge {
    init {
        System.loadLibrary("cupola_render")
    }

    @JvmStatic external fun bindThread()

    /** Writes the device name into `nameOut[0]`; layout in [NativeRenderer.deviceInfo]. Null if no Vulkan 1.1 device. */
    @JvmStatic external fun deviceInfo(nameOut: Array<String?>): LongArray?

    @JvmStatic external fun attach(id: Int, surface: Surface): Int

    @JvmStatic external fun resize(id: Int, width: Int, height: Int): Int

    @JvmStatic external fun detach(id: Int)

    @JvmStatic external fun setInputs(id: Int, values: FloatArray): Int

    @JvmStatic external fun renderFrame(id: Int): Int

    @JvmStatic external fun lastGpuTimeNanos(): Long

    @JvmStatic external fun renderOffscreen(width: Int, height: Int, inputs: FloatArray, out: ByteArray): Int

    /** Loads a KTX2 asset into a Vulkan image and destroys it; layout in [NativeRenderer.loadTextureInfo]. */
    @JvmStatic external fun loadTextureInfo(assets: AssetManager, path: String): IntArray
}
