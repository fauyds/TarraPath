package com.example.service

import com.example.data.local.ExploredChunkEntity
import com.example.data.local.MapMarkerEntity
import com.example.data.local.RouteEntity
import com.example.data.model.AchievementDefinition
import com.example.data.model.ActiveRecordingState
import com.example.data.model.ApiProvider
import com.example.data.model.LatLonPoint
import com.example.data.model.LocationTelemetry
import com.example.data.model.MapDisplaySettings
import com.example.data.model.PendingRouteSummary
import com.example.data.model.RoutingMode
import com.example.data.model.SearchPlaceResult
import com.example.data.model.WalkTarget
import com.example.data.model.WalkingRouteState
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.WalkingRouteService
import com.example.data.repository.OfflineRegionManager
import com.example.data.repository.TerraRepository
import com.example.data.sensors.CompassHeadingTracker
import com.example.data.sensors.HeadingEstimator
import com.example.data.sensors.LocationTracker
import com.example.data.sensors.StepSensorEvent
import com.example.data.sensors.StepSensorTracker
import com.example.domain.GeoGrid
import com.example.domain.OfflineRoadGraph
import com.example.domain.RoadSnapper
import com.example.domain.WalkingNavigationPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface BackgroundServiceController {
    fun startLocationService()
    fun stopLocationService()
}

object NoOpBackgroundServiceController : BackgroundServiceController {
    override fun startLocationService() = Unit
    override fun stopLocationService() = Unit
}

/**
 * Application-scoped coordinator that keeps GPS telemetry, fog-of-war sector unlocking,
 * step counting, active route recording, walking navigation, and achievement evaluation
 * running seamlessly across Activity configuration changes and background execution.
 */
class TrackingCoordinator(
    private val repository: TerraRepository,
    private val preferences: UserPreferencesRepository,
    private val locationTracker: LocationTracker,
    private val stepSensorTracker: StepSensorTracker,
    private val compassHeadingTracker: CompassHeadingTracker? = null,
    private val walkingRouteService: WalkingRouteService = WalkingRouteService(),
    private val offlineRegionManager: OfflineRegionManager? = null,
    private val roadSnapper: RoadSnapper = RoadSnapper(offlineRegionManager),
    private val serviceController: BackgroundServiceController = NoOpBackgroundServiceController,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val _hasLocationPermission = MutableStateFlow(false)
    private val _hasActivityPermission = MutableStateFlow(false)
    private val _isForegroundUiActive = MutableStateFlow(false)
    private val _isBackgroundServiceRunning = MutableStateFlow(false)
    val isBackgroundServiceRunning: StateFlow<Boolean> = _isBackgroundServiceRunning.asStateFlow()

    private var latestGpsTelemetry = LocationTelemetry()
    private var latestCompassHeadingDeg: Float? = null

    private val _locationTelemetry = MutableStateFlow(LocationTelemetry())
    val locationTelemetry: StateFlow<LocationTelemetry> = _locationTelemetry.asStateFlow()

    private val _activeRecording = MutableStateFlow(ActiveRecordingState())
    val activeRecording: StateFlow<ActiveRecordingState> = _activeRecording.asStateFlow()

    private val _pendingRouteSummary = MutableStateFlow<PendingRouteSummary?>(null)
    val pendingRouteSummary: StateFlow<PendingRouteSummary?> = _pendingRouteSummary.asStateFlow()

    private val _highlightedRouteId = MutableStateFlow<Long?>(null)
    val highlightedRouteId: StateFlow<Long?> = _highlightedRouteId.asStateFlow()

    private val _walkingRouteState = MutableStateFlow(WalkingRouteState())
    val walkingRouteState: StateFlow<WalkingRouteState> = _walkingRouteState.asStateFlow()

    private val _snappingRouteId = MutableStateFlow<Long?>(null)
    val snappingRouteId: StateFlow<Long?> = _snappingRouteId.asStateFlow()

    private val _unlockedToastQueue = MutableStateFlow<List<AchievementDefinition>>(emptyList())
    val activeAchievementBanner: StateFlow<AchievementDefinition?> = _unlockedToastQueue
        .map { it.firstOrNull() }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val mapDisplaySettings: StateFlow<MapDisplaySettings> = preferences.mapDisplaySettings
        .stateIn(scope, SharingStarted.Eagerly, MapDisplaySettings())

    val apiKey: StateFlow<String> = preferences.apiKey
        .stateIn(scope, SharingStarted.Eagerly, "")

    val apiProvider: StateFlow<ApiProvider> = preferences.apiProvider
        .stateIn(scope, SharingStarted.Eagerly, ApiProvider.CUSTOM_GENERAL)

    val allRoutes: StateFlow<List<RouteEntity>> = repository.allRoutes
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val exploredChunks: StateFlow<List<ExploredChunkEntity>> = repository.exploredChunks
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val totalChunkCount: StateFlow<Int> = repository.totalChunkCount
        .stateIn(scope, SharingStarted.Eagerly, 0)

    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var stepJob: Job? = null
    private var timerJob: Job? = null
    private var walkingRouteJob: Job? = null
    private var arrivalBannerJob: Job? = null
    private var fullCalculatedWalkPoints: List<LatLonPoint> = emptyList()
    private var lastRecordedTelemetry: LocationTelemetry? = null
    private var lastUnlockedFix: LocationTelemetry? = null

    init {
        scope.launch {
            repository.ensureDefaultDataSeeded()
        }
        scope.launch {
            mapDisplaySettings.collect {
                syncTrackingAndServiceState()
            }
        }
    }

    private fun publishMergedTelemetry() {
        val gps = latestGpsTelemetry
        val fusedHeading = HeadingEstimator.resolveHeadingDegrees(
            compassDegrees = latestCompassHeadingDeg,
            gpsBearingDegrees = gps.bearingDegrees,
            speedKmh = gps.speedKmh
        )
        _locationTelemetry.value = gps.copy(headingDegrees = fusedHeading)
    }

    fun updatePermissions(hasLocation: Boolean, hasActivityRecognition: Boolean) {
        var changed = false
        if (hasLocation != _hasLocationPermission.value) {
            _hasLocationPermission.value = hasLocation
            latestGpsTelemetry = latestGpsTelemetry.copy(hasPermission = hasLocation)
            publishMergedTelemetry()
            changed = true
        }
        if (hasActivityRecognition != _hasActivityPermission.value) {
            _hasActivityPermission.value = hasActivityRecognition
            changed = true
        }
        if (changed) {
            syncTrackingAndServiceState()
        }
    }

    fun setForegroundUiActive(active: Boolean) {
        if (_isForegroundUiActive.value != active) {
            _isForegroundUiActive.value = active
            syncTrackingAndServiceState()
        } else if (active) {
            syncTrackingAndServiceState()
        }
    }

    fun onBackgroundServiceCreated() {
        _isBackgroundServiceRunning.value = true
        syncTrackingAndServiceState()
    }

    fun onBackgroundServiceDestroyed() {
        _isBackgroundServiceRunning.value = false
        syncTrackingAndServiceState()
    }

    fun shouldRunBackgroundService(): Boolean {
        if (!_hasLocationPermission.value) return false
        return mapDisplaySettings.value.backgroundTrackingEnabled ||
            _activeRecording.value.isRecording ||
            _walkingRouteState.value.target != null
    }

    private fun shouldKeepLocationSensorsActive(): Boolean {
        if (!_hasLocationPermission.value) return false
        return _isForegroundUiActive.value ||
            _isBackgroundServiceRunning.value ||
            shouldRunBackgroundService()
    }

    fun syncTrackingAndServiceState() {
        val keepSensors = shouldKeepLocationSensorsActive()
        if (keepSensors) {
            if (locationJob == null || locationJob?.isActive != true) {
                startLocationCollection()
            }
            if (_hasActivityPermission.value && (stepJob == null || stepJob?.isActive != true)) {
                startStepCollection()
            } else if (!_hasActivityPermission.value) {
                stepJob?.cancel()
                stepJob = null
            }
        } else {
            locationJob?.cancel()
            locationJob = null
            stepJob?.cancel()
            stepJob = null
        }

        // Compass heading is only needed for UI arrow rotation while the app is visible
        if (_hasLocationPermission.value && _isForegroundUiActive.value) {
            if (headingJob == null || headingJob?.isActive != true) {
                startHeadingCollection()
            }
        } else {
            headingJob?.cancel()
            headingJob = null
        }

        // Only launch a location Foreground Service when eligible (Activity visible or already running),
        // respecting Android 14+ restrictions on background FGS launches.
        val wantsService = shouldRunBackgroundService()
        if (wantsService) {
            if (_isForegroundUiActive.value && !_isBackgroundServiceRunning.value) {
                serviceController.startLocationService()
            }
        } else if (_isBackgroundServiceRunning.value) {
            serviceController.stopLocationService()
        }
    }

    private fun startLocationCollection() {
        locationJob?.cancel()
        locationJob = scope.launch {
            locationTracker.observeLocationUpdates(true).collect { telemetry ->
                // Avoid flashing back to "GPS Sync" if the location collector restarts while a valid fix is already cached
                if (!telemetry.hasFix && latestGpsTelemetry.hasFix && telemetry.hasPermission) {
                    return@collect
                }

                val effectiveTelemetry = if (telemetry.hasFix && mapDisplaySettings.value.snapToRoadsEnabled) {
                    val headingHint = HeadingEstimator.resolveHeadingDegrees(
                        compassDegrees = latestCompassHeadingDeg,
                        gpsBearingDegrees = telemetry.bearingDegrees,
                        speedKmh = telemetry.speedKmh
                    )
                    val snap = roadSnapper.snapPoint(
                        latitude = telemetry.latitude,
                        longitude = telemetry.longitude,
                        accuracyMeters = telemetry.accuracyMeters,
                        headingDegrees = headingHint,
                        speedKmh = telemetry.speedKmh
                    )
                    if (snap.snappedToRoad) {
                        telemetry.copy(
                            latitude = snap.latitude,
                            longitude = snap.longitude
                        )
                    } else {
                        telemetry
                    }
                } else {
                    if (!telemetry.hasFix) {
                        roadSnapper.resetContinuity()
                    }
                    telemetry
                }

                latestGpsTelemetry = effectiveTelemetry
                publishMergedTelemetry()
                // Only advance sector unlocks & track points when fix is sharper than provisional coarse fallback
                if (effectiveTelemetry.hasFix && effectiveTelemetry.accuracyMeters <= 45f) {
                    processGpsFix(effectiveTelemetry)
                }
            }
        }
    }

    private fun startHeadingCollection() {
        headingJob?.cancel()
        val tracker = compassHeadingTracker ?: return
        headingJob = scope.launch {
            tracker.observeHeadingDegrees().collect { compassDeg ->
                latestCompassHeadingDeg = compassDeg
                if (latestGpsTelemetry.hasFix) {
                    publishMergedTelemetry()
                }
            }
        }
    }

    private suspend fun processGpsFix(telemetry: LocationTelemetry) {
        val prevUnlock = lastUnlockedFix
        val newlyUnlockedCount = if (prevUnlock != null && prevUnlock.hasFix) {
            val segDist = GeoGrid.haversineMeters(
                prevUnlock.latitude,
                prevUnlock.longitude,
                telemetry.latitude,
                telemetry.longitude
            )
            val dtUnlockSec = ((telemetry.lastUpdatedAt - prevUnlock.lastUpdatedAt) / 1000.0).coerceAtLeast(0.5)
            val shouldInterpolate = segDist <= 2500.0 && (segDist / dtUnlockSec) <= 45.0
            repository.unlockChunksAlongSegment(
                startLat = prevUnlock.latitude,
                startLon = prevUnlock.longitude,
                endLat = telemetry.latitude,
                endLon = telemetry.longitude,
                interpolateSegment = shouldInterpolate
            )
        } else {
            if (repository.unlockChunkAt(telemetry.latitude, telemetry.longitude)) 1 else 0
        }
        lastUnlockedFix = telemetry

        val prev = lastRecordedTelemetry
        var distDelta = 0.0
        if (prev != null && prev.hasFix) {
            val d = GeoGrid.haversineMeters(
                prev.latitude,
                prev.longitude,
                telemetry.latitude,
                telemetry.longitude
            )
            val dtSec = ((telemetry.lastUpdatedAt - prev.lastUpdatedAt) / 1000.0).coerceAtLeast(0.5)
            val impliedSpeedMps = d / dtSec
            if (d >= 2.5 && d <= 1500.0 && impliedSpeedMps <= 12.0) {
                distDelta = d
                lastRecordedTelemetry = telemetry
            } else if (d > 1500.0 || impliedSpeedMps > 12.0) {
                // Re-anchor after a large background gap/relocation without counting teleport distance
                lastRecordedTelemetry = telemetry
            }
        } else {
            lastRecordedTelemetry = telemetry
        }

        updateWalkingRouteOnGpsFix(telemetry)

        if (_activeRecording.value.isRecording) {
            _activeRecording.update { state ->
                val lastPt = state.points.lastOrNull()
                val distFromLastPt = if (lastPt == null) {
                    Double.POSITIVE_INFINITY
                } else {
                    GeoGrid.haversineMeters(
                        lastPt.latitude,
                        lastPt.longitude,
                        telemetry.latitude,
                        telemetry.longitude
                    )
                }
                val dtPtSec = if (lastPt == null || lastPt.timestamp <= 0L) {
                    1.0
                } else {
                    ((telemetry.lastUpdatedAt - lastPt.timestamp) / 1000.0).coerceAtLeast(0.5)
                }
                val shouldAppendPoint = lastPt == null || (
                    distFromLastPt >= 2.5 &&
                        distFromLastPt <= 1500.0 &&
                        (distFromLastPt / dtPtSec) <= 12.0
                    )

                val newPoints = if (shouldAppendPoint) {
                    state.points + LatLonPoint(
                        latitude = telemetry.latitude,
                        longitude = telemetry.longitude,
                        speedKmh = telemetry.speedKmh,
                        timestamp = telemetry.lastUpdatedAt
                    )
                } else {
                    state.points
                }

                state.copy(
                    distanceMeters = state.distanceMeters + distDelta,
                    maxSpeedKmh = maxOf(state.maxSpeedKmh, telemetry.speedKmh),
                    chunksUnlockedDuringRoute = state.chunksUnlockedDuringRoute + newlyUnlockedCount,
                    points = newPoints
                )
            }
        }

        val chunkTotal = repository.getTotalChunkCountOnce()
        val unlockedNow = repository.recordTelemetryProgress(
            distanceDeltaMeters = distDelta,
            stepsDelta = 0L,
            currentSpeedKmh = telemetry.speedKmh,
            totalChunksUnlocked = chunkTotal
        )
        enqueueUnlockedAchievements(unlockedNow)
    }

    private fun startStepCollection() {
        stepJob?.cancel()
        stepJob = scope.launch {
            stepSensorTracker.observeSteps(true).collect { event ->
                val delta = when (event) {
                    is StepSensorEvent.CumulativeSteps -> {
                        val baseline = preferences.stepCounterBaseline.first()
                        if (baseline == null || event.totalSinceBoot < baseline) {
                            preferences.setStepCounterBaseline(event.totalSinceBoot)
                            0L
                        } else {
                            val diff = event.totalSinceBoot - baseline
                            if (diff > 0L) {
                                preferences.setStepCounterBaseline(event.totalSinceBoot)
                            }
                            diff.coerceIn(0L, 5000L)
                        }
                    }
                    StepSensorEvent.SingleStepDetected -> 1L
                }
                if (delta > 0L) {
                    val chunkTotal = repository.getTotalChunkCountOnce()
                    val unlockedNow = repository.recordTelemetryProgress(
                        distanceDeltaMeters = 0.0,
                        stepsDelta = delta,
                        currentSpeedKmh = _locationTelemetry.value.speedKmh,
                        totalChunksUnlocked = chunkTotal
                    )
                    enqueueUnlockedAchievements(unlockedNow)
                }
            }
        }
    }

    private fun enqueueUnlockedAchievements(unlocked: List<AchievementDefinition>) {
        if (unlocked.isEmpty()) return
        _unlockedToastQueue.update { queue ->
            val existingIds = queue.map { it.id }.toSet()
            queue + unlocked.filter { it.id !in existingIds }
        }
    }

    fun dismissCurrentAchievementBanner() {
        _unlockedToastQueue.update { queue ->
            if (queue.isNotEmpty()) queue.drop(1) else emptyList()
        }
    }

    fun startRouteRecording() {
        val now = System.currentTimeMillis()
        val loc = _locationTelemetry.value
        val initialPoints = if (loc.hasFix) {
            listOf(LatLonPoint(loc.latitude, loc.longitude, loc.speedKmh, now))
        } else emptyList()

        _activeRecording.value = ActiveRecordingState(
            isRecording = true,
            startedAtMillis = now,
            elapsedMillis = 0L,
            distanceMeters = 0.0,
            maxSpeedKmh = loc.speedKmh,
            chunksUnlockedDuringRoute = 0,
            points = initialPoints
        )

        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(500L)
                val rec = _activeRecording.value
                if (!rec.isRecording) break
                _activeRecording.update {
                    it.copy(elapsedMillis = System.currentTimeMillis() - it.startedAtMillis)
                }
            }
        }
        syncTrackingAndServiceState()
    }

    fun stopRouteRecordingAndPrompt() {
        timerJob?.cancel()
        val rec = _activeRecording.value
        if (!rec.isRecording) return
        val endedAt = System.currentTimeMillis()
        val finalDuration = (endedAt - rec.startedAtMillis).coerceAtLeast(1000L)

        val loc = _locationTelemetry.value
        val finalPoints = if (loc.hasFix && (rec.points.isEmpty() || GeoGrid.haversineMeters(
                rec.points.last().latitude,
                rec.points.last().longitude,
                loc.latitude,
                loc.longitude
            ) >= 1.0)
        ) {
            rec.points + LatLonPoint(loc.latitude, loc.longitude, loc.speedKmh, endedAt)
        } else {
            rec.points
        }

        val hours = finalDuration / 3_600_000.0
        val avgSpeed = if (hours > 0.0 && rec.distanceMeters > 0.0) {
            (rec.distanceMeters / 1000.0 / hours).toFloat()
        } else 0f

        _pendingRouteSummary.value = PendingRouteSummary(
            distanceMeters = rec.distanceMeters,
            durationMillis = finalDuration,
            avgSpeedKmh = avgSpeed,
            maxSpeedKmh = rec.maxSpeedKmh,
            chunksUnlocked = rec.chunksUnlockedDuringRoute,
            startedAt = rec.startedAtMillis,
            endedAt = endedAt,
            points = finalPoints
        )
        _activeRecording.value = ActiveRecordingState()
        syncTrackingAndServiceState()
    }

    fun confirmSaveRoute(name: String, colorArgb: Long) {
        val summary = _pendingRouteSummary.value ?: return
        _pendingRouteSummary.value = null
        scope.launch {
            val savedId = repository.saveRecordedRoute(
                name = name,
                colorArgb = colorArgb,
                distanceMeters = summary.distanceMeters,
                durationMillis = summary.durationMillis,
                avgSpeedKmh = summary.avgSpeedKmh,
                maxSpeedKmh = summary.maxSpeedKmh,
                chunksUnlocked = summary.chunksUnlocked,
                startedAt = summary.startedAt,
                endedAt = summary.endedAt,
                points = summary.points
            )
            _highlightedRouteId.value = savedId
        }
    }

    fun discardPendingRoute() {
        _pendingRouteSummary.value = null
    }

    fun highlightRouteOnMap(routeId: Long?) {
        _highlightedRouteId.value = routeId
        if (routeId == null) {
            if (_walkingRouteState.value.target is WalkTarget.SavedTrackStart) {
                stopWalkingNavigation()
            }
        } else {
            scope.launch {
                val points = repository.getRoutePointsOnce(routeId)
                val firstPt = points.firstOrNull() ?: return@launch
                val gps = _locationTelemetry.value
                if (gps.hasFix && !WalkingNavigationPolicy.hasArrivedAtStart(
                        gps.latitude,
                        gps.longitude,
                        firstPt.latitude,
                        firstPt.longitude
                    )
                ) {
                    val routeName = allRoutes.value.firstOrNull { it.id == routeId }?.name ?: "Saved Track"
                    activateWalkTarget(
                        WalkTarget.SavedTrackStart(
                            routeId = routeId,
                            label = "$routeName Start",
                            targetLat = firstPt.latitude,
                            targetLon = firstPt.longitude
                        )
                    )
                }
            }
        }
    }

    fun startWalkingToMarker(marker: MapMarkerEntity) {
        activateWalkTarget(
            WalkTarget.SavedPoint(
                markerId = marker.id,
                label = marker.title,
                targetLat = marker.latitude,
                targetLon = marker.longitude
            )
        )
    }

    fun startWalkingToTrackStart(routeId: Long) {
        _highlightedRouteId.value = routeId
        scope.launch {
            val points = repository.getRoutePointsOnce(routeId)
            val firstPt = points.firstOrNull() ?: return@launch
            val routeName = allRoutes.value.firstOrNull { it.id == routeId }?.name ?: "Saved Track"
            activateWalkTarget(
                WalkTarget.SavedTrackStart(
                    routeId = routeId,
                    label = "$routeName Start",
                    targetLat = firstPt.latitude,
                    targetLon = firstPt.longitude
                )
            )
        }
    }

    fun startWalkingToSearchPlace(place: SearchPlaceResult) {
        activateWalkTarget(
            WalkTarget.SearchedPlace(
                placeId = place.id,
                label = place.title,
                targetLat = place.latitude,
                targetLon = place.longitude
            )
        )
    }

    fun startWalkingToCoordinate(label: String, latitude: Double, longitude: Double) {
        activateWalkTarget(
            WalkTarget.CustomPoint(
                label = label.trim().ifEmpty { "Custom Pin" },
                targetLat = latitude,
                targetLon = longitude
            )
        )
    }

    fun stopWalkingNavigation() {
        walkingRouteJob?.cancel()
        fullCalculatedWalkPoints = emptyList()
        _walkingRouteState.value = WalkingRouteState()
        syncTrackingAndServiceState()
    }

    fun dismissArrivalBanner() {
        arrivalBannerJob?.cancel()
        _walkingRouteState.update { it.copy(arrivedBannerLabel = null) }
    }

    private fun activateWalkTarget(target: WalkTarget) {
        arrivalBannerJob?.cancel()
        walkingRouteJob?.cancel()
        fullCalculatedWalkPoints = emptyList()

        val gps = _locationTelemetry.value
        val mode = mapDisplaySettings.value.routingMode
        if (gps.hasFix && WalkingNavigationPolicy.hasArrivedAtStart(
                gps.latitude,
                gps.longitude,
                target.targetLat,
                target.targetLon
            )
        ) {
            triggerArrivedClear(target.label)
            return
        }

        val initialDist = if (gps.hasFix) {
            GeoGrid.haversineMeters(gps.latitude, gps.longitude, target.targetLat, target.targetLon)
        } else 0.0

        _walkingRouteState.value = WalkingRouteState(
            target = target,
            remainingDistanceMeters = initialDist,
            routeDistanceMeters = initialDist,
            estimatedWalkSeconds = (initialDist / OfflineRoadGraph.WALKING_SPEED_MPS).toLong(),
            modeUsed = mode,
            errorMessage = if (!gps.hasFix) "Waiting for GPS fix…" else null
        )

        syncTrackingAndServiceState()

        if (gps.hasFix) {
            requestWalkingRouteCalculation(gps.latitude, gps.longitude, target, mode)
        }
    }

    private fun updateWalkingRouteOnGpsFix(telemetry: LocationTelemetry) {
        val state = _walkingRouteState.value
        val target = state.target ?: return
        if (!telemetry.hasFix) return

        if (WalkingNavigationPolicy.hasArrivedAtStart(
                telemetry.latitude,
                telemetry.longitude,
                target.targetLat,
                target.targetLon
            )
        ) {
            triggerArrivedClear(target.label)
            return
        }

        val sourcePoints = if (fullCalculatedWalkPoints.size >= 2) fullCalculatedWalkPoints else state.routePoints
        val (remaining, trimmed) = WalkingNavigationPolicy.computeRemainingAndTrimmedRoute(
            currentLat = telemetry.latitude,
            currentLon = telemetry.longitude,
            routePoints = sourcePoints,
            targetLat = target.targetLat,
            targetLon = target.targetLon
        )
        _walkingRouteState.update { cur ->
            if (cur.target != target) cur
            else cur.copy(
                routePoints = if (fullCalculatedWalkPoints.size >= 2) trimmed else cur.routePoints,
                remainingDistanceMeters = remaining,
                estimatedWalkSeconds = (remaining / OfflineRoadGraph.WALKING_SPEED_MPS).toLong().coerceAtLeast(1L)
            )
        }

        val activeMode = mapDisplaySettings.value.routingMode
        val now = System.currentTimeMillis()
        if (WalkingNavigationPolicy.shouldRecalculateRoute(
                state = _walkingRouteState.value,
                currentLat = telemetry.latitude,
                currentLon = telemetry.longitude,
                activeMode = activeMode,
                nowMillis = now
            )
        ) {
            requestWalkingRouteCalculation(telemetry.latitude, telemetry.longitude, target, activeMode)
        }
    }

    private fun triggerArrivedClear(label: String) {
        walkingRouteJob?.cancel()
        fullCalculatedWalkPoints = emptyList()
        _walkingRouteState.value = WalkingRouteState(
            arrivedBannerLabel = "Arrived near $label"
        )
        arrivalBannerJob?.cancel()
        arrivalBannerJob = scope.launch {
            delay(4000L)
            _walkingRouteState.update { it.copy(arrivedBannerLabel = null) }
        }
        syncTrackingAndServiceState()
    }

    fun requestWalkingRouteCalculation(
        startLat: Double,
        startLon: Double,
        target: WalkTarget,
        mode: RoutingMode
    ) {
        walkingRouteJob?.cancel()
        val requestTime = System.currentTimeMillis()
        _walkingRouteState.update {
            it.copy(
                isCalculating = true,
                modeUsed = mode,
                errorMessage = null
            )
        }
        walkingRouteJob = scope.launch {
            val result = when (mode) {
                RoutingMode.ONLINE -> walkingRouteService.calculateOnlineWalkingRoute(
                    startLat = startLat,
                    startLon = startLon,
                    endLat = target.targetLat,
                    endLon = target.targetLon,
                    apiKey = apiKey.value,
                    apiProvider = apiProvider.value
                )
                RoutingMode.OFFLINE -> offlineRegionManager?.calculateOfflineWalkingRoute(
                    startLat = startLat,
                    startLon = startLon,
                    endLat = target.targetLat,
                    endLon = target.targetLon
                ) ?: Result.failure(IllegalStateException("Offline routing storage unavailable"))
            }

            result.onSuccess { outcome ->
                fullCalculatedWalkPoints = outcome.points
                val latestGps = _locationTelemetry.value
                val (remaining, trimmed) = if (latestGps.hasFix) {
                    WalkingNavigationPolicy.computeRemainingAndTrimmedRoute(
                        currentLat = latestGps.latitude,
                        currentLon = latestGps.longitude,
                        routePoints = outcome.points,
                        targetLat = target.targetLat,
                        targetLon = target.targetLon
                    )
                } else {
                    outcome.distanceMeters to outcome.points
                }

                if (latestGps.hasFix && WalkingNavigationPolicy.hasArrivedAtStart(
                        latestGps.latitude,
                        latestGps.longitude,
                        target.targetLat,
                        target.targetLon
                    )
                ) {
                    triggerArrivedClear(target.label)
                    return@onSuccess
                }

                _walkingRouteState.update { cur ->
                    if (cur.target != target) cur
                    else cur.copy(
                        routePoints = trimmed,
                        routeDistanceMeters = outcome.distanceMeters,
                        remainingDistanceMeters = remaining,
                        estimatedWalkSeconds = (remaining / OfflineRoadGraph.WALKING_SPEED_MPS)
                            .toLong()
                            .coerceAtLeast(1L),
                        isCalculating = false,
                        modeUsed = mode,
                        providerLabel = outcome.providerLabel,
                        errorMessage = null,
                        lastRoutedLat = startLat,
                        lastRoutedLon = startLon,
                        lastRoutedAtMillis = requestTime
                    )
                }
            }.onFailure { err ->
                _walkingRouteState.update { cur ->
                    if (cur.target != target) cur
                    else cur.copy(
                        isCalculating = false,
                        modeUsed = mode,
                        errorMessage = err.localizedMessage ?: "Unable to calculate walking route",
                        lastRoutedLat = startLat,
                        lastRoutedLon = startLon,
                        lastRoutedAtMillis = requestTime
                    )
                }
            }
        }
    }

    fun retryWalkingRoute() {
        val target = _walkingRouteState.value.target ?: return
        val gps = _locationTelemetry.value
        if (gps.hasFix) {
            requestWalkingRouteCalculation(
                gps.latitude,
                gps.longitude,
                target,
                mapDisplaySettings.value.routingMode
            )
        }
    }

    fun onRouteDeleted(routeId: Long) {
        if (_highlightedRouteId.value == routeId) {
            _highlightedRouteId.value = null
        }
        val activeTarget = _walkingRouteState.value.target
        if (activeTarget is WalkTarget.SavedTrackStart && activeTarget.routeId == routeId) {
            stopWalkingNavigation()
        }
    }

    fun onMarkerDeleted(markerId: Long) {
        val activeTarget = _walkingRouteState.value.target
        if (activeTarget is WalkTarget.SavedPoint && activeTarget.markerId == markerId) {
            stopWalkingNavigation()
        }
    }

    fun snapRouteToRoads(routeId: Long) {
        if (_snappingRouteId.value != null) return
        _snappingRouteId.value = routeId
        scope.launch {
            try {
                val rawPoints = repository.getRoutePointsOnce(routeId)
                if (rawPoints.size >= 2) {
                    val asDomain = rawPoints.map {
                        LatLonPoint(
                            latitude = it.latitude,
                            longitude = it.longitude,
                            speedKmh = it.speedKmh,
                            timestamp = it.timestamp
                        )
                    }
                    val snapped = roadSnapper.snapPolyline(asDomain)
                    repository.replaceRoutePoints(routeId, snapped)
                }
            } finally {
                if (_snappingRouteId.value == routeId) {
                    _snappingRouteId.value = null
                }
            }
        }
    }

    fun setBackgroundTrackingEnabled(enabled: Boolean) {
        scope.launch {
            preferences.setBackgroundTrackingEnabled(enabled)
            syncTrackingAndServiceState()
        }
    }

    fun setSnapToRoadsEnabled(enabled: Boolean) {
        scope.launch {
            preferences.setSnapToRoadsEnabled(enabled)
            if (!enabled) {
                roadSnapper.resetContinuity()
            }
        }
    }
}
