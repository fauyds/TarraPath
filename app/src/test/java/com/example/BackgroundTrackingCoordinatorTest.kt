package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TerraPathDatabase
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.TerraRepository
import com.example.data.sensors.LocationTracker
import com.example.data.sensors.StepSensorTracker
import com.example.data.model.LocationTelemetry
import com.example.domain.GeoGrid
import com.example.service.BackgroundServiceController
import com.example.service.TrackingCoordinator
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackgroundTrackingCoordinatorTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var db: TerraPathDatabase
    private lateinit var context: Context

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

    private class FakeServiceController(
        private val onStartCallback: () -> Unit = {},
        private val onStopCallback: () -> Unit = {}
    ) : BackgroundServiceController {
        var startCount = 0
        var stopCount = 0

        override fun startLocationService() {
            startCount++
            onStartCallback()
        }

        override fun stopLocationService() {
            stopCount++
            onStopCallback()
        }
    }

    private suspend fun waitUntil(timeoutMs: Long = 2000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            testDispatcher.scheduler.runCurrent()
        }
    }

    @Test
    fun trackingCoordinator_startsAndStopsBackgroundServiceBasedOnPermissionsAndPreference() = runBlocking {
        val repo = TerraRepository(
            chunkDao = db.chunkDao(),
            routeDao = db.routeDao(),
            markerDao = db.markerDao(),
            paletteDao = db.paletteDao(),
            achievementDao = db.achievementDao()
        )
        val prefs = UserPreferencesRepository(context)
        prefs.setBackgroundTrackingEnabled(true)
        assertTrue(prefs.mapDisplaySettings.first().backgroundTrackingEnabled)

        lateinit var coordinator: TrackingCoordinator
        val fakeController = FakeServiceController(
            onStartCallback = { coordinator.onBackgroundServiceCreated() },
            onStopCallback = { coordinator.onBackgroundServiceDestroyed() }
        )

        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        coordinator = TrackingCoordinator(
            repository = repo,
            preferences = prefs,
            locationTracker = LocationTracker(context),
            stepSensorTracker = StepSensorTracker(context),
            serviceController = fakeController,
            scope = scope
        )

        waitUntil { coordinator.mapDisplaySettings.value.backgroundTrackingEnabled }

        // Without location permission, service must never be started
        assertEquals(0, fakeController.startCount)
        assertFalse(coordinator.isBackgroundServiceRunning.value)

        // Foreground UI active + permission granted -> starts foreground location service
        coordinator.setForegroundUiActive(true)
        coordinator.updatePermissions(hasLocation = true, hasActivityRecognition = false)
        waitUntil { coordinator.isBackgroundServiceRunning.value }
        assertEquals(1, fakeController.startCount)
        assertTrue(coordinator.isBackgroundServiceRunning.value)

        // Going to background keeps background service running while preference is enabled
        coordinator.setForegroundUiActive(false)
        testDispatcher.scheduler.runCurrent()
        assertTrue(coordinator.isBackgroundServiceRunning.value)
        assertEquals(0, fakeController.stopCount)

        // Returning to foreground & disabling background tracking stops idle service
        coordinator.setForegroundUiActive(true)
        coordinator.setBackgroundTrackingEnabled(false)
        waitUntil { !coordinator.isBackgroundServiceRunning.value }
        assertFalse(coordinator.isBackgroundServiceRunning.value)
        assertEquals(1, fakeController.stopCount)

        scope.cancel()
    }

    @Test
    fun trackingCoordinator_keepsServiceRunningDuringRouteRecordingEvenWhenIdleBgDisabled() = runBlocking {
        val repo = TerraRepository(
            chunkDao = db.chunkDao(),
            routeDao = db.routeDao(),
            markerDao = db.markerDao(),
            paletteDao = db.paletteDao(),
            achievementDao = db.achievementDao()
        )
        val prefs = UserPreferencesRepository(context)
        prefs.setBackgroundTrackingEnabled(false)
        assertFalse(prefs.mapDisplaySettings.first().backgroundTrackingEnabled)

        lateinit var coordinator: TrackingCoordinator
        val fakeController = FakeServiceController(
            onStartCallback = { coordinator.onBackgroundServiceCreated() },
            onStopCallback = { coordinator.onBackgroundServiceDestroyed() }
        )

        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        coordinator = TrackingCoordinator(
            repository = repo,
            preferences = prefs,
            locationTracker = LocationTracker(context),
            stepSensorTracker = StepSensorTracker(context),
            serviceController = fakeController,
            scope = scope
        )

        waitUntil { !coordinator.mapDisplaySettings.value.backgroundTrackingEnabled }

        coordinator.setForegroundUiActive(true)
        coordinator.updatePermissions(hasLocation = true, hasActivityRecognition = false)
        testDispatcher.scheduler.runCurrent()
        assertFalse(coordinator.isBackgroundServiceRunning.value)

        // Starting route recording promotes the background service even when general bg tracking is off
        coordinator.startRouteRecording()
        waitUntil { coordinator.isBackgroundServiceRunning.value }
        assertTrue(coordinator.activeRecording.value.isRecording)
        assertTrue(coordinator.isBackgroundServiceRunning.value)

        // Minimizing keeps recording & service active
        coordinator.setForegroundUiActive(false)
        testDispatcher.scheduler.runCurrent()
        assertTrue(coordinator.isBackgroundServiceRunning.value)
        assertTrue(coordinator.activeRecording.value.isRecording)

        // Stopping route produces PendingRouteSummary and stops service when general bg tracking is disabled
        coordinator.stopRouteRecordingAndPrompt()
        waitUntil { !coordinator.isBackgroundServiceRunning.value }
        assertNotNull(coordinator.pendingRouteSummary.value)
        assertFalse(coordinator.isBackgroundServiceRunning.value)

        prefs.setBackgroundTrackingEnabled(true)
        scope.cancel()
    }

    @Test
    fun trackingCoordinator_unlocksTraversedSectorsInBackgroundMode() = runBlocking {
        val repo = TerraRepository(
            chunkDao = db.chunkDao(),
            routeDao = db.routeDao(),
            markerDao = db.markerDao(),
            paletteDao = db.paletteDao(),
            achievementDao = db.achievementDao()
        )
        val prefs = UserPreferencesRepository(context)
        prefs.setBackgroundTrackingEnabled(true)

        val fakeFixes = MutableSharedFlow<LocationTelemetry>(replay = 1)
        val fakeTracker = object : LocationTracker(context) {
            override fun observeLocationUpdates(hasPermission: Boolean): Flow<LocationTelemetry> = fakeFixes
        }

        lateinit var coordinator: TrackingCoordinator
        val fakeController = FakeServiceController(
            onStartCallback = { coordinator.onBackgroundServiceCreated() },
            onStopCallback = { coordinator.onBackgroundServiceDestroyed() }
        )

        val scope = CoroutineScope(SupervisorJob() + testDispatcher)
        coordinator = TrackingCoordinator(
            repository = repo,
            preferences = prefs,
            locationTracker = fakeTracker,
            stepSensorTracker = StepSensorTracker(context),
            serviceController = fakeController,
            scope = scope
        )

        waitUntil { coordinator.mapDisplaySettings.value.backgroundTrackingEnabled }

        coordinator.setForegroundUiActive(true)
        coordinator.updatePermissions(hasLocation = true, hasActivityRecognition = false)
        waitUntil { coordinator.isBackgroundServiceRunning.value }

        // Move app UI into background (screen off / minimized)
        coordinator.setForegroundUiActive(false)
        testDispatcher.scheduler.runCurrent()
        assertTrue(coordinator.isBackgroundServiceRunning.value)

        // First GPS fix unlocks starting sector
        val lat0 = 37.7749
        val lon0 = -122.4194
        fakeFixes.emit(
            LocationTelemetry(
                latitude = lat0,
                longitude = lon0,
                speedKmh = 4.5f,
                accuracyMeters = 8f,
                hasFix = true,
                hasPermission = true,
                lastUpdatedAt = 10_000L
            )
        )
        waitUntil { coordinator.totalChunkCount.value >= 1 }
        assertEquals(1, coordinator.totalChunkCount.value)

        // Second fix arrives ~750m North after a background GPS gap -> must unlock start, intermediate, AND destination sectors (4 total)
        val lat3 = lat0 + GeoGrid.CHUNK_SIZE_DEG_LAT * 3.0
        fakeFixes.emit(
            LocationTelemetry(
                latitude = lat3,
                longitude = lon0,
                speedKmh = 5.0f,
                accuracyMeters = 10f,
                hasFix = true,
                hasPermission = true,
                lastUpdatedAt = 160_000L
            )
        )
        waitUntil { coordinator.totalChunkCount.value >= 4 }
        assertEquals(4, coordinator.totalChunkCount.value)

        scope.cancel()
    }
}
