package com.baer.cupola.wallpaper

import android.util.Log
import kotlin.math.hypot

interface TapListener {
    fun onSingleTap(x: Float, y: Float)
    fun onDoubleTap(x: Float, y: Float)
}

/** Logs taps; the device check reads these lines. */
object LogTapListener : TapListener {
    override fun onSingleTap(x: Float, y: Float) {
        Log.i(LOG_TAG, "tap single x=$x y=$y")
    }

    override fun onDoubleTap(x: Float, y: Float) {
        Log.i(LOG_TAG, "tap double x=$x y=$y")
    }
}

internal const val LOG_TAG = "Cupola"

/**
 * Separates single from double taps. A second tap within [timeoutMs] (inclusive) and [slopPx] of the first
 * is a double tap; a single tap fires only once [tick] sees the timeout pass. Times are caller-supplied
 * milliseconds; the caller schedules [tick] at [deadlineMs]. Not thread-safe.
 */
class TapDetector(
    private val timeoutMs: Long,
    private val slopPx: Float,
    private val listener: TapListener,
) {
    private class Tap(val x: Float, val y: Float, val timeMs: Long)

    private var pending: Tap? = null

    /** Time at which [tick] will fire the pending single tap, null if none is pending. */
    val deadlineMs: Long?
        get() = pending?.let { it.timeMs + timeoutMs + 1 }

    fun onTap(x: Float, y: Float, nowMs: Long) {
        val first = pending
        pending = null
        if (first != null) {
            if (nowMs - first.timeMs <= timeoutMs && hypot(x - first.x, y - first.y) <= slopPx) {
                listener.onDoubleTap(x, y)
                return
            }
            listener.onSingleTap(first.x, first.y)
        }
        pending = Tap(x, y, nowMs)
    }

    fun tick(nowMs: Long) {
        val first = pending ?: return
        if (nowMs - first.timeMs > timeoutMs) {
            pending = null
            listener.onSingleTap(first.x, first.y)
        }
    }
}
