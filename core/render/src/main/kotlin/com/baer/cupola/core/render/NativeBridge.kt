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

    @JvmStatic external fun lastEarthPassGpuTimeNanos(): Long

    /** true acquires a device reference (returns a VkResult), false releases one. */
    @JvmStatic external fun holdDevice(hold: Boolean): Int

    @JvmStatic external fun renderOffscreen(width: Int, height: Int, inputs: FloatArray, out: ByteArray): Int

    /** Asset manager for the Earth texture set; the first call wins (kept as a global reference). */
    @JvmStatic external fun setAssetManager(assets: AssetManager)

    /** Loads the texture set if needed; bit per slot loaded from its asset, or a negative VkResult. */
    @JvmStatic external fun textureSetMask(): Int

    /** Loads a KTX2 asset into a Vulkan image and destroys it; layout in [NativeRenderer.loadTextureInfo]. */
    @JvmStatic external fun loadTextureInfo(assets: AssetManager, path: String): IntArray

    /** Atmosphere LUT [index] (0 transmittance, 1 multiple scattering, 2 sky irradiance): width, height, RGBA texels. */
    @JvmStatic external fun atmosphereLut(index: Int): FloatArray?
}
