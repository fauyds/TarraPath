package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TerraPathDatabase
import com.example.data.model.LatLonPoint
import com.example.data.model.LocationTelemetry
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.TerraRepository
import com.example.data.sensors.LocationTracker
import com.example.data.sensors.StepSensorTracker
import com.example.domain.GeoGrid
import com.example.domain.RoadSegment
import com.example.domain.RoadSnapResult
import com.example.domain.RoadSnapper
import com.example.service.TrackingCoordinator
import com.example.ui.map.MvtTileParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoadSnapperTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: TerraPathDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TerraPathDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            testDispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun extractRoadSegmentsFromMvtTile_decodesWebMercatorCoordinatesAccurately() {
        fun varint(value: Int): ByteArray {
            val out = ByteArrayOutputStream()
            var v = value
            while ((v and 0x7F.inv()) != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v and 0x7F)
            return out.toByteArray()
        }

        fun zigZag(n: Int): Int = (n shl 1) xor (n shr 31)

        fun lengthDelimited(field: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(varint((field shl 3) or 2))
            out.write(varint(payload.size))
            out.write(payload)
            return out.toByteArray()
        }

        val z = 14
        val tx = 9502
        val ty = 5742
        val extent = 4096

        val layerOut = ByteArrayOutputStream()
        layerOut.write(lengthDelimited(1, "transportation".toByteArray(Charsets.UTF_8)))
        layerOut.write(lengthDelimited(3, "class".toByteArray(Charsets.UTF_8))) // key[0] = "class"
        // values[0] = "tertiary", values[1] = "rail"
        layerOut.write(lengthDelimited(4, lengthDelimited(1, "tertiary".toByteArray(Charsets.UTF_8))))
        layerOut.write(lengthDelimited(4, lengthDelimited(1, "rail".toByteArray(Charsets.UTF_8))))
        layerOut.write(varint((5 shl 3) or 0))
        layerOut.write(varint(extent))

        fun addLineFeature(valueIdx: Int, x0: Int, y0: Int, dx: Int, dy: Int) {
            val feat = ByteArrayOutputStream()
            val tags = ByteArrayOutputStream().apply {
                write(varint(0))
                write(varint(valueIdx))
            }
            feat.write(lengthDelimited(2, tags.toByteArray()))
            feat.write(varint((3 shl 3) or 0))
            feat.write(varint(2)) // LINESTRING
            val geom = ByteArrayOutputStream().apply {
                write(varint((1 shl 3) or 1)) // MoveTo count=1 -> 9
                write(varint(zigZag(x0)))
                write(varint(zigZag(y0)))
                write(varint((1 shl 3) or 2)) // LineTo count=1 -> 10
                write(varint(zigZag(dx)))
                write(varint(zigZag(dy)))
            }
            feat.write(lengthDelimited(4, geom.toByteArray()))
            layerOut.write(lengthDelimited(2, feat.toByteArray()))
        }

        addLineFeature(valueIdx = 0, x0 = 1024, y0 = 1024, dx = 1024, dy = 0)
        addLineFeature(valueIdx = 1, x0 = 512, y0 = 512, dx = 512, dy = 512) // excluded rail

        val tileBytes = lengthDelimited(3, layerOut.toByteArray())
        val decoded = MvtTileParser.parse(tileBytes)
        val segments = RoadSnapper.extractRoadSegmentsFromMvtTile(z, tx, ty, decoded)

        assertEquals(1, segments.size)
        val seg = segments.first()
        assertEquals("tertiary", seg.roadClass)

        // Verify exact forward Web Mercator correspondence for start point (1024/4096 = 0.25 into tile)
        val expectedLonStart = ((tx + 0.25) / (1 shl z) * 360.0) - 180.0
        val expectedLonEnd = ((tx + 0.50) / (1 shl z) * 360.0) - 180.0
        assertEquals(expectedLonStart, seg.startLon, 1e-6)
        assertEquals(expectedLonEnd, seg.endLon, 1e-6)
        assertEquals(seg.startLat, seg.endLat, 1e-6)
        // Lat also matches tile grid cleanly
        val fy = (1.0 - ln(tan(Math.toRadians(seg.startLat)) + 1.0 / cos(Math.toRadians(seg.startLat))) / PI) / 2.0 * (1 shl z)
        assertEquals(ty + 0.25, fy, 1e-5)
    }

    @Test
    fun snapPointToSegments_snapsOffsetPathToRoadAndLeavesOffRoadUntouched() {
        val baseLat = 47.0100
        val baseLon = 28.8600
        val northSouthRoad = RoadSegment(
            roadId = "ns_street",
            roadClass = "secondary",
            startLat = baseLat - 0.001,
            startLon = baseLon,
            endLat = baseLat + 0.001,
            endLon = baseLon
        )
        val eastWestCrossStreet = RoadSegment(
            roadId = "ew_street",
            roadClass = "minor",
            startLat = baseLat + 0.00018, // ~20m North
            startLon = baseLon - 0.001,
            endLat = baseLat + 0.00018,
            endLon = baseLon + 0.001
        )
        val segments = listOf(northSouthRoad, eastWestCrossStreet)

        // GPS point shifted ~18m West of northSouthRoad while walking North (heading 0 deg)
        val rawLat = baseLat + 0.00017
        val rawLon = baseLon - 0.00024 // ~18m West at 47N
        val snapped = RoadSnapper.snapPointToSegments(
            latitude = rawLat,
            longitude = rawLon,
            accuracyMeters = 14f,
            headingDegrees = 2f,
            speedKmh = 4.5f,
            segments = segments,
            lastMatchedRoadId = "ns_street"
        )
        assertTrue(snapped.snappedToRoad)
        assertEquals("ns_street", snapped.matchedRoadId)
        assertEquals(baseLon, snapped.longitude, 1e-6)
        assertEquals(rawLat, snapped.latitude, 1e-6)

        // Far off-road point (>85m away in a park from both roads) must NOT snap onto any road
        val offRoad = RoadSnapper.snapPointToSegments(
            latitude = baseLat - 0.0006, // ~67m South of ew_street end and south tip
            longitude = baseLon - 0.0011, // ~85m West of ns_street
            accuracyMeters = 12f,
            headingDegrees = 0f,
            speedKmh = 4.0f,
            segments = segments
        )
        assertFalse(offRoad.snappedToRoad)
        assertEquals(baseLon - 0.0011, offRoad.longitude, 1e-7)
    }

    @Test
    fun trackingCoordinator_snapsLiveTelemetryAndSavedRouteWithoutGpsSyncFlicker() = runBlocking {
        val repo = TerraRepository(
            chunkDao = db.chunkDao(),
            routeDao = db.routeDao(),
            markerDao = db.markerDao(),
            paletteDao = db.paletteDao(),
            achievementDao = db.achievementDao()
        )
        val prefs = UserPreferencesRepository(context)
        prefs.setSnapToRoadsEnabled(true)
        assertTrue(prefs.mapDisplaySettings.first().snapToRoadsEnabled)

        val fakeFixes = MutableSharedFlow<LocationTelemetry>(replay = 1)
        val fakeTracker = object : LocationTracker(context) {
            override fun observeLocationUpdates(hasPermission: Boolean): Flow<LocationTelemetry> = fakeFixes
        }

        val roadLon = 28.8600
        val fakeSnapper = object : RoadSnapper(null) {
            override suspend fun snapPoint(
                latitude: Double,
                longitude: Double,
                accuracyMeters: Float,
                headingDegrees: Float?,
                speedKmh: Float
            ): RoadSnapResult {
                return RoadSnapResult(
                    latitude = latitude,
                    longitude = roadLon,
                    snappedToRoad = true,
                    snapDistanceMeters = GeoGrid.haversineMeters(latitude, longitude, latitude, roadLon),
                    matchedRoadId = "test_avenue"
                )
            }

            override suspend fun snapPolyline(
                points: List<LatLonPoint>,
                maxSnapMeters: Double
            ): List<LatLonPoint> {
                return points.map { it.copy(longitude = roadLon) }
            }
        }

        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        val coordinator = TrackingCoordinator(
            repository = repo,
            preferences = prefs,
            locationTracker = fakeTracker,
            stepSensorTracker = StepSensorTracker(context),
            roadSnapper = fakeSnapper,
            scope = scope
        )

        waitUntil { coordinator.mapDisplaySettings.value.snapToRoadsEnabled }
        coordinator.setForegroundUiActive(true)
        coordinator.updatePermissions(hasLocation = true, hasActivityRecognition = false)

        // Emit offset GPS fix -> coordinator must publish snapped longitude onto roadLon
        val offsetLon = 28.85975 // ~19m west of roadLon
        fakeFixes.emit(
            LocationTelemetry(
                latitude = 47.0100,
                longitude = offsetLon,
                speedKmh = 4.2f,
                accuracyMeters = 10f,
                hasFix = true,
                hasPermission = true,
                lastUpdatedAt = 5_000L
            )
        )
        waitUntil { coordinator.locationTelemetry.value.hasFix }
        assertEquals(roadLon, coordinator.locationTelemetry.value.longitude, 1e-6)

        // Stream restart emitting (hasFix = false, hasPermission = true) must NOT revert HUD back to GPS Sync
        fakeFixes.emit(LocationTelemetry(hasFix = false, hasPermission = true))
        testDispatcher.scheduler.runCurrent()
        assertTrue(coordinator.locationTelemetry.value.hasFix)
        assertEquals(roadLon, coordinator.locationTelemetry.value.longitude, 1e-6)

        // Coarse provisional cold-start fix (>45m) must NOT unlock extra sectors when live fix resets not tested,
        // and snapping a previously saved off-road track aligns its points cleanly onto roadLon
        val savedRouteId = repo.saveRecordedRoute(
            name = "Explorer Path",
            colorArgb = 0xFFE11D48,
            distanceMeters = 150.0,
            durationMillis = 120_000L,
            avgSpeedKmh = 4.5f,
            maxSpeedKmh = 5.2f,
            chunksUnlocked = 1,
            startedAt = 1_000L,
            endedAt = 121_000L,
            points = listOf(
                LatLonPoint(47.0100, offsetLon, 4.5f, 1_000L),
                LatLonPoint(47.0110, offsetLon, 4.5f, 121_000L)
            )
        )
        coordinator.snapRouteToRoads(savedRouteId)
        waitUntil {
            val pts = runBlocking { repo.getRoutePointsOnce(savedRouteId) }
            pts.isNotEmpty() && kotlin.math.abs(pts.first().longitude - roadLon) < 1e-6
        }
        val snappedPts = repo.getRoutePointsOnce(savedRouteId)
        assertNotNull(snappedPts)
        assertEquals(2, snappedPts.size)
        assertEquals(roadLon, snappedPts[0].longitude, 1e-6)
        assertEquals(roadLon, snappedPts[1].longitude, 1e-6)

        prefs.setSnapToRoadsEnabled(true)
        scope.cancel()
    }
}
