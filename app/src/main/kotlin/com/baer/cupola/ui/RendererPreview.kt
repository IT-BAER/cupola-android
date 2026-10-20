package com.baer.cupola.ui

import android.view.Surface
import com.baer.cupola.core.render.NativeRenderer
import com.baer.cupola.feature.settings.PreviewRenderer
import com.baer.cupola.wallpaper.EngineState
import com.baer.cupola.wallpaper.RenderSession
import com.baer.cupola.wallpaper.RendererHost
import javax.inject.Inject
import javax.inject.Singleton

/** Live preview as one more session on the shared renderer, like a wallpaper engine in preview mode. Main thread only. */
@Singleton
class RendererPreview @Inject constructor(private val host: RendererHost) : PreviewRenderer {
    private class Active(val renderer: NativeRenderer, val id: Int, val session: RenderSession)

    private var active: Active? = null

    override fun attach(surface: Surface) {
        detach()
        val renderer = host.acquire()
        val (id, session) = host.openSession()
        session.surface = surface
        if (renderer.attachSurface(id, surface) < 0) {
            session.surface = null
            host.closeSession(id)
            host.release()
            return
        }
        host.engineVisible(true)
        active = Active(renderer, id, session).also(::push)
    }

    override fun resize(width: Int, height: Int) {
        val a = active ?: return
        a.renderer.resizeSurface(a.id, width, height)
        push(a)
    }

    override fun detach() {
        val a = active ?: return
        active = null
        a.renderer.detachSurface(a.id)
        a.session.surface = null
        host.engineVisible(false)
        host.closeSession(a.id)
        host.release()
    }

    private fun push(a: Active) {
        a.renderer.updateScheduler(a.id) { a.session.apply(EngineState(visible = true, preview = true), this) }
    }
}
