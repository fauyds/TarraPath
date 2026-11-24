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
import com.example.data.model.LocationSearchState
import com.example.data.model.LocationTelemetry
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MarkerCategory
import com.example.data.model.MarkerStyle
import com.example.data.model.OfflineDownloadProgress
import com.example.data.model.RoutingMode
import com.example.data.model.SearchPlaceResult
import com.example.data.model.ThemeMode
import com.example.data.model.WalkingRouteState
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.remote.ApiKeyRejectedException
import com.example.data.remote.LocationSearchService
import com.example.data.remote.WalkingRouteService
import com.example.data.repository.OfflineRegionManager
import com.example.data.repository.TerraRepository
import com.example.data.sensors.CompassHeadingTracker
import com.example.data.sensors.LocationTracker
import com.example.data.sensors.StepSensorTracker
import com.example.domain.GeoGrid
import com.example.service.TrackingCoordinator
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

typealias PendingRouteSummary = com.example.data.model.PendingRouteSummary

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TerraViewModel(
    private val repository: TerraRepository,
    private val preferences: UserPreferencesRepository,
    private val locationTracker: LocationTracker,
    private val stepSensorTracker: StepSensorTracker,
    private val locationSearchService: LocationSearchService = LocationSearchService(),
    private val compassHeadingTracker: CompassHeadingTracker? = null,
    private val walkingRouteService: WalkingRouteService = WalkingRouteService(),
    private val offlineRegionManager: OfflineRegionManager? = null,
    private val trackingCoordinator: TrackingCoordinator = TrackingCoordinator(
        repository = repository,
        preferences = preferences,
        locationTracker = locationTracker,
        stepSensorTracker = stepSensorTracker,
        compassHeadingTracker = compassHeadingTracker,
        walkingRouteService = walkingRouteService,
        offlineRegionManager = offlineRegionManager
    )
) : ViewModel() {

    val locationTelemetry: StateFlow<LocationTelemetry> = trackingCoordinator.locationTelemetry
    val activeRecording: StateFlow<ActiveRecordingState> = trackingCoordinator.activeRecording
    val pendingRouteSummary: StateFlow<PendingRouteSummary?> = trackingCoordinator.pendingRouteSummary
    val highlightedRouteId: StateFlow<Long?> = trackingCoordinator.highlightedRouteId
    val walkingRouteState: StateFlow<WalkingRouteState> = trackingCoordinator.walkingRouteState
    val snappingRouteId: StateFlow<Long?> = trackingCoordinator.snappingRouteId
    val activeAchievementBanner: StateFlow<AchievementDefinition?> = trackingCoordinator.activeAchievementBanner
    val isBackgroundServiceRunning: StateFlow<Boolean> = trackingCoordinator.isBackgroundServiceRunning

    private val _locationSearchState = MutableStateFlow(LocationSearchState())
    val locationSearchState: StateFlow<LocationSearchState> = _locationSearchState.asStateFlow()

    private val _selectedSearchPlace = MutableStateFlow<SearchPlaceResult?>(null)
    val selectedSearchPlace: StateFlow<SearchPlaceResult?> = _selectedSearchPlace.asStateFlow()

    private val _apiKeyTestState = MutableStateFlow<ApiKeyTestState>(ApiKeyTestState.Idle)
    val apiKeyTestState: StateFlow<ApiKeyTestState> = _apiKeyTestState.asStateFlow()

    val offlineRegions: StateFlow<List<OfflineRegionEntity>> =
        (offlineRegionManager?.allRegions ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val offlineDownloadProgress: StateFlow<OfflineDownloadProgress> =
        offlineRegionManager?.downloadProgress ?: MutableStateFlow(OfflineDownloadProgress())

    val highlightedRoutePoints: StateFlow<List<RoutePointEntity>> = highlightedRouteId
        .flatMapLatest { routeId ->
            if (routeId == null) flowOf(emptyList())
            else repository.observeRoutePoints(routeId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val themeMode: StateFlow<ThemeMode> = preferences.themeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val customAccentColorArgb: StateFlow<Long?> = preferences.customAccentColorArgb
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val apiKey: StateFlow<String> = trackingCoordinator.apiKey
    val apiProvider: StateFlow<ApiProvider> = trackingCoordinator.apiProvider
    val mapDisplaySettings: StateFlow<MapDisplaySettings> = trackingCoordinator.mapDisplaySettings

    val hiddenMarkerCategories: StateFlow<Set<String>> = preferences.hiddenMarkerCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val exploredChunks: StateFlow<List<ExploredChunkEntity>> = trackingCoordinator.exploredChunks
    val totalChunkCount: StateFlow<Int> = trackingCoordinator.totalChunkCount

    val currentDistrictInfo: StateFlow<DistrictInfo> = combine(
        locationTelemetry,
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

    val allRoutes: StateFlow<List<RouteEntity>> = trackingCoordinator.allRoutes

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

    private var searchJob: Job? = null

    fun updatePermissions(hasLocation: Boolean, hasActivityRecognition: Boolean) {
        trackingCoordinator.updatePermissions(hasLocation, hasActivityRecognition)
    }

    fun setForegroundUiActive(active: Boolean) {
        trackingCoordinator.setForegroundUiActive(active)
    }

    fun dismissCurrentAchievementBanner() {
        trackingCoordinator.dismissCurrentAchievementBanner()
    }

    fun startRouteRecording() {
        trackingCoordinator.startRouteRecording()
    }

    fun stopRouteRecordingAndPrompt() {
        trackingCoordinator.stopRouteRecordingAndPrompt()
    }

    fun confirmSaveRoute(name: String, colorArgb: Long) {
        trackingCoordinator.confirmSaveRoute(name, colorArgb)
    }

    fun discardPendingRoute() {
        trackingCoordinator.discardPendingRoute()
    }

    fun highlightRouteOnMap(routeId: Long?) {
        trackingCoordinator.highlightRouteOnMap(routeId)
    }

    fun snapRouteToRoads(routeId: Long) {
        trackingCoordinator.snapRouteToRoads(routeId)
    }

    fun startWalkingToMarker(marker: MapMarkerEntity) {
        trackingCoordinator.startWalkingToMarker(marker)
    }

    fun startWalkingToTrackStart(routeId: Long) {
        trackingCoordinator.startWalkingToTrackStart(routeId)
    }

    fun startWalkingToSearchPlace(place: SearchPlaceResult) {
        trackingCoordinator.startWalkingToSearchPlace(place)
    }

    fun startWalkingToCoordinate(label: String, latitude: Double, longitude: Double) {
        trackingCoordinator.startWalkingToCoordinate(label, latitude, longitude)
    }

    fun stopWalkingNavigation() {
        trackingCoordinator.stopWalkingNavigation()
    }

    fun dismissArrivalBanner() {
        trackingCoordinator.dismissArrivalBanner()
    }

    fun deleteRoute(routeId: Long) {
        viewModelScope.launch {
            trackingCoordinator.onRouteDeleted(routeId)
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
            trackingCoordinator.onMarkerDeleted(markerId)
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

    fun setBackgroundTrackingEnabled(enabled: Boolean) {
        trackingCoordinator.setBackgroundTrackingEnabled(enabled)
    }

    fun setSnapToRoadsEnabled(enabled: Boolean) {
        trackingCoordinator.setSnapToRoadsEnabled(enabled)
    }

    fun setMarkerStyle(style: MarkerStyle) {
        viewModelScope.launch { preferences.setMarkerStyle(style) }
    }

    fun setRoutingMode(mode: RoutingMode) {
        viewModelScope.launch {
            preferences.setRoutingMode(mode)
            val target = walkingRouteState.value.target
            val gps = locationTelemetry.value
            if (target != null && gps.hasFix) {
                trackingCoordinator.requestWalkingRouteCalculation(gps.latitude, gps.longitude, target, mode)
            }
        }
    }

    fun retryWalkingRoute() {
        trackingCoordinator.retryWalkingRoute()
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
                val target = walkingRouteState.value.target
                val gps = locationTelemetry.value
                if (target != null && gps.hasFix && mapDisplaySettings.value.routingMode == RoutingMode.OFFLINE) {
                    trackingCoordinator.requestWalkingRouteCalculation(gps.latitude, gps.longitude, target, RoutingMode.OFFLINE)
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
        val telemetry = locationTelemetry.value
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
                offlineRegionManager = container.offlineRegionManager,
                trackingCoordinator = container.trackingCoordinator
            ) as T
        }
    }
}
