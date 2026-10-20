package com.baer.cupola.ui

import com.baer.cupola.feature.settings.ShareRenderer
import com.baer.cupola.wallpaper.RendererHost
import javax.inject.Inject

/** Offscreen render at the current inputs, from an own session so the look-around starts at rest. */
class RendererShareRenderer @Inject constructor(private val host: RendererHost) : ShareRenderer {
    override suspend fun renderRgba(width: Int, height: Int): ByteArray {
        val renderer = host.acquire()
        try {
            return renderer.renderOffscreen(width, height, host.detachedSession().nextFrameInputs())
        } finally {
            host.release()
        }
    }
}
