package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppContainer
import com.example.data.local.ExploredChunkEntity
import com.example.data.local.ExplorerStatsEntity
import com.example.data.local.MapMarkerEntity
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
import com.example.data.model.SearchPlaceResult
import com.example.data.model.ThemeMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.ApiKeyRejectedException
import com.example.data.remote.LocationSearchService
import com.example.data.repository.TerraRepository
import com.example.data.sensors.LocationTracker
import com.example.data.sensors.StepSensorEvent
import com.example.data.sensors.StepSensorTracker
import com.example.domain.GeoGrid
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
    private val locationSearchService: LocationSearchService = LocationSearchService()
) : ViewModel() {

    private val _hasLocationPermission = MutableStateFlow(false)
    private val _hasActivityPermission = MutableStateFlow(false)

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
    private var stepJob: Job? = null
    private var timerJob: Job? = null
    private var searchJob: Job? = null
    private var lastRecordedTelemetry: LocationTelemetry? = null

    init {
        viewModelScope.launch {
            repository.ensureDefaultDataSeeded()
        }
    }

    fun updatePermissions(hasLocation: Boolean, hasActivityRecognition: Boolean) {
        if (hasLocation != _hasLocationPermission.value) {
            _hasLocationPermission.value = hasLocation
            _locationTelemetry.update { it.copy(hasPermission = hasLocation) }
            if (hasLocation) {
                startLocationCollection()
            } else {
                locationJob?.cancel()
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
                _locationTelemetry.value = telemetry
                if (telemetry.hasFix) {
                    processGpsFix(telemetry)
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
            // Filter GPS jitter (< 1.2m) and unrealistic jumps (> 300m in a single tick)
            if (d in 1.2..300.0) {
                distDelta = d
            }
        }
        lastRecordedTelemetry = telemetry

        if (_activeRecording.value.isRecording) {
            _activeRecording.update { state ->
                val lastPt = state.points.lastOrNull()
                val shouldAppendPoint = lastPt == null || GeoGrid.haversineMeters(
                    lastPt.latitude,
                    lastPt.longitude,
                    telemetry.latitude,
                    telemetry.longitude
                ) >= 2.0

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
    }

    fun deleteRoute(routeId: Long) {
        viewModelScope.launch {
            if (_highlightedRouteId.value == routeId) {
                _highlightedRouteId.value = null
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
            preferences.setApiKey(key)
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

    fun setMarkerStyle(style: MarkerStyle) {
        viewModelScope.launch { preferences.setMarkerStyle(style) }
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
                locationSearchService = container.locationSearchService
            ) as T
        }
    }
}
