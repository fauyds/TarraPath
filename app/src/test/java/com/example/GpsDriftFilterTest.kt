package com.example

import com.example.data.sensors.GpsDriftFilter
import com.example.data.sensors.RawGpsFix
import com.example.domain.GeoGrid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsDriftFilterTest {

    @Test
    fun ignoresLowAccuracyFixesAndUpgradesFromCoarseColdStart() {
        val filter = GpsDriftFilter()

        // 50m cell-tower fix rejected even on cold start
        assertNull(
            filter.process(
                RawGpsFix(
                    latitude = 37.77500,
                    longitude = -122.41900,
                    accuracyMeters = 50f,
                    elapsedRealtimeMs = 1_000L,
                    wallTimeMs = 1_000L
                )
            )
        )

        // Moderate 22m cold start initializes fix cleanly
        val cold = filter.process(
            RawGpsFix(
                latitude = 37.77510,
                longitude = -122.41910,
                accuracyMeters = 22f,
                elapsedRealtimeMs = 2_000L,
                wallTimeMs = 2_000L
            )
        )
        assertNotNull(cold)
        assertTrue(cold!!.isInitialFix)

        // Sharp 5m GPS lock immediately upgrades without dragging old 22m offset
        val crisp = filter.process(
            RawGpsFix(
                latitude = 37.77500,
                longitude = -122.41900,
                accuracyMeters = 5f,
                elapsedRealtimeMs = 3_000L,
                wallTimeMs = 3_000L
            )
        )
        assertNotNull(crisp)
        assertEquals(37.77500, crisp!!.latitude, 1e-6)
        assertEquals(-122.41900, crisp.longitude, 1e-6)

        // Noisy 24m fix after lock is completely ignored
        assertNull(
            filter.process(
                RawGpsFix(
                    latitude = 37.77550,
                    longitude = -122.41850,
                    accuracyMeters = 24f,
                    elapsedRealtimeMs = 4_000L,
                    wallTimeMs = 4_000L
                )
            )
        )
    }

    @Test
    fun ignoresMicroMovementsWhenStandingStill() {
        val filter = GpsDriftFilter()
        val anchorLat = 37.77500
        val anchorLon = -122.41900

        val start = filter.process(
            RawGpsFix(
                latitude = anchorLat,
                longitude = anchorLon,
                accuracyMeters = 6f,
                reportedSpeedMps = 0f,
                elapsedRealtimeMs = 1_000L,
                wallTimeMs = 1_000L
            )
        )
        assertNotNull(start)

        // ~2.2m stationary wobble (0.00002 deg lat ~ 2.2m) -> pinned cleanly
        val jitter = filter.process(
            RawGpsFix(
                latitude = anchorLat + 0.00002,
                longitude = anchorLon + 0.000015,
                accuracyMeters = 8f,
                reportedSpeedMps = 0.2f,
                elapsedRealtimeMs = 2_000L,
                wallTimeMs = 2_000L
            )
        )
        assertNotNull(jitter)
        assertFalse(jitter!!.acceptedForMovement)
        assertEquals(anchorLat, jitter.latitude, 1e-7)
        assertEquals(anchorLon, jitter.longitude, 1e-7)
    }

    @Test
    fun rejectsUnrealisticWalkingSpeedSpikes() {
        val filter = GpsDriftFilter()
        filter.process(
            RawGpsFix(
                latitude = 37.77500,
                longitude = -122.41900,
                accuracyMeters = 5f,
                reportedSpeedMps = 1.3f,
                elapsedRealtimeMs = 1_000L,
                wallTimeMs = 1_000L
            )
        )

        // 55m jump in 1 second (~198 km/h) rejected
        assertNull(
            filter.process(
                RawGpsFix(
                    latitude = 37.77550,
                    longitude = -122.41900,
                    accuracyMeters = 6f,
                    reportedSpeedMps = 1.4f,
                    elapsedRealtimeMs = 2_000L,
                    wallTimeMs = 2_000L
                )
            )
        )
    }

    @Test
    fun smoothsLateralJitterWhilePreservingSharpTurns() {
        val filter = GpsDriftFilter()
        val startLat = 37.77500
        val startLon = -122.41900

        filter.process(
            RawGpsFix(
                latitude = startLat,
                longitude = startLon,
                accuracyMeters = 6f,
                reportedSpeedMps = 1.4f,
                elapsedRealtimeMs = 1_000L,
                wallTimeMs = 1_000L
            )
        )

        // Walk North (~4.4m step) with +3.0m East jitter
        val rawJitterLon = startLon + 0.000034 // ~3.0m East at 37.77N
        val stepNorth = filter.process(
            RawGpsFix(
                latitude = startLat + 0.00004,
                longitude = rawJitterLon,
                accuracyMeters = 6f,
                reportedSpeedMps = 1.4f,
                elapsedRealtimeMs = 4_000L,
                wallTimeMs = 4_000L
            )
        )
        assertNotNull(stepNorth)
        assertTrue(stepNorth!!.acceptedForMovement)
        // Smoothed longitude should stay noticeably closer to the true centerline than rawJitterLon
        assertTrue(stepNorth.longitude < rawJitterLon)
        val dampedEastMeters = GeoGrid.haversineMeters(startLat, startLon, startLat, stepNorth.longitude)
        assertTrue("Expected lateral jitter damped < 2.2m, got $dampedEastMeters", dampedEastMeters < 2.2)

        // Continue North one more step so prevMoveVector points clearly North
        val north2 = filter.process(
            RawGpsFix(
                latitude = startLat + 0.00008,
                longitude = startLon,
                accuracyMeters = 5f,
                reportedSpeedMps = 1.4f,
                elapsedRealtimeMs = 7_000L,
                wallTimeMs = 7_000L
            )
        )!!

        // Now make a sharp 90-degree East turn (~6m East)
        val targetTurnLon = north2.longitude + 0.000068
        val turnEast = filter.process(
            RawGpsFix(
                latitude = north2.latitude,
                longitude = targetTurnLon,
                accuracyMeters = 5f,
                reportedSpeedMps = 1.4f,
                elapsedRealtimeMs = 11_000L,
                wallTimeMs = 11_000L
            )
        )!!
        assertTrue(turnEast.acceptedForMovement)
        // Sharp turn boost should pull at least 70% toward the new East leg immediately
        val progressFraction = (turnEast.longitude - north2.longitude) / (targetTurnLon - north2.longitude)
        assertTrue("Expected sharp turn gain >= 0.70, got $progressFraction", progressFraction >= 0.70)
    }
}
