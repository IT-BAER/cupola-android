package com.baer.cupola

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.baer.cupola.core.render.FrameInputs
import com.baer.cupola.core.render.scene.Exposure
import com.baer.cupola.core.render.scene.SceneInputs
import com.baer.cupola.wallpaper.RenderSettings
import com.baer.cupola.wallpaper.RendererHostEntryPoint
import com.baer.cupola.wallpaper.gateSceneOverride
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.nio.ByteBuffer
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dev renders for render tuning (tools/gpr/dev-render.ps1): every gate in `dev/gates.json` ({"pNN": gate, ...}) of the
 * app's external files dir rendered as the live wallpaper shows it (default Settings effects, settled exposure, render
 * scale `-e scale`, default 0.6) at `-e w` x `-e h` into `dev/out/pNN.png`. Skipped when the file is absent.
 */
@RunWith(AndroidJUnit4::class)
class DevSceneRenderTest {
    @Test
    fun renderDevScenes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Made by the app: adb can push into it, while a folder made by the adb shell is unreadable for the app.
        val dir = File(context.getExternalFilesDir(null), "dev").apply { mkdirs() }
        val gates = File(dir, "gates.json")
        assumeTrue("no dev/gates.json", gates.isFile)
        val args = InstrumentationRegistry.getArguments()
        val w = args.getString("w")?.toInt() ?: 1560
        val h = args.getString("h")?.toInt() ?: 1440
        val scale = args.getString("scale")?.toFloat() ?: RenderSettings().maxRenderScale
        val out = File(dir, "out").apply { mkdirs() }
        val host = EntryPointAccessors.fromApplication(context, RendererHostEntryPoint::class.java).rendererHost()
        val renderer = host.acquire()
        try {
            val all = JSONObject(gates.readText())
            for (id in all.keys()) {
                val g = all.getJSONObject(id)
                val override = gateSceneOverride(
                    { if (g.has(it)) g.getDouble(it) else null },
                    { if (g.has(it)) g.getString(it) else null },
                )
                val pose = checkNotNull(SceneInputs.buildPose(override)) { "$id: gate needs time and position" }
                val effects = (pose.effects and FrameInputs.EFFECTS_ALL.inv()) or RenderSettings().effects
                val scene = pose.copy(effects = effects).let { it.copy(exposure = Exposure.steady(it)) }
                val inputs = FrameInputs.empty()
                scene.write(inputs)
                inputs[FrameInputs.RENDER_SCALE] = scale
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(renderer.renderOffscreen(w, h, inputs)))
                    File(out, "$id.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            host.release()
        }
    }
}
