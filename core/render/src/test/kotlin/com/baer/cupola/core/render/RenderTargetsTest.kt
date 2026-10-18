package com.baer.cupola.core.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderTargetsTest {
    private val api = FakeNativeApi()
    private val logs = mutableListOf<String>()
    private var frameRequests = 0
    private val targets = RenderTargets(
        api = api,
        inputsProvider = { _, _ -> null },
        requestFrame = { frameRequests++ },
        log = { logs += it },
    )
    private var now = 0L

    private fun frame() {
        now += 1_000_000_000L
        targets.onFrame(now)
    }

    @Test
    fun attachFailureReturnsStatusAndLeavesNoScheduler() {
        api.attachResult = { _, _ -> VK_ERROR_INCOMPATIBLE_DRIVER }

        assertEquals(VK_ERROR_INCOMPATIBLE_DRIVER, targets.attach(1, "a"))

        assertFalse(targets.isAttached(1))
        assertEquals(emptySet<Int>(), api.live)
        assertTrue(logs.isNotEmpty())
        frame()
        assertEquals(emptyList<Int>(), api.rendered)
    }

    @Test
    fun renderFailureDropsOnlyThatId() {
        targets.attach(1, "a")
        targets.attach(2, "b")
        api.renderResult = { id -> if (id == 1) VK_ERROR_SURFACE_LOST_KHR else VK_SUCCESS }

        frame()

        assertFalse(targets.isAttached(1))
        assertTrue(targets.isAttached(2))
        assertEquals(setOf(2), api.live)
        api.rendered.clear()
        frame()
        assertEquals(listOf(2), api.rendered)
    }

    @Test
    fun redrawAndResizeFailuresDropTheIdWithoutThrowing() {
        targets.attach(1, "a")
        targets.attach(2, "b")
        api.renderResult = { VK_ERROR_OUT_OF_DEVICE_MEMORY }
        api.resizeResult = { VK_ERROR_INITIALIZATION_FAILED }

        assertEquals(VK_ERROR_OUT_OF_DEVICE_MEMORY, targets.renderFrame(1))
        assertTrue(targets.resize(2, 10, 10) < 0)

        assertFalse(targets.isAttached(1))
        assertFalse(targets.isAttached(2))
        assertEquals(emptySet<Int>(), api.live)
    }

    @Test
    fun deviceLostDetachesAllAndReattachesAllFromStoredSurfaces() {
        targets.attach(1, "a")
        targets.attach(2, "b")
        api.events.clear()
        var lost = true
        api.renderResult = { if (lost) VK_ERROR_DEVICE_LOST.also { lost = false } else VK_SUCCESS }

        frame()

        val detachAll = listOf("detach 1", "detach 2")
        val reattach = listOf("attach 1 a", "attach 2 b")
        assertEquals(listOf("render 1") + detachAll + reattach, api.events.take(5))
        assertEquals(0, api.minLiveDuringRun)
        assertEquals(setOf(1, 2), api.live)
        assertTrue(targets.isAttached(1))
        assertTrue(targets.isAttached(2))
    }

    @Test
    fun secondDeviceLostBeforeASuccessfulFrameDropsAll() {
        targets.attach(1, "a")
        targets.attach(2, "b")
        api.renderResult = { VK_ERROR_DEVICE_LOST }

        frame()
        frame()

        assertFalse(targets.isAttached(1))
        assertFalse(targets.isAttached(2))
        assertEquals(emptySet<Int>(), api.live)
    }

    @Test
    fun successfulFrameAfterRecoveryAllowsAnotherRecovery() {
        targets.attach(1, "a")
        val results = ArrayDeque(listOf(VK_ERROR_DEVICE_LOST, VK_SUCCESS, VK_ERROR_DEVICE_LOST))
        api.renderResult = { results.removeFirstOrNull() ?: VK_SUCCESS }

        assertEquals(VK_ERROR_DEVICE_LOST, targets.renderFrame(1))
        assertEquals(VK_SUCCESS, targets.renderFrame(1))
        assertEquals(VK_ERROR_DEVICE_LOST, targets.renderFrame(1))

        assertTrue(targets.isAttached(1))
        assertEquals(setOf(1), api.live)
    }

    @Test
    fun callsForADroppedIdAreNoOps() {
        targets.attach(1, "a")
        api.renderResult = { VK_ERROR_SURFACE_LOST_KHR }
        frame()
        api.events.clear()
        var blockRan = false

        targets.setInputs(1, FloatArray(FrameInputs.COUNT))
        targets.updateScheduler(1) { blockRan = true }
        assertTrue(targets.resize(1, 10, 10) < 0)
        assertTrue(targets.renderFrame(1) < 0)
        frame()

        assertFalse(blockRan)
        assertEquals(emptySet<Int>(), api.live)
        assertFalse(api.events.any { it.startsWith("render") || it.startsWith("attach") })
    }

    @Test
    fun reattachAfterDropRendersAgain() {
        targets.attach(1, "a")
        api.renderResult = { VK_ERROR_SURFACE_LOST_KHR }
        frame()
        api.renderResult = { VK_SUCCESS }

        assertEquals(VK_SUCCESS, targets.attach(1, "c"))
        api.rendered.clear()
        frame()

        assertTrue(targets.isAttached(1))
        assertEquals(listOf(1), api.rendered)
        assertEquals("attach 1 c", api.events.last { it.startsWith("attach") })
    }

    private class FakeNativeApi : NativeApi<String> {
        var attachResult: (Int, String) -> Int = { _, _ -> VK_SUCCESS }
        var resizeResult: (Int) -> Int = { VK_SUCCESS }
        var renderResult: (Int) -> Int = { VK_SUCCESS }
        val live = linkedSetOf<Int>()
        val events = mutableListOf<String>()
        val rendered = mutableListOf<Int>()
        var minLiveDuringRun = Int.MAX_VALUE

        override fun bindThread() = Unit
        override fun deviceInfo(nameOut: Array<String?>): LongArray? = null

        override fun attach(id: Int, surface: String): Int {
            events += "attach $id $surface"
            live -= id
            val result = attachResult(id, surface)
            if (result >= 0) live += id
            return result
        }

        override fun resize(id: Int, width: Int, height: Int): Int {
            events += "resize $id"
            return if (id in live) resizeResult(id) else VK_ERROR_INITIALIZATION_FAILED
        }

        override fun detach(id: Int) {
            events += "detach $id"
            live -= id
            minLiveDuringRun = minOf(minLiveDuringRun, live.size)
        }

        override fun setInputs(id: Int, values: FloatArray): Int =
            if (id in live) VK_SUCCESS else VK_ERROR_INITIALIZATION_FAILED

        override fun renderFrame(id: Int): Int {
            events += "render $id"
            if (id !in live) return VK_ERROR_INITIALIZATION_FAILED
            rendered += id
            return renderResult(id)
        }

        override fun lastGpuTimeNanos(): Long = -1

        override fun renderOffscreen(width: Int, height: Int, inputs: FloatArray, out: ByteArray): Int = VK_SUCCESS
    }

    private companion object {
        const val VK_SUCCESS = 0
        const val VK_ERROR_OUT_OF_DEVICE_MEMORY = -2
        const val VK_ERROR_INITIALIZATION_FAILED = -3
        const val VK_ERROR_DEVICE_LOST = -4
        const val VK_ERROR_INCOMPATIBLE_DRIVER = -9
        const val VK_ERROR_SURFACE_LOST_KHR = -1000000000
    }
}
