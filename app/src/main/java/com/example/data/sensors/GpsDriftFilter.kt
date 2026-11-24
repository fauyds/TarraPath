package com.example.data.sensors

import com.example.domain.GeoGrid
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot

data class RawGpsFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val reportedSpeedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDegrees: Float? = null,
    val elapsedRealtimeMs: Long,
    val wallTimeMs: Long,
    val isCached: Boolean = false
)

data class FilteredGpsPosition(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float,
    val acceptedForMovement: Boolean,
    val isInitialFix: Boolean
)

/**
 * Filters GPS drift, coarse/noisy accuracy spikes, stationary micro-jitter, and impossible
 * walking teleports while applying turn-aware scalar Kalman smoothing to walking coordinates.
 */
class GpsDriftFilter {
    private var initialized = false
    private var hasHighConfidenceLock = false

    private var estLat = 0.0
    private var estLon = 0.0
    private var varianceSqMeters = 25.0
    private var lastFilterTimeMs = 0L

    // Previous motion vector in local meter space (dx East, dy North) for sharp turn detection
    private var prevMoveDx = 0.0
    private var prevMoveDy = 0.0
    private var hasPrevMoveVector = false

    // Tracks consecutive rejected speed-spike fixes so genuine relocation can recover
    private var consecutiveSpikeCount = 0
    private var lastSpikeLat = 0.0
    private var lastSpikeLon = 0.0
    private var lastSpikeTimeMs = 0L
    private var lastHighAccuracyTimeMs = 0L

    fun process(fix: RawGpsFix): FilteredGpsPosition? {
        if (fix.latitude.isNaN() || fix.longitude.isNaN()) return null
        if (fix.accuracyMeters.isNaN() || fix.accuracyMeters <= 0f) return null

        // 1. Initial fix gating (allow moderate cold-start accuracy up to 35m, reject coarse cell/Wi-Fi fixes)
        if (!initialized) {
            val coldStartLimit = if (fix.isCached) MAX_CACHED_INITIAL_ACCURACY_METERS else MAX_INITIAL_ACCURACY_METERS
            if (fix.accuracyMeters > coldStartLimit) return null

            estLat = fix.latitude
            estLon = fix.longitude
            val sigma = fix.accuracyMeters.coerceAtLeast(3.5f).toDouble()
            varianceSqMeters = sigma * sigma
            lastFilterTimeMs = fix.elapsedRealtimeMs
            lastHighAccuracyTimeMs = fix.elapsedRealtimeMs
            initialized = true
            hasHighConfidenceLock = fix.accuracyMeters <= MAX_LIVE_ACCURACY_METERS
            return FilteredGpsPosition(
                latitude = estLat,
                longitude = estLon,
                accuracyMeters = fix.accuracyMeters,
                acceptedForMovement = true,
                isInitialFix = true
            )
        }

        // Ignore out-of-order fixes
        if (fix.elapsedRealtimeMs < lastFilterTimeMs) return null

        // 2. Reject coarse cell/Wi-Fi fixes (>45m) and momentary low-accuracy jitter (>15m)
        // when crisp fixes have been arriving recently; allow moderate pocket/background fixes (<=45m)
        // after a crisp-fix drought so background sector unlocking never starves.
        if (fix.accuracyMeters > MAX_FALLBACK_ACCURACY_METERS) {
            return null
        }
        val isCrispFix = fix.accuracyMeters <= MAX_LIVE_ACCURACY_METERS
        if (isCrispFix) {
            lastHighAccuracyTimeMs = fix.elapsedRealtimeMs
        } else if (hasHighConfidenceLock &&
            (fix.elapsedRealtimeMs - lastHighAccuracyTimeMs) < HIGH_ACCURACY_DROUGHT_MS
        ) {
            return null
        }

        // Upgrade cleanly if cold-start was coarse (>15m) and we just received our first sharp GPS fix (<=15m)
        if (!hasHighConfidenceLock && isCrispFix) {
            estLat = fix.latitude
            estLon = fix.longitude
            val sigma = fix.accuracyMeters.coerceAtLeast(3.5f).toDouble()
            varianceSqMeters = sigma * sigma
            lastFilterTimeMs = fix.elapsedRealtimeMs
            hasHighConfidenceLock = true
            hasPrevMoveVector = false
            consecutiveSpikeCount = 0
            return FilteredGpsPosition(
                latitude = estLat,
                longitude = estLon,
                accuracyMeters = fix.accuracyMeters,
                acceptedForMovement = true,
                isInitialFix = false
            )
        }

        val dtSec = ((fix.elapsedRealtimeMs - lastFilterTimeMs) / 1000.0).coerceAtLeast(0.2)
        val distFromAnchor = GeoGrid.haversineMeters(estLat, estLon, fix.latitude, fix.longitude)

        // 3. Reject unrealistically fast single-fix speed spikes for walking (e.g. urban canyon multipath jumps),
        // while allowing 3 consecutive physically consistent fixes (stationary OR moving along a path) to re-anchor.
        val maxPlausibleJumpMeters = maxOf(
            MIN_SPIKE_ESCAPE_METERS,
            dtSec * MAX_WALKING_IMPLIED_SPEED_MPS + fix.accuracyMeters * 0.5
        )
        if (distFromAnchor > maxPlausibleJumpMeters) {
            val dtSpikeSec = ((fix.elapsedRealtimeMs - lastSpikeTimeMs) / 1000.0).coerceAtLeast(0.2)
            val stepBetweenSpikes = if (consecutiveSpikeCount > 0) {
                GeoGrid.haversineMeters(lastSpikeLat, lastSpikeLon, fix.latitude, fix.longitude)
            } else {
                Double.MAX_VALUE
            }
            val consistentWithPrevSpike = consecutiveSpikeCount > 0 &&
                stepBetweenSpikes <= maxOf(25.0, dtSpikeSec * MAX_RECOVERY_SPEED_MPS)
            consecutiveSpikeCount = if (consistentWithPrevSpike) consecutiveSpikeCount + 1 else 1
            lastSpikeLat = fix.latitude
            lastSpikeLon = fix.longitude
            lastSpikeTimeMs = fix.elapsedRealtimeMs

            // Re-anchor once 3 consecutive consistent fixes confirm genuine relocation
            if (consecutiveSpikeCount >= 3) {
                estLat = fix.latitude
                estLon = fix.longitude
                val sigma = fix.accuracyMeters.coerceAtLeast(3.5f).toDouble()
                varianceSqMeters = sigma * sigma
                lastFilterTimeMs = fix.elapsedRealtimeMs
                hasPrevMoveVector = false
                consecutiveSpikeCount = 0
                return FilteredGpsPosition(
                    latitude = estLat,
                    longitude = estLon,
                    accuracyMeters = fix.accuracyMeters,
                    acceptedForMovement = true,
                    isInitialFix = false
                )
            }
            return null
        }
        consecutiveSpikeCount = 0

        // 4. Suppress stationary micro-jitter (ignore jumps smaller than ~3.5-5.0m when standing still)
        val reportedSpeedMps = fix.reportedSpeedMps?.takeIf { !it.isNaN() && it >= 0f }
        val activelyWalkingByDoppler = reportedSpeedMps != null && reportedSpeedMps >= 0.85f
        val maxDriftCeiling = if (isCrispFix) MAX_STATIONARY_DRIFT_METERS else MAX_FALLBACK_STATIONARY_DRIFT_METERS
        val stationaryJitterThreshold = maxOf(
            MIN_STATIONARY_DRIFT_METERS,
            minOf(maxDriftCeiling, fix.accuracyMeters * 0.45)
        )
        val requiredMovementMeters = if (activelyWalkingByDoppler) {
            ACTIVE_WALKING_MIN_STEP_METERS
        } else {
            stationaryJitterThreshold
        }

        if (distFromAnchor < requiredMovementMeters) {
            // Keep smoothed coordinate pinned cleanly without adding track/marker jitter
            lastFilterTimeMs = fix.elapsedRealtimeMs
            return FilteredGpsPosition(
                latitude = estLat,
                longitude = estLon,
                accuracyMeters = fix.accuracyMeters,
                acceptedForMovement = false,
                isInitialFix = false
            )
        }

        // 5. Light Kalman-style smoothing with sharp-turn awareness
        val clampedDt = dtSec.coerceIn(0.2, 15.0)
        val processSpeedMps = if (activelyWalkingByDoppler) 2.2 else 1.45
        val predictedVarianceSq = varianceSqMeters + clampedDt * processSpeedMps * processSpeedMps

        val measSigma = fix.accuracyMeters.coerceIn(3.5f, MAX_FALLBACK_ACCURACY_METERS).toDouble()
        val measurementVarianceSq = measSigma * measSigma
        val rawGain = predictedVarianceSq / (predictedVarianceSq + measurementVarianceSq)

        // Compute meter displacement vector to detect real turns vs straight-line jitter
        val cosLat = cos(Math.toRadians(estLat)).coerceAtLeast(0.1)
        val dx = (fix.longitude - estLon) * 111_320.0 * cosLat
        val dy = (fix.latitude - estLat) * 111_320.0
        val stepLen = hypot(dx, dy)

        val isSharpTurn = if (hasPrevMoveVector && stepLen >= 4.0) {
            val prevLen = hypot(prevMoveDx, prevMoveDy)
            if (prevLen >= 2.0) {
                val dot = (dx * prevMoveDx + dy * prevMoveDy) / (stepLen * prevLen)
                val angleDeg = Math.toDegrees(acos(dot.coerceIn(-1.0, 1.0)))
                angleDeg >= TURN_BOOST_ANGLE_DEG
            } else {
                false
            }
        } else {
            false
        }

        val gain = if (isSharpTurn) {
            rawGain.coerceIn(TURN_MIN_GAIN, MAX_KALMAN_GAIN)
        } else {
            rawGain.coerceIn(MIN_KALMAN_GAIN, BASE_MAX_KALMAN_GAIN)
        }

        val nextLat = estLat + gain * (fix.latitude - estLat)
        val nextLon = estLon + gain * (fix.longitude - estLon)

        val smoothedDx = (nextLon - estLon) * 111_320.0 * cosLat
        val smoothedDy = (nextLat - estLat) * 111_320.0
        if (hypot(smoothedDx, smoothedDy) >= 1.5) {
            prevMoveDx = smoothedDx
            prevMoveDy = smoothedDy
            hasPrevMoveVector = true
        }

        estLat = nextLat
        estLon = nextLon
        varianceSqMeters = (1.0 - gain) * predictedVarianceSq
        lastFilterTimeMs = fix.elapsedRealtimeMs

        return FilteredGpsPosition(
            latitude = estLat,
            longitude = estLon,
            accuracyMeters = fix.accuracyMeters,
            acceptedForMovement = true,
            isInitialFix = false
        )
    }

    companion object {
        const val MAX_LIVE_ACCURACY_METERS = 15.0f
        const val MAX_FALLBACK_ACCURACY_METERS = 45.0f
        const val HIGH_ACCURACY_DROUGHT_MS = 5_000L
        const val MAX_INITIAL_ACCURACY_METERS = 35.0f
        const val MAX_CACHED_INITIAL_ACCURACY_METERS = 25.0f
        const val MIN_STATIONARY_DRIFT_METERS = 3.5
        const val MAX_STATIONARY_DRIFT_METERS = 5.0
        const val MAX_FALLBACK_STATIONARY_DRIFT_METERS = 12.0
        const val ACTIVE_WALKING_MIN_STEP_METERS = 2.2
        const val MAX_WALKING_IMPLIED_SPEED_MPS = 6.5 // ~23.4 km/h cap against GPS teleports
        const val MAX_RECOVERY_SPEED_MPS = 45.0 // ~162 km/h max continuous transit recovery
        const val MIN_SPIKE_ESCAPE_METERS = 10.0

        private const val MIN_KALMAN_GAIN = 0.38
        private const val BASE_MAX_KALMAN_GAIN = 0.68
        private const val TURN_MIN_GAIN = 0.72
        private const val MAX_KALMAN_GAIN = 0.80
        private const val TURN_BOOST_ANGLE_DEG = 35.0
    }
}
