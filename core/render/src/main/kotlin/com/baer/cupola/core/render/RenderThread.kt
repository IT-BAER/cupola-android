package com.baer.cupola.core.render

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Choreographer

/**
 * The single render thread of the process: a Looper thread paced by its own Choreographer.
 * [onFrame] runs on this thread for every requested vsync.
 */
class RenderThread(private val onFrame: (frameTimeNanos: Long) -> Unit) {
    private var thread: HandlerThread? = null

    @Volatile private var handler: Handler? = null

    // Render thread only.
    private var choreographer: Choreographer? = null
    private var frameRequested = false
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos ->
        frameRequested = false
        onFrame(frameTimeNanos)
    }

    val isCurrent: Boolean
        get() = handler?.looper == Looper.myLooper()

    @Synchronized
    fun start() {
        check(thread == null) { "render thread already started" }
        val started = HandlerThread(NAME).apply { start() }
        thread = started
        handler = Handler(started.looper)
        postAndWait { choreographer = Choreographer.getInstance() }
    }

    /** Stops frame callbacks and joins the thread; tasks posted before quit still run. */
    fun quit() {
        check(!isCurrent) { "quit must not be called on the render thread" }
        val stopping = synchronized(this) { thread?.also { thread = null } } ?: return
        postAndWait {
            choreographer?.removeFrameCallback(frameCallback)
            choreographer = null
            frameRequested = false
        }
        handler = null
        stopping.quitSafely()
        stopping.join()
    }

    /** Returns false if the thread is not running. */
    fun post(block: () -> Unit): Boolean = handler?.post(block) ?: false

    /** Runs [block] on the render thread and waits; runs inline when already on it. */
    fun <T> postAndWait(block: () -> T): T =
        if (isCurrent) block() else BlockingCall.run(post = { task -> post { task.run() } }, block = block)

    /** Schedules one [onFrame] call at the next vsync. Render thread only. */
    fun requestFrame() {
        check(isCurrent) { "requestFrame off the render thread" }
        val current = choreographer ?: return
        if (!frameRequested) {
            frameRequested = true
            current.postFrameCallback(frameCallback)
        }
    }

    companion object {
        const val NAME = "CupolaRender"
    }
}
