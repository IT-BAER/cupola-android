package com.baer.cupola.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.baer.cupola.core.render.scene.SceneGeometry
import com.baer.cupola.core.render.scene.SceneOverride
import com.baer.cupola.orbit.frames.Frames
import com.baer.cupola.orbit.frames.Geodetic
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * `am broadcast -n com.baer.cupola.debug/com.baer.cupola.wallpaper.GateReceiver --es cmd load --ei iterations N`
 * Commands: load, trace (--es arg start|stop), stats (--es arg reset|dump), freeze (--es arg on|off),
 * fault (--es arg orbit|clear), scene (--es arg dump|clear, or any of: --es time 2026-03-20T22:00:00Z; --ef lat --ef lon --ef alt_km for the ISS
 * position, geodetic deg and km; --ef az --ef el camera direction in deg from north / above the local horizon at
 * the ISS, needs the position; --ef fov field of view on the long screen axis in deg).
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
                "clear" -> Gate.setOrbitFault(false)
                else -> Log.w("CupolaGate", "gate bad fault arg=$arg")
            }
            "stats" -> when (arg) {
                "reset" -> Gate.resetStats()
                "dump" -> Gate.dumpStats()
                else -> Log.w("CupolaGate", "gate bad stats arg=$arg")
            }
            else -> Log.w("CupolaGate", "gate bad cmd=$cmd")
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
