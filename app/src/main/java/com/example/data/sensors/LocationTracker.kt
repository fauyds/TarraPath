package com.example.data.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
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
import com.google.android.gms.tasks.CancellationTokenSource
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

open class LocationTracker(context: Context) {
    private val appContext: Context = context.applicationContext
    private val fusedClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)

    @SuppressLint("MissingPermission")
    open fun observeLocationUpdates(hasPermission: Boolean): Flow<LocationTelemetry> = callbackFlow {
        if (!hasPermission) {
            trySend(LocationTelemetry(hasFix = false, hasPermission = false))
            awaitClose { }
            return@callbackFlow
        }

        trySend(LocationTelemetry(hasFix = false, hasPermission = true))

        val driftFilter = GpsDriftFilter()
        var previousSample: SpeedFixSample? = null
        var smoothedSpeedKmh = 0f
        var lastValidBearingDeg: Float? = null
        var lastFixReceivedRealtimeMs = 0L
        var lastEmittedTelemetry: LocationTelemetry? = null
        var emittedProvisionalFix = false

        fun Location.toElapsedMs(): Long = if (elapsedRealtimeNanos > 0L) {
            elapsedRealtimeNanos / 1_000_000L
        } else {
            SystemClock.elapsedRealtime()
        }

        fun emitLocation(location: Location, isCached: Boolean) {
            // Do not let an old cached fix overwrite a live GPS update
            if (isCached && previousSample != null) return

            val elapsedMs = location.toElapsedMs()
            val prev = previousSample
            if (prev != null && elapsedMs < prev.elapsedRealtimeMs) return

            val speedMps = if (location.hasSpeed() && !location.speed.isNaN() && location.speed >= 0f) {
                location.speed
            } else {
                null
            }
            val speedAcc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
                location.speedAccuracyMetersPerSecond
            } else {
                null
            }
            val rawBearing = if (location.hasBearing() && !location.bearing.isNaN()) {
                location.bearing
            } else {
                null
            }
            val wallTime = if (location.time > 0L) location.time else System.currentTimeMillis()

            val filtered = driftFilter.process(
                RawGpsFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = location.accuracy,
                    reportedSpeedMps = speedMps,
                    speedAccuracyMps = speedAcc,
                    bearingDegrees = rawBearing,
                    elapsedRealtimeMs = elapsedMs,
                    wallTimeMs = wallTime,
                    isCached = isCached
                )
            )
            if (filtered == null) {
                // Before high-confidence lock arrives, show a provisional puck for coarse cold-start fixes
                // instead of staying stuck on "GPS Sync" indoors or under tall buildings.
                val cachedAgeMs = SystemClock.elapsedRealtime() - elapsedMs
                val allowProvisional = previousSample == null &&
                    !emittedProvisionalFix &&
                    !location.accuracy.isNaN() &&
                    location.accuracy in 1f..85f &&
                    (!isCached || cachedAgeMs <= 30 * 60_000L)
                if (allowProvisional) {
                    emittedProvisionalFix = true
                    val provisional = LocationTelemetry(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speedKmh = 0f,
                        accuracyMeters = location.accuracy,
                        bearingDegrees = null,
                        headingDegrees = null,
                        hasFix = true,
                        hasPermission = true,
                        lastUpdatedAt = wallTime
                    )
                    lastEmittedTelemetry = provisional
                    trySend(provisional)
                }
                return
            }

            val filteredSample = SpeedFixSample(
                latitude = filtered.latitude,
                longitude = filtered.longitude,
                accuracyMeters = filtered.accuracyMeters,
                reportedSpeedMps = speedMps,
                speedAccuracyMps = speedAcc,
                elapsedRealtimeMs = elapsedMs
            )

            val isStaleCached = isCached &&
                (SystemClock.elapsedRealtime() - elapsedMs) > 5_000L

            if (isStaleCached || !filtered.acceptedForMovement) {
                smoothedSpeedKmh = 0f
            } else {
                smoothedSpeedKmh = SpeedEstimator.computeSpeedKmh(
                    current = filteredSample,
                    previous = prev,
                    previousSmoothedKmh = smoothedSpeedKmh
                )

                val hardwareBearing = if (rawBearing != null && smoothedSpeedKmh >= 2.0f) {
                    HeadingEstimator.normalizeDegrees(rawBearing)
                } else {
                    null
                }
                val derivedBearing = if (hardwareBearing == null && prev != null && smoothedSpeedKmh >= 2.0f) {
                    HeadingEstimator.computeCourseBearingDegrees(
                        prevLat = prev.latitude,
                        prevLon = prev.longitude,
                        curLat = filteredSample.latitude,
                        curLon = filteredSample.longitude
                    )
                } else {
                    null
                }
                val candidateBearing = hardwareBearing ?: derivedBearing
                if (candidateBearing != null) {
                    lastValidBearingDeg = HeadingEstimator.smoothAngle(
                        previousDegrees = lastValidBearingDeg,
                        newDegrees = candidateBearing,
                        alpha = 0.45f
                    )
                }
                previousSample = filteredSample
            }

            if (previousSample == null) {
                previousSample = filteredSample
            }
            lastFixReceivedRealtimeMs = SystemClock.elapsedRealtime()

            val telemetry = LocationTelemetry(
                latitude = filtered.latitude,
                longitude = filtered.longitude,
                speedKmh = smoothedSpeedKmh,
                accuracyMeters = filtered.accuracyMeters,
                bearingDegrees = lastValidBearingDeg,
                headingDegrees = lastValidBearingDeg,
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

        val currentLocCancellation = CancellationTokenSource()

        // 1. Query cached fused location
        fusedClient.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) {
                emitLocation(cached, isCached = true)
            } else {
                // Fallback to platform LocationManager providers if Play Services cache is empty
                runCatching {
                    val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                    val candidates = listOfNotNull(
                        lm?.getLastKnownLocation(LocationManager.GPS_PROVIDER),
                        lm?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    )
                    val best = candidates.maxByOrNull { it.elapsedRealtimeNanos }
                    if (best != null) {
                        emitLocation(best, isCached = true)
                    }
                }
            }
        }

        // 2. Actively request an immediate fresh high-accuracy fix for cold start
        fusedClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, currentLocCancellation.token)
            .addOnSuccessListener { fresh ->
                if (fresh != null) {
                    emitLocation(fresh, isCached = false)
                }
            }

        // Keep cadence continuous even in background/pocket so sectors unlock reliably
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMaxUpdateDelayMillis(1500L)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val fixes = result.locations
                if (fixes.isNotEmpty()) {
                    val ordered = if (fixes.size > 1) {
                        fixes.sortedBy { it.elapsedRealtimeNanos }
                    } else {
                        fixes
                    }
                    for (loc in ordered) {
                        emitLocation(loc, isCached = false)
                    }
                } else {
                    result.lastLocation?.let { emitLocation(it, isCached = false) }
                }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose {
            currentLocCancellation.cancel()
            watchdogJob.cancel()
            fusedClient.removeLocationUpdates(callback)
        }
    }
}
