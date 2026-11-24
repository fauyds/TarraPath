package com.example.domain

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.data.model.ChunkCoord
import com.example.data.model.DistrictInfo
import com.example.data.model.MapPalette
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object GeoGrid {
    // ~250m x 250m geographic chunk dimensions
    const val CHUNK_SIZE_DEG_LAT = 0.00225
    const val CHUNK_SIZE_DEG_LON = 0.00285
    const val DISTRICT_SPAN = 10

    fun latLonToChunk(lat: Double, lon: Double): ChunkCoord {
        val cx = floor(lon / CHUNK_SIZE_DEG_LON).toInt()
        val cy = floor(lat / CHUNK_SIZE_DEG_LAT).toInt()
        return ChunkCoord(cx, cy)
    }

    fun chunkToDistrict(cx: Int, cy: Int): DistrictInfo {
        val dx = Math.floorDiv(cx, DISTRICT_SPAN)
        val dy = Math.floorDiv(cy, DISTRICT_SPAN)
        val displayNum = ((dx * 37 + dy * 17) and 0x7FFFFFFF) % 89 + 10
        return DistrictInfo(
            dx = dx,
            dy = dy,
            displayNumber = displayNum,
            unlockedCount = 0,
            totalCount = DISTRICT_SPAN * DISTRICT_SPAN
        )
    }

    fun chunkCenter(cx: Int, cy: Int): Pair<Double, Double> {
        val lat = (cy + 0.5) * CHUNK_SIZE_DEG_LAT
        val lon = (cx + 0.5) * CHUNK_SIZE_DEG_LON
        return lat to lon
    }

    /**
     * Returns all distinct chunks intersected along the straight segment from (startLat, startLon)
     * to (endLat, endLon) when distance is within [maxInterpolationMeters], or just the endpoint
     * chunk otherwise (e.g. cold-start teleport across distant cities).
     */
    fun chunksAlongSegment(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        maxInterpolationMeters: Double = 2500.0
    ): List<ChunkCoord> {
        val endChunk = latLonToChunk(endLat, endLon)
        val distMeters = haversineMeters(startLat, startLon, endLat, endLon)
        if (distMeters <= 1.0 || distMeters > maxInterpolationMeters) {
            return listOf(endChunk)
        }
        val startChunk = latLonToChunk(startLat, startLon)
        if (startChunk == endChunk) {
            return listOf(endChunk)
        }
        val steps = kotlin.math.ceil(distMeters / 35.0).toInt().coerceIn(2, 250)
        val seen = LinkedHashMap<String, ChunkCoord>()
        for (i in 0..steps) {
            val t = i.toDouble() / steps.toDouble()
            val lat = startLat + (endLat - startLat) * t
            val lon = startLon + (endLon - startLon) * t
            val coord = latLonToChunk(lat, lon)
            seen[coord.key] = coord
        }
        seen[endChunk.key] = endChunk
        return seen.values.toList()
    }

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            String.format("%.2f km", meters / 1000.0)
        }
    }

    fun formatDurationExact(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            hours > 0 -> String.format("%d hr %02d min %02d sec", hours, minutes, seconds)
            minutes > 0 -> String.format("%d min %02d sec", minutes, seconds)
            else -> String.format("%d sec", seconds)
        }
    }

    fun formatTimerClock(millis: Long): String {
        val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    fun deriveDefaultMapPalette(
        colorScheme: ColorScheme,
        isDark: Boolean,
        easyMode: Boolean = false
    ): MapPalette {
        val land = if (isDark) Color(0xFF1A1D21) else Color(0xFFF4F1EA)
        val road = if (isDark) Color(0xFF343A42) else Color(0xFFFFFFFF)
        val water = if (isDark) Color(0xFF163242) else Color(0xFFAAD3DF)

        val baseFog = if (isDark) Color(0xFF0E1013) else Color.White
        val fog = if (easyMode) {
            baseFog.copy(alpha = if (isDark) 0.40f else 0.42f)
        } else {
            baseFog
        }

        val gridStroke = colorScheme.primary.copy(alpha = if (isDark) 0.42f else 0.34f)
        val route = colorScheme.primary

        return MapPalette(
            id = if (isDark) -2L else -1L,
            name = if (isDark) "Dark Map" else "Light Map",
            isBuiltIn = true,
            landColor = land.toArgb().toLong() and 0xFFFFFFFFL,
            roadColor = road.toArgb().toLong() and 0xFFFFFFFFL,
            waterColor = water.toArgb().toLong() and 0xFFFFFFFFL,
            fogColor = fog.toArgb().toLong() and 0xFFFFFFFFL,
            gridStrokeColor = gridStroke.toArgb().toLong() and 0xFFFFFFFFL,
            routeColor = route.toArgb().toLong() and 0xFFFFFFFFL
        )
    }

    fun deriveMonetMapPalette(colorScheme: ColorScheme, isDark: Boolean): MapPalette =
        deriveDefaultMapPalette(colorScheme = colorScheme, isDark = isDark, easyMode = false)
}
