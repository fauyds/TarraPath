package com.example.data.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import com.example.data.model.LocationTelemetry
import com.example.domain.GeoGrid
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class SpeedFixSample(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val reportedSpeedMps: Float?,
    val speedAccuracyMps: Float?,
    val elapsedRealtimeMs: Long
)

object SpeedEstimator {
    private const val MAX_REASONABLE_SPEED_KMH = 160f
    private const val NOISE_FLOOR_KMH = 1.1f

    fun computeSpeedKmh(
        current: SpeedFixSample,
        previous: SpeedFixSample?,
        previousSmoothedKmh: Float
    ): Float {
        // Reject speed calculations when fix accuracy is too coarse (cell/Wi-Fi drift)
        if (current.accuracyMeters > 45f) {
            return if (previousSmoothedKmh < 1.5f) 0f else (previousSmoothedKmh * 0.5f)
        }

        val dtSec = if (previous != null) {
            (current.elapsedRealtimeMs - previous.elapsedRealtimeMs) / 1000.0
        } else {
            0.0
        }
        val distMeters = if (previous != null) {
            GeoGrid.haversineMeters(
                previous.latitude,
                previous.longitude,
                current.latitude,
                current.longitude
            )
        } else {
            0.0
        }

        val instantSpeedKmh: Float = when {
            current.reportedSpeedMps != null &&
                !current.reportedSpeedMps.isNaN() &&
                current.reportedSpeedMps >= 0f -> {
                val rawKmh = current.reportedSpeedMps * 3.6f
                val poorSpeedAccuracy = current.speedAccuracyMps != null &&
                    !current.speedAccuracyMps.isNaN() &&
                    current.speedAccuracyMps > 2.5f &&
                    rawKmh < 6.0f
                val stationaryWobble = previous != null &&
                    dtSec in 0.5..6.0 &&
                    distMeters < 1.0 &&
                    current.accuracyMeters > 12f &&
                    rawKmh < 4.5f

                when {
                    poorSpeedAccuracy || stationaryWobble || rawKmh < NOISE_FLOOR_KMH -> 0f
                    else -> rawKmh.coerceAtMost(MAX_REASONABLE_SPEED_KMH)
                }
            }

            previous != null &&
                dtSec in 0.5..12.0 &&
                current.accuracyMeters <= 25f &&
                previous.accuracyMeters <= 25f -> {
                // Only infer speed from position delta when movement clearly exceeds combined jitter
                val minMovementMeters = maxOf(2.0, (current.accuracyMeters + previous.accuracyMeters) * 0.22)
                if (distMeters >= minMovementMeters) {
                    val derivedKmh = ((distMeters / dtSec) * 3.6).toFloat()
                    if (derivedKmh < NOISE_FLOOR_KMH) 0f
                    else derivedKmh.coerceAtMost(MAX_REASONABLE_SPEED_KMH)
                } else {
                    0f
                }
            }

            else -> 0f
        }

        val smoothed = if (previous == null) {
            instantSpeedKmh
        } else {
            previousSmoothedKmh * 0.35f + instantSpeedKmh * 0.65f
        }

        return if (instantSpeedKmh == 0f && smoothed < NOISE_FLOOR_KMH) {
            0f
        } else {
            smoothed.coerceIn(0f, MAX_REASONABLE_SPEED_KMH)
        }
    }
}

class LocationTracker(context: Context) {
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context.applicationContext)

    @SuppressLint("MissingPermission")
    fun observeLocationUpdates(hasPermission: Boolean): Flow<LocationTelemetry> = callbackFlow {
        if (!hasPermission) {
            trySend(LocationTelemetry(hasFix = false, hasPermission = false))
            awaitClose { }
            return@callbackFlow
        }

        trySend(LocationTelemetry(hasFix = false, hasPermission = true))

        var previousSample: SpeedFixSample? = null
        var smoothedSpeedKmh = 0f
        var lastFixReceivedRealtimeMs = 0L
        var lastEmittedTelemetry: LocationTelemetry? = null

        fun Location.toSample(): SpeedFixSample {
            val elapsedMs = if (elapsedRealtimeNanos > 0L) {
                elapsedRealtimeNanos / 1_000_000L
            } else {
                SystemClock.elapsedRealtime()
            }
            val speedMps = if (hasSpeed() && !speed.isNaN() && speed >= 0f) speed else null
            val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasSpeedAccuracy()) {
                speedAccuracyMetersPerSecond
            } else {
                null
            }
            return SpeedFixSample(
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracy,
                reportedSpeedMps = speedMps,
                speedAccuracyMps = speedAcc,
                elapsedRealtimeMs = elapsedMs
            )
        }

        fun emitLocation(location: Location, isCached: Boolean) {
            // Do not let an old cached fix overwrite a live GPS update
            if (isCached && previousSample != null) return

            val sample = location.toSample()
            val prev = previousSample
            if (prev != null && sample.elapsedRealtimeMs < prev.elapsedRealtimeMs) return

            val isStaleCached = isCached &&
                (SystemClock.elapsedRealtime() - sample.elapsedRealtimeMs) > 5_000L

            smoothedSpeedKmh = if (isStaleCached) {
                0f
            } else {
                SpeedEstimator.computeSpeedKmh(
                    current = sample,
                    previous = prev,
                    previousSmoothedKmh = smoothedSpeedKmh
                )
            }
            previousSample = sample
            lastFixReceivedRealtimeMs = SystemClock.elapsedRealtime()

            val wallTime = if (location.time > 0L) location.time else System.currentTimeMillis()
            val telemetry = LocationTelemetry(
                latitude = location.latitude,
                longitude = location.longitude,
                speedKmh = smoothedSpeedKmh,
                accuracyMeters = location.accuracy,
                hasFix = true,
                hasPermission = true,
                lastUpdatedAt = wallTime
            )
            lastEmittedTelemetry = telemetry
            trySend(telemetry)
        }

        // Watchdog resets speed to 0.0 km/h if no new movement fix arrives for > 4s
        val watchdogJob = launch {
            while (isActive) {
                delay(1500L)
                val latest = lastEmittedTelemetry
                val elapsedSinceFix = SystemClock.elapsedRealtime() - lastFixReceivedRealtimeMs
                if (latest != null && latest.speedKmh > 0f && elapsedSinceFix > 4_000L) {
                    smoothedSpeedKmh = 0f
                    val zeroed = latest.copy(
                        speedKmh = 0f,
                        lastUpdatedAt = System.currentTimeMillis()
                    )
                    lastEmittedTelemetry = zeroed
                    trySend(zeroed)
                }
            }
        }

        fusedClient.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) {
                emitLocation(cached, isCached = true)
            }
        }

        // Keep cadence continuous even when user pauses/stops so speed returns cleanly to 0.0 km/h
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateDelayMillis(1500L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { emitLocation(it, isCached = false) }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose {
            watchdogJob.cancel()
            fusedClient.removeLocationUpdates(callback)
        }
    }
}
