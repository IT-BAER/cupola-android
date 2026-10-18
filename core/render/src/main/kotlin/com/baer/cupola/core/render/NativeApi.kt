package com.baer.cupola.core.render

import android.view.Surface

/** Calls of [NativeBridge] behind an interface so [RenderTargets] runs on the JVM with a fake. Render thread only. */
internal interface NativeApi<S> {
    fun bindThread()

    fun deviceInfo(nameOut: Array<String?>): LongArray?

    fun attach(id: Int, surface: S): Int

    fun resize(id: Int, width: Int, height: Int): Int

    fun detach(id: Int)

    fun setInputs(id: Int, values: FloatArray): Int

    fun renderFrame(id: Int): Int

    fun lastGpuTimeNanos(): Long

    fun renderOffscreen(width: Int, height: Int, inputs: FloatArray, out: ByteArray): Int
}

internal object NativeBridgeApi : NativeApi<Surface> {
    override fun bindThread() = NativeBridge.bindThread()

    override fun deviceInfo(nameOut: Array<String?>) = NativeBridge.deviceInfo(nameOut)

    override fun attach(id: Int, surface: Surface) = NativeBridge.attach(id, surface)

    override fun resize(id: Int, width: Int, height: Int) = NativeBridge.resize(id, width, height)

    override fun detach(id: Int) = NativeBridge.detach(id)

    override fun setInputs(id: Int, values: FloatArray) = NativeBridge.setInputs(id, values)

    override fun renderFrame(id: Int) = NativeBridge.renderFrame(id)

    override fun lastGpuTimeNanos() = NativeBridge.lastGpuTimeNanos()

    override fun renderOffscreen(width: Int, height: Int, inputs: FloatArray, out: ByteArray) =
        NativeBridge.renderOffscreen(width, height, inputs, out)
}
