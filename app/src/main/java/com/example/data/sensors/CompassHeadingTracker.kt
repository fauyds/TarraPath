package com.example.data.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import com.example.domain.GeoGrid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object HeadingEstimator {

    fun normalizeDegrees(degrees: Float): Float {
        if (degrees.isNaN() || degrees.isInfinite()) return 0f
        val mod = degrees % 360f
        return if (mod < 0f) mod + 360f else mod
    }

    fun shortestAngleDelta(fromDegrees: Float, toDegrees: Float): Float {
        return ((toDegrees - fromDegrees + 540f) % 360f) - 180f
    }

    fun smoothAngle(previousDegrees: Float?, newDegrees: Float, alpha: Float = 0.25f): Float {
        val target = normalizeDegrees(newDegrees)
        if (previousDegrees == null) return target
        val delta = shortestAngleDelta(previousDegrees, target)
        return normalizeDegrees(previousDegrees + delta * alpha.coerceIn(0.05f, 1f))
    }

    fun computeCourseBearingDegrees(
        prevLat: Double,
        prevLon: Double,
        curLat: Double,
        curLon: Double,
        minDistanceMeters: Double = 2.0
    ): Float? {
        if (GeoGrid.haversineMeters(prevLat, prevLon, curLat, curLon) < minDistanceMeters) {
            return null
        }
        val lat1 = Math.toRadians(prevLat)
        val lat2 = Math.toRadians(curLat)
        val dLon = Math.toRadians(curLon - prevLon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        if (x == 0.0 && y == 0.0) return null
        val bearingDeg = Math.toDegrees(atan2(y, x)).toFloat()
        return normalizeDegrees(bearingDeg)
    }

    fun resolveHeadingDegrees(
        compassDegrees: Float?,
        gpsBearingDegrees: Float?,
        speedKmh: Float
    ): Float? {
        return when {
            speedKmh >= 10.0f && gpsBearingDegrees != null && !gpsBearingDegrees.isNaN() ->
                normalizeDegrees(gpsBearingDegrees)
            compassDegrees != null && !compassDegrees.isNaN() ->
                normalizeDegrees(compassDegrees)
            gpsBearingDegrees != null && !gpsBearingDegrees.isNaN() ->
                normalizeDegrees(gpsBearingDegrees)
            else -> null
        }
    }
}

class CompassHeadingTracker(context: Context) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    fun observeHeadingDegrees(): Flow<Float> = callbackFlow {
        val manager = sensorManager
        if (manager == null) {
            awaitClose { }
            return@callbackFlow
        }

        val rotationSensor = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: manager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val accelSensor = if (rotationSensor == null) manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        val magSensor = if (rotationSensor == null) manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null

        if (rotationSensor == null && (accelSensor == null || magSensor == null)) {
            awaitClose { }
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val orientationAngles = FloatArray(3)
        val lastAccel = FloatArray(3)
        val lastMag = FloatArray(3)
        var hasAccel = false
        var hasMag = false

        var smoothedHeading: Float? = null
        var lastEmittedHeading: Float? = null
        var lastEmitRealtimeMs = 0L

        fun displayRotationOffsetDegrees(): Float {
            val rotation = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
            return when (rotation) {
                Surface.ROTATION_90 -> 90f
                Surface.ROTATION_180 -> 180f
                Surface.ROTATION_270 -> 270f
                else -> 0f
            }
        }

        fun publishRawAzimuth(azimuthDeg: Float) {
            val adjusted = HeadingEstimator.normalizeDegrees(azimuthDeg + displayRotationOffsetDegrees())
            val next = HeadingEstimator.smoothAngle(smoothedHeading, adjusted, alpha = 0.24f)
            smoothedHeading = next

            val now = SystemClock.elapsedRealtime()
            val prevEmitted = lastEmittedHeading
            val delta = if (prevEmitted != null) abs(HeadingEstimator.shortestAngleDelta(prevEmitted, next)) else 180f
            if (prevEmitted == null || (delta >= 2.0f && now - lastEmitRealtimeMs >= 80L)) {
                lastEmittedHeading = next
                lastEmitRealtimeMs = now
                trySend(next)
            }
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ROTATION_VECTOR,
                    Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientationAngles)
                        val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                        publishRawAzimuth(azimuthDeg)
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, lastAccel, 0, 3)
                        hasAccel = true
                        if (hasMag && SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                            SensorManager.getOrientation(rotationMatrix, orientationAngles)
                            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                            publishRawAzimuth(azimuthDeg)
                        }
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, lastMag, 0, 3)
                        hasMag = true
                        if (hasAccel && SensorManager.getRotationMatrix(rotationMatrix, null, lastAccel, lastMag)) {
                            SensorManager.getOrientation(rotationMatrix, orientationAngles)
                            val azimuthDeg = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                            publishRawAzimuth(azimuthDeg)
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (rotationSensor != null) {
            manager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            manager.registerListener(listener, accelSensor, SensorManager.SENSOR_DELAY_UI)
            manager.registerListener(listener, magSensor, SensorManager.SENSOR_DELAY_UI)
        }

        awaitClose {
            manager.unregisterListener(listener)
        }
    }
}
