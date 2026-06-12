package com.lunya.deerpeek

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(private val listener: Listener) : SensorEventListener {

    interface Listener {
        fun hearShake()
    }

    private var lastAcceleration = 0f
    private var currentAcceleration = 0f
    private var shakeThreshold = 12.0f // Чувствительность (выше = жестче)
    private var lastShakeTime: Long = 0

    init {
        currentAcceleration = SensorManager.GRAVITY_EARTH
        lastAcceleration = SensorManager.GRAVITY_EARTH
    }

    fun start(sensorManager: SensorManager) {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop(sensorManager: SensorManager) {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        lastAcceleration = currentAcceleration
        currentAcceleration = sqrt(x * x + y * y + z * z)
        
        val delta = currentAcceleration - lastAcceleration
        val absDelta = if (delta < 0) -delta else delta

        if (absDelta > shakeThreshold) {
            val now = System.currentTimeMillis()
            if (now - lastShakeTime > 2000) { // Коллдаун между детекциями тряски
                lastShakeTime = now
                listener.hearShake()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
