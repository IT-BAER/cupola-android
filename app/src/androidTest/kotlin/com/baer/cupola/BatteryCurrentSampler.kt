package com.baer.cupola

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a check: the battery current sampler for the wallpaper battery delta (TARGETS row "Battery"). Skipped unless
 * started with `-e powerSeconds N [-e powerName NAME]`; writes one line per second to files/power/NAME.csv of the
 * app under test. The instrumentation keeps this process sampling while another wallpaper is set.
 */
@RunWith(AndroidJUnit4::class)
class BatteryCurrentSampler {
    @Test
    fun sample() {
        val args = InstrumentationRegistry.getArguments()
        val seconds = args.getString("powerSeconds")?.toLongOrNull() ?: 0L
        assumeTrue("needs -e powerSeconds N", seconds > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val battery = context.getSystemService(BatteryManager::class.java)
        val dir = File(context.getExternalFilesDir(null), "power").apply { mkdirs() }
        File(dir, "${args.getString("powerName") ?: "power"}.csv").bufferedWriter().use { out ->
            out.write("utc_ms,current_ua,charge_uah,voltage_mv\n")
            val end = SystemClock.elapsedRealtime() + seconds * 1000
            var next = SystemClock.elapsedRealtime()
            while (next < end) {
                val voltage = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1
                val current = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                val charge = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                out.write("${System.currentTimeMillis()},$current,$charge,$voltage\n")
                out.flush()
                next += 1000
                SystemClock.sleep((next - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
        }
    }
}
