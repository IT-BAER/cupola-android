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
 * Without the sensor, [start] does nothing and look-around stays at rest.
 */
internal class OrientationSensor(
    private val manager: SensorManager?,
    private val onOrientation: (Orientation) -> Unit,
) : SensorEventListener {
    private val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
    private val lock = Any()
    private var wanted = false
    private var registered = false

    fun start(renderer: NativeRenderer, surfaceId: Int) {
        val manager = manager ?: return
        val sensor = sensor ?: return
        synchronized(lock) { wanted = true }
        renderer.updateScheduler(surfaceId) {
            synchronized(lock) {
                if (wanted && !registered) {
                    val handler = Handler(checkNotNull(Looper.myLooper()))
                    registered = manager.registerListener(this@OrientationSensor, sensor, SensorManager.SENSOR_DELAY_GAME, handler)
                }
            }
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
        if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR) onOrientation(RotationVector.fromSensorValues(event.values))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}
