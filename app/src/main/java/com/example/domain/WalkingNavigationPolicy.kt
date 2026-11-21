package com.example.domain

import com.example.data.local.OfflineRegionEntity
import com.example.data.model.LatLonPoint
import com.example.data.model.RoutingMode
import com.example.data.model.WalkingRouteState
import kotlin.math.cos
import kotlin.math.sqrt

data class BoundingBox(
    val southLat: Double,
    val westLon: Double,
    val northLat: Double,
    val eastLon: Double
) {
    fun contains(lat: Double, lon: Double): Boolean {
        return lat in southLat..northLat && lon in westLon..eastLon
    }
}

/**
 * Controls automatic walking route re-calculation throttling, live remaining-distance updates,
 * polyline trimming from the user's current position, and arrival auto-clearing.
 */
object WalkingNavigationPolicy {
    const val ARRIVAL_RADIUS_METERS = 25.0
    const val REROUTE_MIN_DISTANCE_METERS = 12.0
    const val REROUTE_MIN_INTERVAL_MS = 4_000L
    const val REROUTE_FAST_MOVE_DISTANCE_METERS = 25.0

    fun hasArrivedAtStart(
        currentLat: Double,
        currentLon: Double,
        targetLat: Double,
        targetLon: Double
    ): Boolean {
        return GeoGrid.haversineMeters(currentLat, currentLon, targetLat, targetLon) <= ARRIVAL_RADIUS_METERS
    }

    fun shouldRecalculateRoute(
        state: WalkingRouteState,
        currentLat: Double,
        currentLon: Double,
        activeMode: RoutingMode,
        nowMillis: Long
    ): Boolean {
        if (state.target == null) return false
        if (state.isCalculating) return false
        val prevLat = state.lastRoutedLat ?: return true
        val prevLon = state.lastRoutedLon ?: return true
        if (state.modeUsed != activeMode) return true
        if (state.routePoints.size < 2 && (nowMillis - state.lastRoutedAtMillis) >= REROUTE_MIN_INTERVAL_MS) {
            return true
        }

        val movedMeters = GeoGrid.haversineMeters(prevLat, prevLon, currentLat, currentLon)
        val elapsedMs = nowMillis - state.lastRoutedAtMillis

        if (movedMeters >= REROUTE_FAST_MOVE_DISTANCE_METERS) return true
        if (movedMeters >= REROUTE_MIN_DISTANCE_METERS && elapsedMs >= REROUTE_MIN_INTERVAL_MS) return true
        return false
    }

    /**
     * Estimates remaining walking distance cleanly on every GPS fix between full route refreshes
     * by projecting the user's current coordinate onto the closest segment of the active route.
     */
    fun computeRemainingAndTrimmedRoute(
        currentLat: Double,
        currentLon: Double,
        routePoints: List<LatLonPoint>,
        targetLat: Double,
        targetLon: Double
    ): Pair<Double, List<LatLonPoint>> {
        if (routePoints.size < 2) {
            val direct = GeoGrid.haversineMeters(currentLat, currentLon, targetLat, targetLon)
            return direct to listOf(
                LatLonPoint(currentLat, currentLon),
                LatLonPoint(targetLat, targetLon)
            )
        }

        val cosLat = cos(Math.toRadians(currentLat)).coerceAtLeast(0.1)
        val mPerLat = 111_320.0
        val mPerLon = 111_320.0 * cosLat

        var bestSegIdx = 0
        var bestT = 0.0
        var bestDistSq = Double.POSITIVE_INFINITY
        var bestSnapLat = routePoints[0].latitude
        var bestSnapLon = routePoints[0].longitude

        for (i in 0 until routePoints.lastIndex) {
            val a = routePoints[i]
            val b = routePoints[i + 1]
            val ax = (a.longitude - currentLon) * mPerLon
            val ay = (a.latitude - currentLat) * mPerLat
            val bx = (b.longitude - currentLon) * mPerLon
            val by = (b.latitude - currentLat) * mPerLat

            val abx = bx - ax
            val aby = by - ay
            val abLenSq = abx * abx + aby * aby
            val t = if (abLenSq <= 1e-6) 0.0 else (-(ax * abx + ay * aby) / abLenSq).coerceIn(0.0, 1.0)
            val px = ax + t * abx
            val py = ay + t * aby
            val distSq = px * px + py * py
            if (distSq < bestDistSq) {
                bestDistSq = distSq
                bestSegIdx = i
                bestT = t
                bestSnapLat = a.latitude + t * (b.latitude - a.latitude)
                bestSnapLon = a.longitude + t * (b.longitude - a.longitude)
            }
        }

        val trimmed = ArrayList<LatLonPoint>(routePoints.size - bestSegIdx + 2)
        trimmed.add(LatLonPoint(currentLat, currentLon))
        val snapToEndDist = GeoGrid.haversineMeters(
            bestSnapLat,
            bestSnapLon,
            routePoints[bestSegIdx + 1].latitude,
            routePoints[bestSegIdx + 1].longitude
        )
        val snapDistMeters = sqrt(bestDistSq)
        if (snapToEndDist > 2.0 && snapDistMeters > 2.0 && snapDistMeters <= 45.0) {
            trimmed.add(LatLonPoint(bestSnapLat, bestSnapLon))
        }
        for (idx in (bestSegIdx + 1) until routePoints.size) {
            trimmed.add(routePoints[idx])
        }

        var remaining = 0.0
        for (i in 1 until trimmed.size) {
            remaining += GeoGrid.haversineMeters(
                trimmed[i - 1].latitude,
                trimmed[i - 1].longitude,
                trimmed[i].latitude,
                trimmed[i].longitude
            )
        }
        return remaining to trimmed
    }

    fun boundingBoxForRadius(centerLat: Double, centerLon: Double, radiusMeters: Int): BoundingBox {
        val latDelta = radiusMeters / 111_320.0
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.1)
        val lonDelta = radiusMeters / (111_320.0 * cosLat)
        return BoundingBox(
            southLat = (centerLat - latDelta).coerceIn(-85.0, 85.0),
            westLon = (centerLon - lonDelta).coerceIn(-180.0, 180.0),
            northLat = (centerLat + latDelta).coerceIn(-85.0, 85.0),
            eastLon = (centerLon + lonDelta).coerceIn(-180.0, 180.0)
        )
    }

    fun OfflineRegionEntity.toBoundingBox(): BoundingBox = BoundingBox(
        southLat = southLat,
        westLon = westLon,
        northLat = northLat,
        eastLon = eastLon
    )

    fun OfflineRegionEntity.covers(lat: Double, lon: Double): Boolean {
        return toBoundingBox().contains(lat, lon)
    }
}
