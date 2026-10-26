package com.baer.cupola.wallpaper

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import com.baer.cupola.core.render.NativeRenderer

/**
 * Game rotation vector feed delivered on the render thread. Registration runs on that thread (the Handler is
 * built from its Looper); [lock] makes a late registration lose against a [stop] from any thread.
 * Without the sensor, [start] does nothing and look-around stays at rest. [setEnabled] (false at look-around
 * strength 0) keeps it released while visible.
 */
internal class OrientationSensor(
    private val manager: SensorManager?,
    private val onOrientation: (orientation: Orientation, timestampNs: Long) -> Unit,
) : SensorEventListener {
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val lock = Any()
    private var wanted = false
    private var enabled = true
    private var registered = false

    fun start(renderer: NativeRenderer, surfaceId: Int) {
        if (manager == null || sensor == null) return
        synchronized(lock) { wanted = true }
        renderer.updateScheduler(surfaceId) { synchronized(lock) { syncLocked() } }
    }

    /** Render thread only. */
    fun setEnabled(on: Boolean) {
        synchronized(lock) {
            enabled = on
            syncLocked()
        }
    }

    // Holds [lock] on the render thread, whose Looper delivers the events.
    private fun syncLocked() {
        val manager = manager ?: return
        val sensor = sensor ?: return
        if (wanted && enabled && !registered) {
            val handler = Handler(checkNotNull(Looper.myLooper()))
            registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
        } else if (!(wanted && enabled) && registered) {
            manager.unregisterListener(this)
            registered = false
        }
    }

    fun stop() {
        synchronized(lock) {
            wanted = false
            if (registered) {
                manager?.unregisterListener(this)
                registered = false
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) onOrientation(RotationVector.fromSensorValues(event.values), event.timestamp)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
