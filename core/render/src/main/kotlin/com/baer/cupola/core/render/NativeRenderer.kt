package com.baer.cupola.core.render

import android.util.Log
import android.view.Surface
import com.baer.cupola.core.render.scene.FrameScheduler

/**
 * Kotlin facade of the native Vulkan renderer. Owns the [RenderThread]; every public method may be
 * called from any thread and is executed there. Attach and detach block until the native side is done,
 * so `surfaceDestroyed` can return only after the swapchain is gone.
 *
 * Surface calls never throw for a Vulkan failure; [RenderTargets] drops the failing id or recovers a lost device.
 * [inputsProvider] supplies per-frame inputs ([FrameInputs] layout) on the render thread; null keeps the last ones.
 */
class NativeRenderer(
    inputsProvider: (surfaceId: Int, frameTimeNanos: Long) -> FloatArray? = { _, _ -> null },
) : AutoCloseable {
    private val thread: RenderThread = RenderThread { targets.onFrame(it) }
    private val targets: RenderTargets<Surface> =
        RenderTargets(NativeBridgeApi, inputsProvider, { thread.requestFrame() }) { Log.w(LOG_TAG, it) }

    init {
        thread.start()
        thread.postAndWait { NativeBridge.bindThread() }
    }

    fun deviceInfo(): DeviceInfo = thread.postAndWait {
        val name = arrayOfNulls<String>(1)
        val v = checkNotNull(NativeBridge.deviceInfo(name)) { "no Vulkan 1.1 device" }
        DeviceInfo(
            name = name[0].orEmpty(),
            apiVersion = v[0].toInt(),
            driverVersion = v[1],
            astcLdr = v[2] != 0L,
            maxImageDimension2D = v[3].toInt(),
            timestampPeriod = Float.fromBits(v[4].toInt()),
            timestampsSupported = v[5] != 0L,
        )
    }

    /**
     * Replaces any surface already attached under [id]; rendering starts at the next vsync.
     * Returns the VkResult; on failure nothing is attached under [id].
     */
    fun attachSurface(id: Int, surface: Surface): Int = thread.postAndWait { targets.attach(id, surface) }

    /** Returns the VkResult; a failure detaches [id]. */
    fun resizeSurface(id: Int, width: Int, height: Int): Int = thread.postAndWait { targets.resize(id, width, height) }

    fun detachSurface(id: Int) = thread.postAndWait { targets.detach(id) }

    /** Inputs are copied; ignored if [id] is detached by the time the render thread runs. */
    fun setFrameInputs(id: Int, inputs: FloatArray) {
        val copy = inputs.copyOf()
        thread.post { targets.setInputs(id, copy) }
    }

    /** Adjusts the [FrameScheduler] of [id] (visibility, battery saver, motion) on the render thread. */
    fun updateScheduler(id: Int, block: FrameScheduler.() -> Unit) {
        thread.post { targets.updateScheduler(id, block) }
    }

    /** Renders and presents one frame; false if skipped (zero-sized or out-of-date swapchain) or failed. */
    fun renderFrame(id: Int): Boolean = thread.postAndWait { targets.renderFrame(id) == VK_SUCCESS }

    /** True while [id] has a native target (false after a failed attach or a dropped id). */
    internal fun isAttached(id: Int): Boolean = thread.postAndWait { targets.isAttached(id) }

    /** GPU time of the last completed frame in nanoseconds, -1 if unknown. */
    fun lastGpuTimeNanos(): Long = thread.postAndWait { NativeBridge.lastGpuTimeNanos() }

    /** Tightly packed RGBA8, row-major, top row first. */
    fun renderOffscreen(width: Int, height: Int, inputs: FloatArray): ByteArray {
        require(width > 0 && height > 0) { "size must be positive: ${width}x$height" }
        val out = ByteArray(width * height * 4)
        thread.postAndWait {
            val result = NativeBridge.renderOffscreen(width, height, inputs, out)
            check(result >= 0) { "offscreen failed: VkResult $result" }
        }
        return out
    }

    /** Detaches every surface, releases the device and stops the render thread. */
    override fun close() {
        thread.postAndWait { targets.detachAll() }
        thread.quit()
    }

    private companion object {
        const val VK_SUCCESS = 0
        const val LOG_TAG = "Cupola"
    }
}
