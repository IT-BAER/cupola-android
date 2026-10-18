package com.baer.cupola.core.render

import android.view.Surface
import com.baer.cupola.core.render.scene.FrameScheduler

/**
 * Kotlin facade of the native Vulkan renderer. Owns the [RenderThread]; every public method may be
 * called from any thread and is executed there. Attach and detach block until the native side is done,
 * so `surfaceDestroyed` can return only after the swapchain is gone.
 *
 * [inputsProvider] supplies per-frame inputs ([FrameInputs] layout) on the render thread; null keeps the last ones.
 */
class NativeRenderer(
    private val inputsProvider: (surfaceId: Int, frameTimeNanos: Long) -> FloatArray? = { _, _ -> null },
) : AutoCloseable {
    private val thread = RenderThread(::onFrame)

    // Render thread only.
    private val schedulers = LinkedHashMap<Int, FrameScheduler>()
    private var lastFrameNanos = 0L
    private var vsyncNanos = DEFAULT_VSYNC_NANOS

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

    /** Replaces any surface already attached under [id]; rendering starts at the next vsync. */
    fun attachSurface(id: Int, surface: Surface) = thread.postAndWait {
        schedulers.remove(id)
        vkCheck(NativeBridge.attach(id, surface), "attach $id")
        schedulers[id] = FrameScheduler().apply { setVisible(true) }
        thread.requestFrame()
    }

    fun resizeSurface(id: Int, width: Int, height: Int) = thread.postAndWait {
        vkCheck(NativeBridge.resize(id, width, height), "resize $id")
        schedulers[id]?.setVisible(true)
        thread.requestFrame()
    }

    fun detachSurface(id: Int) = thread.postAndWait {
        schedulers.remove(id)
        NativeBridge.detach(id)
    }

    /** Inputs are copied; ignored if [id] is detached by the time the render thread runs. */
    fun setFrameInputs(id: Int, inputs: FloatArray) {
        val copy = inputs.copyOf()
        thread.post { NativeBridge.setInputs(id, copy) }
    }

    /** Adjusts the [FrameScheduler] of [id] (visibility, battery saver, motion) on the render thread. */
    fun updateScheduler(id: Int, block: FrameScheduler.() -> Unit) {
        thread.post {
            schedulers[id]?.let {
                it.block()
                thread.requestFrame()
            }
        }
    }

    /** Renders and presents one frame; false if skipped (zero-sized or out-of-date swapchain). */
    fun renderFrame(id: Int): Boolean = thread.postAndWait {
        vkCheck(NativeBridge.renderFrame(id), "render $id") == VK_SUCCESS
    }

    /** GPU time of the last completed frame in nanoseconds, -1 if unknown. */
    fun lastGpuTimeNanos(): Long = thread.postAndWait { NativeBridge.lastGpuTimeNanos() }

    /** Tightly packed RGBA8, row-major, top row first. */
    fun renderOffscreen(width: Int, height: Int, inputs: FloatArray): ByteArray {
        require(width > 0 && height > 0) { "size must be positive: ${width}x$height" }
        val out = ByteArray(width * height * 4)
        thread.postAndWait { vkCheck(NativeBridge.renderOffscreen(width, height, inputs, out), "offscreen") }
        return out
    }

    /** Detaches every surface, releases the device and stops the render thread. */
    override fun close() {
        thread.postAndWait {
            for (id in schedulers.keys) NativeBridge.detach(id)
            schedulers.clear()
        }
        thread.quit()
    }

    private fun onFrame(frameTimeNanos: Long) {
        val delta = frameTimeNanos - lastFrameNanos
        if (lastFrameNanos != 0L && delta in MIN_VSYNC_NANOS..MAX_VSYNC_NANOS) vsyncNanos = delta
        lastFrameNanos = frameTimeNanos
        for ((id, scheduler) in schedulers.entries.toList()) {
            if (schedulers[id] !== scheduler || !scheduler.shouldRender(frameTimeNanos, vsyncNanos)) continue
            inputsProvider(id, frameTimeNanos)?.let { NativeBridge.setInputs(id, it) }
            if (schedulers[id] === scheduler) vkCheck(NativeBridge.renderFrame(id), "render $id")
        }
        if (schedulers.values.any { it.targetFps > 0 || it.renderOnce }) thread.requestFrame()
    }

    private fun vkCheck(result: Int, what: String): Int {
        check(result >= 0) { "$what failed: VkResult $result" }
        return result
    }

    private companion object {
        const val VK_SUCCESS = 0
        const val DEFAULT_VSYNC_NANOS = 16_666_667L
        const val MIN_VSYNC_NANOS = 2_000_000L
        const val MAX_VSYNC_NANOS = 50_000_000L
    }
}
