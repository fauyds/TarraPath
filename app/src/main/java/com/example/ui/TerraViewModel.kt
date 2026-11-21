package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.local.ExploredChunkEntity
import com.example.data.local.ExplorerStatsEntity
import com.example.data.local.MapMarkerEntity
import com.example.data.local.OfflineRegionEntity
import com.example.data.local.RouteEntity
import com.example.data.local.RoutePointEntity
import com.example.data.model.AchievementCatalog
import com.example.data.model.AchievementDefinition
import com.example.data.model.AchievementUiItem
import com.example.data.model.ActiveRecordingState
import com.example.data.model.ApiKeyTestState
import com.example.data.model.ApiProvider
import com.example.data.model.DistrictInfo
import com.example.data.model.LatLonPoint
import com.example.data.model.LocationSearchState
import com.example.data.model.LocationTelemetry
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MarkerCategory
import com.example.data.model.MarkerStyle
import com.example.data.model.OfflineDownloadProgress
import com.example.data.model.RoutingMode
import com.example.data.model.SearchPlaceResult
import com.example.data.model.ThemeMode
import com.example.data.model.WalkTarget
import com.example.data.model.WalkingRouteState
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.ApiKeyRejectedException
import com.example.data.remote.LocationSearchService
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
import com.example.domain.WalkingNavigationPolicy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingRouteSummary(
    val distanceMeters: Double,
    val durationMillis: Long,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val chunksUnlocked: Int,
    val startedAt: Long,
    val endedAt: Long,
    val points: List<LatLonPoint>
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TerraViewModel(
    private val repository: TerraRepository,
    private val preferences: UserPreferencesRepository,
    private val locationTracker: LocationTracker,
    private val stepSensorTracker: StepSensorTracker,
    private val locationSearchService: LocationSearchService = LocationSearchService(),
    private val compassHeadingTracker: CompassHeadingTracker? = null,
    private val walkingRouteService: WalkingRouteService = WalkingRouteService(),
    private val offlineRegionManager: OfflineRegionManager? = null
) : ViewModel() {

    private val _hasLocationPermission = MutableStateFlow(false)
    private val _hasActivityPermission = MutableStateFlow(false)

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

    private val _locationSearchState = MutableStateFlow(LocationSearchState())
    val locationSearchState: StateFlow<LocationSearchState> = _locationSearchState.asStateFlow()

    private val _selectedSearchPlace = MutableStateFlow<SearchPlaceResult?>(null)
    val selectedSearchPlace: StateFlow<SearchPlaceResult?> = _selectedSearchPlace.asStateFlow()

    private val _apiKeyTestState = MutableStateFlow<ApiKeyTestState>(ApiKeyTestState.Idle)
    val apiKeyTestState: StateFlow<ApiKeyTestState> = _apiKeyTestState.asStateFlow()

    private val _walkingRouteState = MutableStateFlow(WalkingRouteState())
    val walkingRouteState: StateFlow<WalkingRouteState> = _walkingRouteState.asStateFlow()

    val offlineRegions: StateFlow<List<OfflineRegionEntity>> =
        (offlineRegionManager?.allRegions ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val offlineDownloadProgress: StateFlow<OfflineDownloadProgress> =
        offlineRegionManager?.downloadProgress ?: MutableStateFlow(OfflineDownloadProgress())

    val highlightedRoutePoints: StateFlow<List<RoutePointEntity>> = _highlightedRouteId
        .flatMapLatest { routeId ->
            if (routeId == null) flowOf(emptyList())
            else repository.observeRoutePoints(routeId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _unlockedToastQueue = MutableStateFlow<List<AchievementDefinition>>(emptyList())
    val activeAchievementBanner: StateFlow<AchievementDefinition?> = _unlockedToastQueue
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val themeMode: StateFlow<ThemeMode> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val customAccentColorArgb: StateFlow<Long?> = preferences.customAccentColorArgb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val apiKey: StateFlow<String> = preferences.apiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val apiProvider: StateFlow<ApiProvider> = preferences.apiProvider
        .stateIn(viewModelScope, SharingStarted.Eagerly, ApiProvider.CUSTOM_GENERAL)

    val mapDisplaySettings: StateFlow<MapDisplaySettings> = preferences.mapDisplaySettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MapDisplaySettings())

    val hiddenMarkerCategories: StateFlow<Set<String>> = preferences.hiddenMarkerCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val exploredChunks: StateFlow<List<ExploredChunkEntity>> = repository.exploredChunks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalChunkCount: StateFlow<Int> = repository.totalChunkCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val currentDistrictInfo: StateFlow<DistrictInfo> = combine(
        _locationTelemetry,
        exploredChunks
    ) { telemetry, chunks ->
        if (!telemetry.hasFix) {
            val latestChunk = chunks.firstOrNull()
            if (latestChunk != null) {
                val info = GeoGrid.chunkToDistrict(latestChunk.chunkX, latestChunk.chunkY)
                val count = chunks.count { it.districtKey == info.key }
                info.copy(unlockedCount = count)
            } else {
                DistrictInfo(dx = 0, dy = 0, displayNumber = 10, unlockedCount = 0)
            }
        } else {
            val coord = GeoGrid.latLonToChunk(telemetry.latitude, telemetry.longitude)
            val info = GeoGrid.chunkToDistrict(coord.cx, coord.cy)
            val count = chunks.count { it.districtKey == info.key }
            info.copy(unlockedCount = count)
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        DistrictInfo(0, 0, 10, 0)
    )

    val allRoutes: StateFlow<List<RouteEntity>> = repository.allRoutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val allMarkers: StateFlow<List<MapMarkerEntity>> = repository.allMarkers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val explorerStats: StateFlow<ExplorerStatsEntity> = repository.explorerStats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExplorerStatsEntity())

    val achievements: StateFlow<List<AchievementUiItem>> = combine(
        explorerStats,
        totalChunkCount,
        repository.achievementUnlocks
    ) { stats, chunkCount, unlocks ->
        val unlockMap = unlocks.associateBy { it.achievementId }
        AchievementCatalog.all.map { def ->
            val unlock = unlockMap[def.id]
            val currentVal = when (def.category) {
                com.example.data.model.AchievementCategory.DISTANCE -> stats.totalDistanceMeters
                com.example.data.model.AchievementCategory.STEPS -> stats.totalSteps.toDouble()
                com.example.data.model.AchievementCategory.SPEED -> stats.maxSpeedKmh.toDouble()
                com.example.data.model.AchievementCategory.EXPLORER -> chunkCount.toDouble()
            }
            AchievementUiItem(
                definition = def,
                currentValue = if (unlock != null) maxOf(currentVal, def.targetValue) else currentVal,
                isUnlocked = unlock != null,
                unlockedAt = unlock?.unlockedAt
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var stepJob: Job? = null
    private var timerJob: Job? = null
    private var searchJob: Job? = null
    private var walkingRouteJob: Job? = null
    private var arrivalBannerJob: Job? = null
    private var fullCalculatedWalkPoints: List<LatLonPoint> = emptyList()
    private var lastRecordedTelemetry: LocationTelemetry? = null

    init {
        viewModelScope.launch {
            repository.ensureDefaultDataSeeded()
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
        if (hasLocation != _hasLocationPermission.value) {
            _hasLocationPermission.value = hasLocation
            latestGpsTelemetry = latestGpsTelemetry.copy(hasPermission = hasLocation)
            publishMergedTelemetry()
            if (hasLocation) {
                startLocationCollection()
                startHeadingCollection()
            } else {
                locationJob?.cancel()
                headingJob?.cancel()
            }
        }
        if (hasActivityRecognition != _hasActivityPermission.value) {
            _hasActivityPermission.value = hasActivityRecognition
            if (hasActivityRecognition) {
                startStepCollection()
            } else {
                stepJob?.cancel()
            }
        }
    }

    private fun startLocationCollection() {
        locationJob?.cancel()
        locationJob = viewModelScope.launch {
            locationTracker.observeLocationUpdates(true).collect { telemetry ->
                latestGpsTelemetry = telemetry
                publishMergedTelemetry()
                if (telemetry.hasFix) {
                    processGpsFix(telemetry)
                }
            }
        }
    }

    private fun startHeadingCollection() {
        headingJob?.cancel()
        val tracker = compassHeadingTracker ?: return
        headingJob = viewModelScope.launch {
            tracker.observeHeadingDegrees().collect { compassDeg ->
                latestCompassHeadingDeg = compassDeg
                if (latestGpsTelemetry.hasFix) {
                    publishMergedTelemetry()
                }
            }
        }
    }

    private suspend fun processGpsFix(telemetry: LocationTelemetry) {
        val unlockedNew = repository.unlockChunkAt(telemetry.latitude, telemetry.longitude)

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
            // Filter residual micro-drift (< 2.5m) and unrealistic walking speed spikes (> 8.5 m/s)
            if (d >= 2.5 && d <= 200.0 && impliedSpeedMps <= 8.5) {
                distDelta = d
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
                        distFromLastPt <= 200.0 &&
                        (distFromLastPt / dtPtSec) <= 8.5
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
                    chunksUnlockedDuringRoute = state.chunksUnlockedDuringRoute + if (unlockedNew) 1 else 0,
                    points = newPoints
                )
            }
        }

        val chunkTotal = totalChunkCount.value + if (unlockedNew) 1 else 0
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
        stepJob = viewModelScope.launch {
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
                    val unlockedNow = repository.recordTelemetryProgress(
                        distanceDeltaMeters = 0.0,
                        stepsDelta = delta,
                        currentSpeedKmh = _locationTelemetry.value.speedKmh,
                        totalChunksUnlocked = totalChunkCount.value
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
        timerJob = viewModelScope.launch {
            while (true) {
                delay(500L)
                val rec = _activeRecording.value
                if (!rec.isRecording) break
                _activeRecording.update {
                    it.copy(elapsedMillis = System.currentTimeMillis() - it.startedAtMillis)
                }
            }
        }
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
    }

    fun confirmSaveRoute(name: String, colorArgb: Long) {
        val summary = _pendingRouteSummary.value ?: return
        _pendingRouteSummary.value = null
        viewModelScope.launch {
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
            viewModelScope.launch {
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
        viewModelScope.launch {
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
        arrivalBannerJob = viewModelScope.launch {
            delay(4000L)
            _walkingRouteState.update { it.copy(arrivedBannerLabel = null) }
        }
    }

    private fun requestWalkingRouteCalculation(
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
        walkingRouteJob = viewModelScope.launch {
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

    fun deleteRoute(routeId: Long) {
        viewModelScope.launch {
            if (_highlightedRouteId.value == routeId) {
                _highlightedRouteId.value = null
            }
            val activeTarget = _walkingRouteState.value.target
            if (activeTarget is WalkTarget.SavedTrackStart && activeTarget.routeId == routeId) {
                stopWalkingNavigation()
            }
            repository.deleteRoute(routeId)
        }
    }

    fun observeRoute(routeId: Long) = repository.observeRoute(routeId)
    fun observeRoutePoints(routeId: Long) = repository.observeRoutePoints(routeId)

    fun addMarker(
        title: String,
        note: String,
        category: MarkerCategory,
        latitude: Double,
        longitude: Double,
        colorArgb: Long = category.defaultColorArgb,
        iconName: String = category.defaultIconName
    ) {
        viewModelScope.launch {
            repository.addMarker(title, note, category, latitude, longitude, colorArgb, iconName)
        }
    }

    fun updateMarkerIcon(marker: MapMarkerEntity, newIconName: String) {
        viewModelScope.launch {
            repository.updateMarkerIcon(marker, newIconName)
        }
    }

    fun updateMarkerColor(marker: MapMarkerEntity, newColorArgb: Long) {
        viewModelScope.launch {
            repository.updateMarkerColor(marker, newColorArgb)
        }
    }

    fun deleteMarker(markerId: Long) {
        viewModelScope.launch {
            val activeTarget = _walkingRouteState.value.target
            if (activeTarget is WalkTarget.SavedPoint && activeTarget.markerId == markerId) {
                stopWalkingNavigation()
            }
            repository.deleteMarker(markerId)
        }
    }

    fun toggleMarkerCategory(category: MarkerCategory) {
        viewModelScope.launch {
            preferences.toggleMarkerCategoryVisibility(category.name)
        }
    }

    fun showAllMarkers() {
        viewModelScope.launch {
            preferences.showAllMarkerCategories()
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { preferences.setThemeMode(mode) }
    }

    fun setCustomAccentColor(colorArgb: Long?) {
        viewModelScope.launch { preferences.setCustomAccentColor(colorArgb) }
    }

    fun saveApiKeyConfig(key: String, provider: ApiProvider) {
        viewModelScope.launch {
            preferences.setApiProvider(provider)
            preferences.setApiKey(LocationSearchService.sanitizeApiKey(key))
            _apiKeyTestState.value = ApiKeyTestState.Idle
        }
    }

    fun clearApiKey() {
        viewModelScope.launch {
            preferences.setApiKey("")
            _apiKeyTestState.value = ApiKeyTestState.Idle
        }
    }

    fun testApiKey(key: String, provider: ApiProvider) {
        viewModelScope.launch {
            _apiKeyTestState.value = ApiKeyTestState.Testing
            val result = locationSearchService.verifyApiKey(key, provider)
            _apiKeyTestState.value = result.fold(
                onSuccess = { msg -> ApiKeyTestState.Verified(msg) },
                onFailure = { err ->
                    ApiKeyTestState.Failed(
                        err.localizedMessage ?: "Verification failed. Check your connection and key."
                    )
                }
            )
        }
    }

    fun resetApiKeyTestState() {
        _apiKeyTestState.value = ApiKeyTestState.Idle
    }

    fun setEasyModeEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setEasyModeEnabled(enabled) }
    }

    fun setShowGridLines(show: Boolean) {
        viewModelScope.launch { preferences.setShowGridLines(show) }
    }

    fun setShowStreetNames(show: Boolean) {
        viewModelScope.launch { preferences.setShowStreetNames(show) }
    }

    fun setShowMarkerLabels(show: Boolean) {
        viewModelScope.launch {
            preferences.setShowMarkerLabels(show)
            if (show) {
                preferences.setDisableCreatedMarkerLabels(false)
            }
        }
    }

    fun setDisableCreatedMarkerLabels(disabled: Boolean) {
        viewModelScope.launch { preferences.setDisableCreatedMarkerLabels(disabled) }
    }

    fun setShowSearchLabels(show: Boolean) {
        viewModelScope.launch { preferences.setShowSearchLabels(show) }
    }

    fun setShowSectorLabels(show: Boolean) {
        viewModelScope.launch { preferences.setShowSectorLabels(show) }
    }

    fun setSmoothFadeEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSmoothFadeEnabled(enabled) }
    }

    fun setUiBlurEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setUiBlurEnabled(enabled) }
    }

    fun setUiTransparency(transparency: Float) {
        viewModelScope.launch { preferences.setUiTransparency(transparency) }
    }

    fun setUiBlurRadiusDp(radiusDp: Float) {
        viewModelScope.launch { preferences.setUiBlurRadiusDp(radiusDp) }
    }

    fun setShowLocationOutline(show: Boolean) {
        viewModelScope.launch { preferences.setShowLocationOutline(show) }
    }

    fun setMarkerStyle(style: MarkerStyle) {
        viewModelScope.launch { preferences.setMarkerStyle(style) }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            preferences.setRoutingMode(mode)
            val target = _walkingRouteState.value.target
            val gps = _locationTelemetry.value
            if (target != null && gps.hasFix) {
                requestWalkingRouteCalculation(gps.latitude, gps.longitude, target, mode)
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

    fun downloadOfflineArea(
        regionName: String,
        centerLat: Double,
        centerLon: Double,
        radiusMeters: Int,
        isDarkTheme: Boolean
    ) {
        val manager = offlineRegionManager ?: return
        viewModelScope.launch {
            val res = manager.downloadOfflineArea(
                regionName = regionName,
                centerLat = centerLat,
                centerLon = centerLon,
                radiusMeters = radiusMeters,
                isDarkTheme = isDarkTheme,
                showStreetNames = mapDisplaySettings.value.showStreetNames,
                apiKey = apiKey.value,
                apiProvider = apiProvider.value
            )
            if (res.isSuccess) {
                val target = _walkingRouteState.value.target
                val gps = _locationTelemetry.value
                if (target != null && gps.hasFix && mapDisplaySettings.value.routingMode == RoutingMode.OFFLINE) {
                    requestWalkingRouteCalculation(gps.latitude, gps.longitude, target, RoutingMode.OFFLINE)
                }
            }
        }
    }

    fun deleteOfflineRegion(region: OfflineRegionEntity) {
        viewModelScope.launch {
            offlineRegionManager?.deleteRegion(region)
        }
    }

    fun clearOfflineDownloadError() {
        offlineRegionManager?.clearDownloadError()
    }

    fun onSearchQueryChanged(query: String, biasLat: Double? = null, biasLon: Double? = null) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _locationSearchState.value = LocationSearchState(query = query)
            return
        }

        val coordMatch = LocationSearchService.parseCoordinateQuery(trimmed)
        val localPins = LocationSearchService.searchLocalMarkers(trimmed, allMarkers.value)
        val instantResults = buildList {
            if (coordMatch != null) add(coordMatch)
            addAll(localPins)
        }

        if (coordMatch != null && trimmed.none { it.isLetter() }) {
            _locationSearchState.value = LocationSearchState(
                query = query,
                isSearching = false,
                results = instantResults,
                errorMessage = null,
                hasSearched = true
            )
            return
        }

        if (trimmed.length < 2) {
            _locationSearchState.value = LocationSearchState(
                query = query,
                isSearching = false,
                results = instantResults,
                errorMessage = null,
                hasSearched = instantResults.isNotEmpty()
            )
            return
        }

        _locationSearchState.value = LocationSearchState(
            query = query,
            isSearching = true,
            results = instantResults,
            errorMessage = null,
            hasSearched = false
        )

        searchJob = viewModelScope.launch {
            delay(380L)
            executeRemoteSearch(query = trimmed, localResults = instantResults, biasLat = biasLat, biasLon = biasLon)
        }
    }

    fun submitLocationSearch(biasLat: Double? = null, biasLon: Double? = null) {
        val trimmed = _locationSearchState.value.query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        val coordMatch = LocationSearchService.parseCoordinateQuery(trimmed)
        val localPins = LocationSearchService.searchLocalMarkers(trimmed, allMarkers.value)
        val instantResults = buildList {
            if (coordMatch != null) add(coordMatch)
            addAll(localPins)
        }
        if (coordMatch != null && trimmed.none { it.isLetter() }) {
            _locationSearchState.value = _locationSearchState.value.copy(
                isSearching = false,
                results = instantResults,
                errorMessage = null,
                hasSearched = true
            )
            return
        }
        _locationSearchState.value = _locationSearchState.value.copy(
            isSearching = true,
            results = instantResults,
            errorMessage = null
        )
        searchJob = viewModelScope.launch {
            executeRemoteSearch(query = trimmed, localResults = instantResults, biasLat = biasLat, biasLon = biasLon)
        }
    }

    private suspend fun executeRemoteSearch(
        query: String,
        localResults: List<SearchPlaceResult>,
        biasLat: Double?,
        biasLon: Double?
    ) {
        val telemetry = _locationTelemetry.value
        val effectiveLat = biasLat ?: if (telemetry.hasFix) telemetry.latitude else null
        val effectiveLon = biasLon ?: if (telemetry.hasFix) telemetry.longitude else null
        val activeKey = preferences.apiKey.first()
        val activeProvider = preferences.apiProvider.first()
        val remote = locationSearchService.searchRemotePlacesWithProvider(
            query = query,
            biasLat = effectiveLat,
            biasLon = effectiveLon,
            apiKey = activeKey,
            apiProvider = activeProvider
        )
        remote.onSuccess { outcome ->
            _locationSearchState.update { state ->
                if (state.query.trim() != query) state
                else state.copy(
                    isSearching = false,
                    results = (localResults + outcome.results).distinctBy { it.id },
                    errorMessage = null,
                    hasSearched = true,
                    activeProviderLabel = outcome.providerLabel
                )
            }
        }.onFailure { err ->
            val keyRejected = err is ApiKeyRejectedException
            _locationSearchState.update { state ->
                if (state.query.trim() != query) state
                else state.copy(
                    isSearching = false,
                    results = localResults,
                    errorMessage = if (keyRejected || localResults.isEmpty()) {
                        err.localizedMessage ?: "Search unavailable offline"
                    } else null,
                    hasSearched = true,
                    activeProviderLabel = null
                )
            }
        }
    }

    fun selectSearchResult(result: SearchPlaceResult) {
        _selectedSearchPlace.value = result
    }

    fun clearSelectedSearchPlace() {
        _selectedSearchPlace.value = null
    }

    fun clearLocationSearch() {
        searchJob?.cancel()
        _locationSearchState.value = LocationSearchState()
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TerraViewModel(
                repository = container.repository,
                preferences = container.preferences,
                locationTracker = container.locationTracker,
                stepSensorTracker = container.stepSensorTracker,
                locationSearchService = container.locationSearchService,
                compassHeadingTracker = container.compassHeadingTracker,
                walkingRouteService = container.walkingRouteService,
                offlineRegionManager = container.offlineRegionManager
            ) as T
        }
    }
}
