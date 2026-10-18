package com.baer.cupola.core.render.platform

import android.annotation.TargetApi
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.concurrent.Executor

/** Supplies thermal headroom with Android semantics (1.0 is severe throttling, NaN if unsupported). */
interface ThermalHeadroomSource {
    fun start()
    fun stop()
    fun current(): Float

    companion object {
        fun create(context: Context): ThermalHeadroomSource {
            val pm = context.getSystemService(PowerManager::class.java)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ListenerThermalHeadroomSource(pm)
            } else {
                PollingThermalHeadroomSource(pm)
            }
        }
    }
}

/** API 33-35: the platform rate-limits getThermalHeadroom, so it is called at most once per second. */
class PollingThermalHeadroomSource(
    private val powerManager: PowerManager,
    private val forecastSeconds: Int = 0,
    private val elapsedNs: () -> Long = SystemClock::elapsedRealtimeNanos,
) : ThermalHeadroomSource {
    private var lastPollNs = Long.MIN_VALUE
    private var cached = Float.NaN

    override fun start() = Unit
    override fun stop() = Unit

    override fun current(): Float {
        val now = elapsedNs()
        if (lastPollNs == Long.MIN_VALUE || now - lastPollNs >= MIN_POLL_NS) {
            lastPollNs = now
            cached = powerManager.getThermalHeadroom(forecastSeconds)
        }
        return cached
    }

    private companion object {
        const val MIN_POLL_NS = 1_000_000_000L
    }
}

/** API 36+: PowerManager.addThermalHeadroomListener pushes the headroom. */
@TargetApi(Build.VERSION_CODES.BAKLAVA)
class ListenerThermalHeadroomSource(
    private val powerManager: PowerManager,
    private val executor: Executor = Executor { it.run() },
) : ThermalHeadroomSource {
    @Volatile private var latest = Float.NaN

    private val listener = PowerManager.OnThermalHeadroomChangedListener { headroom, _, _, _ ->
        latest = headroom
    }

    override fun start() = powerManager.addThermalHeadroomListener(executor, listener)
    override fun stop() = powerManager.removeThermalHeadroomListener(listener)
    override fun current(): Float = latest
}
