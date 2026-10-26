package com.baer.cupola.wallpaper

import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.baer.cupola.core.render.NativeRenderer
import com.baer.cupola.core.render.scene.SceneGeometry
import com.baer.cupola.core.render.scene.SceneOverride
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.nio.ByteBuffer
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlin.concurrent.thread

/**
 * `am broadcast -n com.baer.cupola.debug/com.baer.cupola.wallpaper.GateReceiver --es cmd load --ei iterations N`
 * Commands: load, trace (--es arg start|stop), stats (--es arg reset|dump), freeze (--es arg on|off),
 * fault (--es arg orbit|surface|clear), capture (--es arg NAME [--ei w --ei h], or --es arg diff --es a A.png --es b B.png
 * [--ei threshold N]; files in getExternalFilesDir/captures), scene (--es arg dump|clear, or any of: --es time 2026-03-20T22:00:00Z; --ef lat --ef lon --ef alt_km for the ISS
 * position, geodetic deg and km; --ef az --ef el camera direction in deg from north / above the local horizon at
 * the ISS, needs the position; --ef fov field of view on the long screen axis in deg), inputs (every session's last
 * frame inputs by name and the texture-set mask with the slots on their fallback), wallpaper (--es arg clear: the home
 * screen gets the system default static wallpaper; Cupola comes back only through the picker).
 */
class GateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val arg = intent.getStringExtra("arg")
        when (val cmd = intent.getStringExtra("cmd")) {
            "load" -> Gate.setLoad(intent.getIntExtra("iterations", 0))
            "trace" -> when (arg) {
                "start" -> Gate.startTrace()
                "stop" -> Gate.stopTrace()
                else -> Log.w("CupolaGate", "gate bad trace arg=$arg")
            }
            "scene" -> if (arg == "dump") Gate.dumpScenes() else try {
                Gate.setSceneOverride(if (arg == "clear") null else sceneOverride(intent))
            } catch (e: DateTimeParseException) {
                Log.w("CupolaGate", "gate bad scene time: ${e.message}")
            }
            "freeze" -> when (arg) {
                "on" -> Gate.setFrozen(true)
                "off" -> Gate.setFrozen(false)
                else -> Log.w("CupolaGate", "gate bad freeze arg=$arg")
            }
            "fault" -> when (arg) {
                "orbit" -> Gate.setOrbitFault(true)
                "surface" -> withRenderer(context) { renderer -> loseSurfaces(renderer) }
                "clear" -> Gate.setOrbitFault(false)
                else -> Log.w("CupolaGate", "gate bad fault arg=$arg")
            }
            "capture" -> {
                val dir = File(context.getExternalFilesDir(null), "captures").apply { mkdirs() }
                when (arg) {
                    "diff" -> background { diff(dir, intent) }
                    else -> withRenderer(context) { renderer ->
                        capture(renderer, dir, arg ?: "capture", intent.getIntExtra("w", 1080), intent.getIntExtra("h", 2400))
                    }
                }
            }
            "inputs" -> {
                Gate.dumpInputs()
                withRenderer(context) { renderer -> Log.i("CupolaGate", Gate.textureLine(renderer.textureSetMask())) }
            }
            "stats" -> when (arg) {
                "reset" -> Gate.resetStats()
                "dump" -> Gate.dumpStats()
                else -> Log.w("CupolaGate", "gate bad stats arg=$arg")
            }
            "wallpaper" -> when (arg) {
                "clear" -> background {
                    WallpaperManager.getInstance(context).clear(WallpaperManager.FLAG_SYSTEM)
                    Log.i("CupolaGate", "gate wallpaper cleared")
                }
                else -> Log.w("CupolaGate", "gate bad wallpaper arg=$arg")
            }
            else -> Log.w("CupolaGate", "gate bad cmd=$cmd")
        }
    }

    /** Off the main thread: offscreen renders and attach wait for the render thread. */
    private fun background(block: () -> Unit) {
        val pending = goAsync()
        thread(name = "CupolaGate") {
            try {
                block()
            } catch (e: Exception) {
                Log.w("CupolaGate", "gate command failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private fun withRenderer(context: Context, block: (NativeRenderer) -> Unit) = background {
        val host = EntryPointAccessors.fromApplication(context.applicationContext, GateEntryPoint::class.java).host()
        val renderer = host.acquire()
        try {
            block(renderer)
        } finally {
            host.release()
        }
    }

    /** Renders each session's last frame inputs at full resolution to `<name>-<id>.png`. */
    private fun capture(renderer: NativeRenderer, dir: File, name: String, width: Int, height: Int) {
        for ((id, session) in Gate.sessions()) {
            val inputs = session.lastFrameInputs() ?: continue
            val file = File(dir, "$name-$id.png")
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(renderer.renderOffscreen(width, height, inputs)))
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encode failed" } }
            } finally {
                bitmap.recycle()
            }
            Log.i("CupolaGate", "gate capture id=$id size=${width}x$height file=$file")
        }
    }

    /** `--es a <file> --es b <file>` inside the captures folder, optional `--ei threshold N`. */
    private fun diff(dir: File, intent: Intent) {
        val a = intent.getStringExtra("a")
        val b = intent.getStringExtra("b")
        if (a == null || b == null) {
            Log.w("CupolaGate", "gate diff needs a and b")
            return
        }
        val result = ImageDiff.of(pixels(File(dir, a)), pixels(File(dir, b)), intent.getIntExtra("threshold", 0))
        Log.i("CupolaGate", result.line(a, b))
    }

    private fun pixels(file: File): IntArray {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "cannot decode $file" }
        try {
            return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        } finally {
            bitmap.recycle()
        }
    }

    /** Detaches and re-attaches every session's surface, as after a surface loss, then re-applies its engine state. */
    private fun loseSurfaces(renderer: NativeRenderer) {
        for ((id, session) in Gate.sessions()) {
            val surface = session.surface ?: continue
            renderer.detachSurface(id)
            val result = renderer.attachSurface(id, surface)
            if (result >= 0) renderer.updateScheduler(id) { session.apply(session.engineState, this) }
            Log.i("CupolaGate", "gate fault surface id=$id reattach=$result")
        }
    }

    private fun sceneOverride(intent: Intent): SceneOverride {
        fun float(name: String) = if (intent.hasExtra(name)) intent.getFloatExtra(name, 0f).toDouble() else null
        val lat = float("lat")
        val lon = float("lon")
        val position = if (lat != null && lon != null) {
            Frames.geodeticToEcef(Geodetic(Math.toRadians(lat), Math.toRadians(lon), float("alt_km") ?: DEFAULT_ALT_KM))
        } else {
            null
        }
        val az = float("az")
        val el = float("el")
        val forward = if (position != null && az != null && el != null) SceneGeometry.directionFromAzEl(position, az, el) else null
        return SceneOverride(
            instant = intent.getStringExtra("time")?.let(Instant::parse),
            positionEcefKm = position,
            cameraForwardEcef = forward,
            fovLongDeg = float("fov"),
        )
    }

    private companion object {
        const val DEFAULT_ALT_KM = 420.0
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface GateEntryPoint {
    fun host(): RendererHost
}
