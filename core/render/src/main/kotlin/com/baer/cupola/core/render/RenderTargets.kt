package com.baer.cupola.core.render

import com.baer.cupola.core.render.scene.FrameScheduler

/**
 * Render-thread state of [NativeRenderer]: one [FrameScheduler] and stored surface per attached id. Render thread only.
 *
 * No Vulkan failure throws. A failing id is detached and forgotten (later calls for it are no-ops until a new
 * [attach]). VK_ERROR_DEVICE_LOST detaches every target, so the device is destroyed, and re-attaches every id
 * from its stored surface; a second loss before a frame succeeds on the recovered device drops all ids.
 */
internal class RenderTargets<S : Any>(
    private val api: NativeApi<S>,
    private val inputsProvider: (surfaceId: Int, frameTimeNanos: Long) -> FloatArray?,
    private val requestFrame: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val schedulers = LinkedHashMap<Int, FrameScheduler>()
    private val surfaces = HashMap<Int, S>()
    private var recovering = false
    private var lastFrameNanos = 0L
    private var vsyncNanos = DEFAULT_VSYNC_NANOS

    fun isAttached(id: Int) = id in schedulers

    fun attach(id: Int, surface: S): Int {
        forget(id)
        val result = api.attach(id, surface)
        if (result < 0) {
            log("attach $id failed: VkResult $result")
            return result
        }
        surfaces[id] = surface
        schedulers[id] = FrameScheduler().apply { setVisible(true) }
        requestFrame()
        return result
    }

    fun resize(id: Int, width: Int, height: Int): Int {
        val scheduler = schedulers[id] ?: return VK_ERROR_INITIALIZATION_FAILED
        val result = api.resize(id, width, height)
        if (result < 0) return fail(id, result, "resize")
        scheduler.setVisible(true)
        requestFrame()
        return result
    }

    fun detach(id: Int) {
        forget(id)
        api.detach(id)
    }

    fun detachAll() {
        for (id in schedulers.keys) api.detach(id)
        schedulers.clear()
        surfaces.clear()
    }

    fun setInputs(id: Int, values: FloatArray) {
        if (id in schedulers) api.setInputs(id, values)
    }

    fun updateScheduler(id: Int, block: FrameScheduler.() -> Unit) {
        schedulers[id]?.let {
            it.block()
            requestFrame()
        }
    }

    fun renderFrame(id: Int): Int {
        if (id !in schedulers) return VK_ERROR_INITIALIZATION_FAILED
        val result = api.renderFrame(id)
        if (result < 0) return fail(id, result, "render")
        if (result == VK_SUCCESS) recovering = false
        return result
    }

    fun onFrame(frameTimeNanos: Long) {
        val delta = frameTimeNanos - lastFrameNanos
        if (lastFrameNanos != 0L && delta in MIN_VSYNC_NANOS..MAX_VSYNC_NANOS) vsyncNanos = delta
        lastFrameNanos = frameTimeNanos
        for ((id, scheduler) in schedulers.entries.toList()) {
            if (schedulers[id] !== scheduler || !scheduler.shouldRender(frameTimeNanos, vsyncNanos)) continue
            inputsProvider(id, frameTimeNanos)?.let { api.setInputs(id, it) }
            if (schedulers[id] === scheduler) renderFrame(id)
        }
        if (schedulers.values.any { it.targetFps > 0 || it.renderOnce }) requestFrame()
    }

    private fun fail(id: Int, result: Int, what: String): Int {
        log("$what $id failed: VkResult $result")
        if (result != VK_ERROR_DEVICE_LOST) {
            detach(id)
        } else if (recovering) {
            log("device lost again before a successful frame; dropping all surfaces")
            detachAll()
        } else {
            recoverDevice()
        }
        return result
    }

    private fun recoverDevice() {
        recovering = true
        for (id in schedulers.keys) api.detach(id)
        for ((id, surface) in surfaces.entries.toList()) {
            val result = api.attach(id, surface)
            if (result < 0) {
                log("re-attach $id after device loss failed: VkResult $result")
                forget(id)
            }
        }
        requestFrame()
    }

    private fun forget(id: Int) {
        schedulers.remove(id)
        surfaces.remove(id)
    }

    private companion object {
        const val VK_SUCCESS = 0
        const val VK_ERROR_INITIALIZATION_FAILED = -3
        const val VK_ERROR_DEVICE_LOST = -4
        const val DEFAULT_VSYNC_NANOS = 16_666_667L
        const val MIN_VSYNC_NANOS = 2_000_000L
        const val MAX_VSYNC_NANOS = 50_000_000L
    }
}
