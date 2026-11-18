package com.example.data.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.example.data.model.LocationTelemetry
import com.example.domain.GeoGrid
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

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

        var lastLocation: Location? = null
        var lastTimestampMs: Long = 0L

        fun emitLocation(location: Location) {
            val now = if (location.time > 0L) location.time else System.currentTimeMillis()
            val computedSpeedKmh = if (location.hasSpeed() && location.speed >= 0f) {
                location.speed * 3.6f
            } else {
                val prev = lastLocation
                val dtSec = ((now - lastTimestampMs) / 1000.0)
                if (prev != null && dtSec >= 0.5) {
                    val dist = GeoGrid.haversineMeters(
                        prev.latitude,
                        prev.longitude,
                        location.latitude,
                        location.longitude
                    )
                    ((dist / dtSec) * 3.6).toFloat().coerceAtMost(120f)
                } else {
                    0f
                }
            }
            lastLocation = location
            lastTimestampMs = now

            trySend(
                LocationTelemetry(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    speedKmh = computedSpeedKmh,
                    accuracyMeters = location.accuracy,
                    hasFix = true,
                    hasPermission = true,
                    lastUpdatedAt = now
                )
            )
        }

        fusedClient.lastLocation.addOnSuccessListener { cached ->
            if (cached != null) {
                emitLocation(cached)
            }
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1500L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.5f)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { emitLocation(it) }
            }
        }

        fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())

        awaitClose {
            fusedClient.removeLocationUpdates(callback)
        }
    }
}
