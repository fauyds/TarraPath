package com.example

import androidx.compose.material3.lightColorScheme
import com.example.domain.GeoGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoGridTest {
    @Test
    fun chunkAndDistrictMapping_isDeterministic() {
        val c1 = GeoGrid.latLonToChunk(37.7749, -122.4194)
        val c2 = GeoGrid.latLonToChunk(37.7749, -122.4194)
        assertEquals(c1, c2)

        val d1 = GeoGrid.chunkToDistrict(c1.cx, c1.cy)
        assertTrue(d1.displayNumber in 10..99)
        assertEquals(100, d1.totalCount)
    }

    @Test
    fun formatDurationExact_formatsMinutesAndSeconds() {
        val millis = (14 * 60 + 20) * 1000L
        assertEquals("14 min 20 sec", GeoGrid.formatDurationExact(millis))
        assertEquals("14:20", GeoGrid.formatTimerClock(millis))
    }

    @Test
    fun haversineMeters_computesReasonableWalkingDistance() {
        val dist = GeoGrid.haversineMeters(37.7749, -122.4194, 37.7758, -122.4194)
        assertTrue("Expected ~100m, got $dist", dist in 90.0..115.0)
    }

    @Test
    fun deriveDefaultMapPalette_followsLightDarkAndEasyMode() {
        val lightClassic = GeoGrid.deriveDefaultMapPalette(lightColorScheme(), isDark = false, easyMode = false)
        val darkClassic = GeoGrid.deriveDefaultMapPalette(lightColorScheme(), isDark = true, easyMode = false)
        assertNotEquals(lightClassic.landColor, darkClassic.landColor)
        assertEquals(0xFFFFFFFFL, lightClassic.fogColor)
        assertEquals(1.0f, lightClassic.fog.alpha, 0.01f)
        assertEquals(1.0f, darkClassic.fog.alpha, 0.01f)

        val lightEasy = GeoGrid.deriveDefaultMapPalette(lightColorScheme(), isDark = false, easyMode = true)
        val darkEasy = GeoGrid.deriveDefaultMapPalette(lightColorScheme(), isDark = true, easyMode = true)
        assertEquals(1.0f, lightEasy.fog.red, 0.01f)
        assertEquals(1.0f, lightEasy.fog.green, 0.01f)
        assertEquals(1.0f, lightEasy.fog.blue, 0.01f)
        assertTrue("Expected transparent fog in Light Easy Mode", lightEasy.fog.alpha < 0.5f)
        assertTrue("Expected transparent fog in Dark Easy Mode", darkEasy.fog.alpha < 0.5f)
    }

    @Test
    fun chunkSize_isApproximately250Meters() {
        val heightMeters = GeoGrid.haversineMeters(
            37.7749,
            -122.4194,
            37.7749 + GeoGrid.CHUNK_SIZE_DEG_LAT,
            -122.4194
        )
        val widthMeters = GeoGrid.haversineMeters(
            37.7749,
            -122.4194,
            37.7749,
            -122.4194 + GeoGrid.CHUNK_SIZE_DEG_LON
        )
        assertTrue("Expected ~250m height, got $heightMeters", heightMeters in 245.0..255.0)
        assertTrue("Expected ~250m width, got $widthMeters", widthMeters in 245.0..255.0)
    }

    @Test
    fun chunksAlongSegment_unlocksIntermediateSectorsAndSkipsTeleports() {
        val startLat = 37.7749
        val startLon = -122.4194
        // Move ~3 chunks North (~750m)
        val endLat = startLat + GeoGrid.CHUNK_SIZE_DEG_LAT * 3.0
        val chunks = GeoGrid.chunksAlongSegment(startLat, startLon, endLat, startLon)
        assertEquals(4, chunks.size)

        // Teleport > 2500m away should only return endpoint chunk, not interpolate across whole city
        val farLat = startLat + 0.05 // ~5.5 km
        val farChunks = GeoGrid.chunksAlongSegment(startLat, startLon, farLat, startLon)
        assertEquals(1, farChunks.size)
        assertEquals(GeoGrid.latLonToChunk(farLat, startLon), farChunks.first())
    }
}
