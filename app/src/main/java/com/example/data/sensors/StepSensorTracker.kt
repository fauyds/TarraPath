package com.example.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

sealed interface StepSensorEvent {
    data class CumulativeSteps(val totalSinceBoot: Long) : StepSensorEvent
    data object SingleStepDetected : StepSensorEvent
}

class StepSensorTracker(context: Context) {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    fun observeSteps(hasPermission: Boolean): Flow<StepSensorEvent> = callbackFlow {
        if (!hasPermission || sensorManager == null) {
            awaitClose { }
            return@callbackFlow
        }

        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val stepDetector = if (stepCounter == null) {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        } else null

        val activeSensor = stepCounter ?: stepDetector
        if (activeSensor == null) {
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER -> {
                        val value = event.values.firstOrNull()?.toLong() ?: return
                        trySend(StepSensorEvent.CumulativeSteps(value))
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        trySend(StepSensorEvent.SingleStepDetected)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sensorManager.registerListener(listener, activeSensor, SensorManager.SENSOR_DELAY_UI)

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }
}
