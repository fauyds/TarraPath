package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.OfflineRegionEntity
import com.example.data.model.LatLonPoint
import com.example.data.model.RoutingMode
import com.example.data.model.WalkTarget
import com.example.data.model.WalkingRouteState
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.WalkingRouteService
import com.example.domain.OfflineRoadGraph
import com.example.domain.WalkingNavigationPolicy
import com.example.domain.WalkingNavigationPolicy.covers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WalkingRoutingTest {

    private fun sampleOverpassJson(): String = """
        {
          "version": 0.6,
          "elements": [
            { "type": "node", "id": 1, "lat": 37.77500, "lon": -122.41900 },
            { "type": "node", "id": 2, "lat": 37.77500, "lon": -122.41800 },
            { "type": "node", "id": 3, "lat": 37.77600, "lon": -122.41800 },
            { "type": "node", "id": 4, "lat": 37.77750, "lon": -122.41900 },
            { "type": "node", "id": 5, "lat": 37.77750, "lon": -122.41700 },
            { "type": "node", "id": 6, "lat": 37.77600, "lon": -122.41700 },
            { "type": "node", "id": 100, "lat": 37.77900, "lon": -122.41900 },
            { "type": "node", "id": 101, "lat": 37.77900, "lon": -122.41700 },
            {
              "type": "way",
              "id": 10,
              "nodes": [1, 2, 3],
              "tags": { "highway": "footway", "name": "Park Shortcut" }
            },
            {
              "type": "way",
              "id": 11,
              "nodes": [1, 4, 5, 6, 3],
              "tags": { "highway": "residential", "name": "Long Detour" }
            },
            {
              "type": "way",
              "id": 12,
              "nodes": [100, 101],
              "tags": { "highway": "motorway" }
            },
            {
              "type": "way",
              "id": 13,
              "nodes": [100, 101],
              "tags": { "highway": "residential", "foot": "no" }
            },
            {
              "type": "way",
              "id": 14,
              "nodes": [100, 101],
              "tags": { "highway": "service", "access": "private" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun offlineRoadGraph_filtersNonWalkableWaysRoundTripsBinaryAndFindsShortestAStarPath() {
        val parsed = OfflineRoadGraph.parseOverpassWalkGraph(sampleOverpassJson())
        // Nodes 100 & 101 are only referenced by motorway / foot=no / access=private ways -> excluded
        assertEquals(6, parsed.nodeCount)
        assertTrue(parsed.edgeCount > 0)

        // Binary serialization round-trip
        val bytesOut = ByteArrayOutputStream()
        parsed.writeTo(bytesOut)
        val reloaded = OfflineRoadGraph.readFrom(ByteArrayInputStream(bytesOut.toByteArray()))
        assertEquals(parsed.nodeCount, reloaded.nodeCount)
        assertEquals(parsed.edgeCount, reloaded.edgeCount)

        // Start slightly off node 1, end near node 3 -> should take Park Shortcut (1 -> 2 -> 3), ~200m, not detour (~600m)
        val route = reloaded.findWalkingRoute(
            startLat = 37.77502,
            startLon = -122.41898,
            endLat = 37.77598,
            endLon = -122.41799
        )
        assertNotNull(route)
        val result = route!!
        assertTrue("Expected at least 3 points, got ${result.points.size}", result.points.size >= 3)
        assertTrue("Expected short park path < 320m, got ${result.distanceMeters}", result.distanceMeters < 320.0)
        assertTrue(result.estimatedSeconds > 0L)

        // Same-edge shortcut projection
        val sameEdge = reloaded.findWalkingRoute(
            startLat = 37.77501,
            startLon = -122.41880,
            endLat = 37.77501,
            endLon = -122.41820
        )
        assertNotNull(sameEdge)
        assertTrue(sameEdge!!.distanceMeters in 40.0..70.0)

        // Out-of-bounds points beyond maxSnapMeters return null
        val tooFar = reloaded.findWalkingRoute(
            startLat = 38.5,
            startLon = -122.4,
            endLat = 37.77598,
            endLon = -122.41799
        )
        assertNull(tooFar)
    }

    @Test
    fun walkingRouteService_parsesOsrmValhallaAndGeoapifyWalkingResponses() {
        val osrmJson = """
            {
              "code": "Ok",
              "routes": [
                {
                  "distance": 412.5,
                  "duration": 298.0,
                  "geometry": {
                    "type": "LineString",
                    "coordinates": [[-122.4190, 37.7750], [-122.4180, 37.7750], [-122.4180, 37.7760]]
                  }
                }
              ]
            }
        """.trimIndent()
        val osrm = WalkingRouteService.parseOsrmFootJson(osrmJson)
        assertEquals(3, osrm.points.size)
        assertEquals(412.5, osrm.distanceMeters, 0.01)
        assertEquals(298L, osrm.estimatedSeconds)
        assertEquals("OSM Foot (Online)", osrm.providerLabel)

        val geoapifyJson = """
            {
              "type": "FeatureCollection",
              "features": [
                {
                  "properties": { "distance": 245.0, "time": 180.0 },
                  "geometry": {
                    "type": "MultiLineString",
                    "coordinates": [[[-122.4190, 37.7750], [-122.4185, 37.7755], [-122.4180, 37.7760]]]
                  }
                }
              ]
            }
        """.trimIndent()
        val geoapify = WalkingRouteService.parseGeoapifyRoutingJson(geoapifyJson)
        assertEquals(3, geoapify.points.size)
        assertEquals(245.0, geoapify.distanceMeters, 0.01)
        assertEquals(180L, geoapify.estimatedSeconds)

        // Polyline6 decode test for Valhalla
        // Encode two points around (37.775000, -122.419000) -> (37.776000, -122.418000)
        fun encodeValhalla6(pts: List<Pair<Double, Double>>): String {
            val out = StringBuilder()
            var prevLat = 0
            var prevLon = 0
            fun writeVal(v: Int) {
                var num = if (v < 0) (v shl 1).inv() else (v shl 1)
                while (num >= 0x20) {
                    out.append(((0x20 or (num and 0x1f)) + 63).toChar())
                    num = num shr 5
                }
                out.append((num + 63).toChar())
            }
            for ((lat, lon) in pts) {
                val iLat = Math.round(lat * 1e6).toInt()
                val iLon = Math.round(lon * 1e6).toInt()
                writeVal(iLat - prevLat)
                writeVal(iLon - prevLon)
                prevLat = iLat
                prevLon = iLon
            }
            return out.toString()
        }

        val shape = encodeValhalla6(listOf(37.7750 to -122.4190, 37.7760 to -122.4180))
        val valhallaJson = """
            {
              "trip": {
                "summary": { "length": 0.142, "time": 105.0 },
                "legs": [ { "shape": "$shape" } ]
              }
            }
        """.trimIndent()
        val valhalla = WalkingRouteService.parseValhallaJson(valhallaJson)
        assertEquals(2, valhalla.points.size)
        assertEquals(37.7750, valhalla.points[0].latitude, 0.0001)
        assertEquals(-122.4180, valhalla.points[1].longitude, 0.0001)
        assertEquals(142.0, valhalla.distanceMeters, 0.1)
    }

    @Test
    fun walkingNavigationPolicy_handlesArrivalThrottledReroutingAndRemainingTrimming() {
        val targetLat = 37.77600
        val targetLon = -122.41800

        // 18m away (< 25m threshold) -> arrived
        assertTrue(WalkingNavigationPolicy.hasArrivedAtStart(37.77615, -122.41800, targetLat, targetLon))
        // 40m away (> 25m threshold) -> not yet arrived
        assertFalse(WalkingNavigationPolicy.hasArrivedAtStart(37.77636, -122.41800, targetLat, targetLon))

        val target = WalkTarget.SavedPoint(1L, "Park Gate", targetLat, targetLon)
        val baseState = WalkingRouteState(
            target = target,
            routePoints = listOf(LatLonPoint(37.7750, -122.4180), LatLonPoint(37.7760, -122.4180)),
            modeUsed = RoutingMode.ONLINE,
            lastRoutedLat = 37.77500,
            lastRoutedLon = -122.41800,
            lastRoutedAtMillis = 10_000L
        )

        // Small jitter (3m) after 5s -> do NOT recalculate
        assertFalse(
            WalkingNavigationPolicy.shouldRecalculateRoute(
                state = baseState,
                currentLat = 37.775025,
                currentLon = -122.41800,
                activeMode = RoutingMode.ONLINE,
                nowMillis = 15_000L
            )
        )

        // 15m moved but only 2s elapsed -> wait until 4s interval passes
        assertFalse(
            WalkingNavigationPolicy.shouldRecalculateRoute(
                state = baseState,
                currentLat = 37.775135,
                currentLon = -122.41800,
                activeMode = RoutingMode.ONLINE,
                nowMillis = 12_000L
            )
        )

        // 15m moved and 5s elapsed -> recalculate
        assertTrue(
            WalkingNavigationPolicy.shouldRecalculateRoute(
                state = baseState,
                currentLat = 37.775135,
                currentLon = -122.41800,
                activeMode = RoutingMode.ONLINE,
                nowMillis = 15_000L
            )
        )

        // 30m moved (> 25m fast move threshold) even after 1s -> recalculate
        assertTrue(
            WalkingNavigationPolicy.shouldRecalculateRoute(
                state = baseState,
                currentLat = 37.77527,
                currentLon = -122.41800,
                activeMode = RoutingMode.ONLINE,
                nowMillis = 11_000L
            )
        )

        // Mode switched -> recalculate immediately
        assertTrue(
            WalkingNavigationPolicy.shouldRecalculateRoute(
                state = baseState,
                currentLat = 37.77500,
                currentLon = -122.41800,
                activeMode = RoutingMode.OFFLINE,
                nowMillis = 10_500L
            )
        )

        // Remaining distance & segment trimming
        val route = listOf(
            LatLonPoint(37.7740, -122.4180),
            LatLonPoint(37.7750, -122.4180),
            LatLonPoint(37.7760, -122.4180)
        )
        val (remaining, trimmed) = WalkingNavigationPolicy.computeRemainingAndTrimmedRoute(
            currentLat = 37.7752,
            currentLon = -122.4180,
            routePoints = route,
            targetLat = 37.7760,
            targetLon = -122.4180
        )
        // ~0.0008 deg lat remaining = ~89m
        assertTrue("Expected ~89m remaining, got $remaining", remaining in 75.0..105.0)
        // First passed node (37.7740) was trimmed away
        assertEquals(2, trimmed.size)

        val bbox = WalkingNavigationPolicy.boundingBoxForRadius(37.7750, -122.4180, 1000)
        val region = OfflineRegionEntity(
            name = "Test Area",
            centerLat = 37.7750,
            centerLon = -122.4180,
            radiusMeters = 1000,
            southLat = bbox.southLat,
            westLon = bbox.westLon,
            northLat = bbox.northLat,
            eastLon = bbox.eastLon,
            nodeCount = 6,
            edgeCount = 6,
            tileCount = 12,
            sizeBytes = 4096L,
            graphFileName = "test.bin",
            downloadedAt = 1L
        )
        assertTrue(region.covers(37.7755, -122.4182))
        assertFalse(region.covers(37.8500, -122.4180))
    }

    @Test
    fun preferences_persistRoutingModeCorrectly() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = UserPreferencesRepository(context)

        assertEquals(RoutingMode.ONLINE, prefs.mapDisplaySettings.first().routingMode)
        prefs.setRoutingMode(RoutingMode.OFFLINE)
        assertEquals(RoutingMode.OFFLINE, prefs.mapDisplaySettings.first().routingMode)
        prefs.setRoutingMode(RoutingMode.ONLINE)
        assertEquals(RoutingMode.ONLINE, prefs.mapDisplaySettings.first().routingMode)
    }
}
