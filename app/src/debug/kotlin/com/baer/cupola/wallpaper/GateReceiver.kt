package com.baer.cupola.wallpaper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * `am broadcast -n com.baer.cupola.debug/com.baer.cupola.wallpaper.GateReceiver --es cmd load --ei iterations N`
 * Commands: load, trace (--es arg start|stop), stats (--es arg reset|dump).
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
            "stats" -> when (arg) {
                "reset" -> Gate.resetStats()
                "dump" -> Gate.dumpStats()
                else -> Log.w("CupolaGate", "gate bad stats arg=$arg")
            }
            else -> Log.w("CupolaGate", "gate bad cmd=$cmd")
        }
    }
}
