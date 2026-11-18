package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.ExploredChunkEntity
import com.example.data.local.ExplorerStatsEntity
import com.example.data.local.MapMarkerEntity
import com.example.data.local.RouteEntity
import com.example.data.local.RoutePointEntity
import com.example.data.model.ActiveRecordingState
import com.example.data.model.ApiProvider
import com.example.data.model.DistrictInfo
import com.example.data.model.LatLonPoint
import com.example.data.model.LocationSearchState
import com.example.data.model.LocationTelemetry
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MapPalette
import com.example.data.model.MarkerCategory
import com.example.data.model.MarkerStyle
import com.example.data.model.SearchPlaceResult
import com.example.domain.GeoGrid
import com.example.ui.PendingRouteSummary
import com.example.ui.components.ApplyDialogBlurIfEnabled
import com.example.ui.components.ColorPickerMath
import com.example.ui.components.CustomColorPickerDialog
import com.example.ui.components.ExpressiveCard
import com.example.ui.components.ExpressivePillButton
import com.example.ui.components.GoogleIconLibraryDialog
import com.example.ui.components.LocationSearchBarAndResults
import com.example.ui.components.MarkerStylePreviewBadge
import com.example.ui.components.SelectedSearchPlaceBanner
import com.example.ui.components.conditionalBlur
import com.example.ui.map.MapControlCommands
import com.example.ui.map.TerraMapCanvas
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle
import kotlinx.coroutines.delay
import kotlin.math.abs

private data class DropMarkerDraft(
    val latitude: Double,
    val longitude: Double,
    val initialTitle: String? = null,
    val initialCategory: MarkerCategory? = null
)

@Composable
fun ExploreScreen(
    palette: MapPalette,
    isDarkTheme: Boolean,
    mapSettings: MapDisplaySettings,
    apiKey: String = "",
    apiProvider: ApiProvider = ApiProvider.CUSTOM_GENERAL,
    telemetry: LocationTelemetry,
    districtInfo: DistrictInfo,
    exploredChunks: List<ExploredChunkEntity>,
    explorerStats: ExplorerStatsEntity,
    activeRecording: ActiveRecordingState,
    pendingRouteSummary: PendingRouteSummary?,
    highlightedRoute: RouteEntity?,
    highlightedRoutePoints: List<RoutePointEntity>,
    markers: List<MapMarkerEntity>,
    hiddenMarkerCategories: Set<String>,
    locationSearchState: LocationSearchState,
    selectedSearchPlace: SearchPlaceResult?,
    onSearchQueryChange: (query: String, biasLat: Double, biasLon: Double) -> Unit,
    onSubmitSearch: (biasLat: Double, biasLon: Double) -> Unit,
    onClearSearch: () -> Unit,
    onSelectSearchResult: (SearchPlaceResult) -> Unit,
    onClearSelectedSearchPlace: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartRoute: () -> Unit,
    onStopRoute: () -> Unit,
    onConfirmSaveRoute: (name: String, colorArgb: Long) -> Unit,
    onDiscardRoute: () -> Unit,
    onClearHighlightedRoute: () -> Unit,
    onAddMarker: (title: String, note: String, category: MarkerCategory, lat: Double, lon: Double, colorArgb: Long, iconName: String) -> Unit,
    onUpdateMarkerIcon: (MapMarkerEntity, String) -> Unit,
    onUpdateMarkerColor: (MapMarkerEntity, Long) -> Unit,
    onDeleteMarker: (Long) -> Unit,
    onToggleMarkerCategory: (MarkerCategory) -> Unit,
    onShowAllMarkerCategories: () -> Unit,
    modifier: Modifier = Modifier
) {
    val initialCenter = remember(telemetry.hasFix, exploredChunks.size, highlightedRoutePoints.size) {
        when {
            telemetry.hasFix -> telemetry.latitude to telemetry.longitude
            highlightedRoutePoints.isNotEmpty() -> highlightedRoutePoints.first().latitude to highlightedRoutePoints.first().longitude
            exploredChunks.isNotEmpty() -> exploredChunks.first().centerLat to exploredChunks.first().centerLon
            else -> 37.7749 to -122.4194
        }
    }

    var cameraLat by remember { mutableDoubleStateOf(initialCenter.first) }
    var cameraLon by remember { mutableDoubleStateOf(initialCenter.second) }
    var followUser by remember { mutableStateOf(true) }
    val mapCommands = remember { MapControlCommands() }

    LaunchedEffect(telemetry.latitude, telemetry.longitude, telemetry.hasFix, followUser) {
        if (telemetry.hasFix && followUser) {
            cameraLat = telemetry.latitude
            cameraLon = telemetry.longitude
        }
    }

    var showFilterStrip by remember { mutableStateOf(false) }
    var isSearchExpanded by remember { mutableStateOf(false) }
    var dropMarkerDraft by remember { mutableStateOf<DropMarkerDraft?>(null) }
    var selectedMarker by remember { mutableStateOf<MapMarkerEntity?>(null) }
    var revealedMarkerId by remember { mutableStateOf<Long?>(null) }
    var revealSeq by remember { mutableLongStateOf(0L) }

    LaunchedEffect(revealedMarkerId, revealSeq) {
        if (revealedMarkerId != null) {
            delay(2000L)
            revealedMarkerId = null
        }
    }

    LaunchedEffect(mapSettings.disableCreatedMarkerLabels) {
        if (!mapSettings.disableCreatedMarkerLabels) {
            revealedMarkerId = null
        }
    }

    BackHandler(enabled = isSearchExpanded) {
        isSearchExpanded = false
    }

    val visibleMarkers = remember(markers, hiddenMarkerCategories) {
        markers.filter { it.categoryName !in hiddenMarkerCategories }
    }

    val highlightedLatLonPoints = remember(highlightedRoutePoints) {
        highlightedRoutePoints.map { LatLonPoint(it.latitude, it.longitude, it.speedKmh, it.timestamp) }
    }

    val blurMapBehindOverlay = mapSettings.uiBlurEnabled && (
        isSearchExpanded || pendingRouteSummary != null || dropMarkerDraft != null || selectedMarker != null
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Full-bleed OSMDroid Map with Fog-of-War, Routes, Markers & Live GPS
        TerraMapCanvas(
            palette = palette,
            isDarkTheme = isDarkTheme,
            mapSettings = mapSettings,
            apiKey = apiKey,
            apiProvider = apiProvider,
            centerLat = cameraLat,
            centerLon = cameraLon,
            followUser = followUser,
            controlCommands = mapCommands,
            exploredChunks = exploredChunks,
            activeRoutePoints = activeRecording.points,
            highlightedRoutePoints = highlightedLatLonPoints,
            highlightedRouteColor = highlightedRoute?.let { Color(it.colorArgb) },
            markers = visibleMarkers,
            searchedPlace = selectedSearchPlace,
            telemetry = telemetry,
            showFogOfWar = true,
            revealedMarkerId = revealedMarkerId,
            onUserPan = { followUser = false },
            onLongPressCoordinate = { lat, lon ->
                dropMarkerDraft = DropMarkerDraft(lat, lon)
            },
            onMarkerClick = { marker ->
                if (mapSettings.disableCreatedMarkerLabels) {
                    if (revealedMarkerId == marker.id) {
                        revealedMarkerId = null
                        selectedMarker = marker
                    } else {
                        revealedMarkerId = marker.id
                        revealSeq++
                    }
                } else {
                    selectedMarker = marker
                }
            },
            modifier = Modifier.conditionalBlur(enabled = blurMapBehindOverlay, radius = 12.dp)
        )

        // Top HUD: Location Search Bar + District exploration stats + Marker category filter
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocationSearchBarAndResults(
                searchState = locationSearchState,
                isExpanded = isSearchExpanded,
                savedMarkers = markers,
                telemetry = telemetry,
                onExpandedChange = { isSearchExpanded = it },
                onQueryChange = { q -> onSearchQueryChange(q, cameraLat, cameraLon) },
                onSubmitSearch = { onSubmitSearch(cameraLat, cameraLon) },
                onClearSearch = { onClearSearch() },
                onSelectResult = { result ->
                    isSearchExpanded = false
                    followUser = false
                    cameraLat = result.latitude
                    cameraLon = result.longitude
                    onSelectSearchResult(result)
                    mapCommands.focusOn(result.latitude, result.longitude, zoom = 16.0)
                },
                onQuickPinResult = { result ->
                    isSearchExpanded = false
                    followUser = false
                    cameraLat = result.latitude
                    cameraLon = result.longitude
                    onSelectSearchResult(result)
                    mapCommands.focusOn(result.latitude, result.longitude, zoom = 16.0)
                    dropMarkerDraft = DropMarkerDraft(
                        latitude = result.latitude,
                        longitude = result.longitude,
                        initialTitle = result.title,
                        initialCategory = result.markerCategory
                    )
                }
            )

            AnimatedVisibility(visible = !isSearchExpanded) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ExpressiveCard(
                        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                        shadowElevation = 4.dp
                    ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "District ${districtInfo.displayNumber}: ${districtInfo.percentage}% explored",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${districtInfo.unlockedCount}/${districtInfo.totalCount} sectors • ${exploredChunks.size} total unlocked",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(percent = 50),
                            color = ExpressiveTheme.colors.nestedElementSurface
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (telemetry.hasFix) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed,
                                    contentDescription = "GPS status",
                                    tint = if (telemetry.hasFix) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (telemetry.hasFix) {
                                        String.format("%.1f km/h", telemetry.speedKmh)
                                    } else if (telemetry.hasPermission) {
                                        "GPS Sync"
                                    } else {
                                        "No GPS"
                                    },
                                    style = TelemetryTextStyle.copy(fontSize = 12.sp),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { districtInfo.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = ExpressiveTheme.colors.nestedElementSurface,
                        strokeCap = StrokeCap.Round
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${GeoGrid.formatDistance(explorerStats.totalDistanceMeters)} • ${explorerStats.totalSteps} steps",
                            style = TelemetryTextStyle.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable { showFilterStrip = !showFilterStrip }
                                .testTag("toggle_marker_filters"),
                            shape = RoundedCornerShape(percent = 50),
                            color = if (hiddenMarkerCategories.isNotEmpty() || showFilterStrip) {
                                ExpressiveTheme.colors.buttonTertiarySubtle
                            } else {
                                ExpressiveTheme.colors.nestedElementSurface
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FilterList,
                                    contentDescription = "Filter map markers",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (hiddenMarkerCategories.isEmpty()) "Pins (${markers.size})" else "Filtered (${visibleMarkers.size}/${markers.size})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showFilterStrip) {
                ExpressiveCard(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    shadowElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .defaultMinSize(minHeight = 36.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable { onShowAllMarkerCategories() },
                            shape = RoundedCornerShape(percent = 50),
                            color = if (hiddenMarkerCategories.isEmpty()) {
                                ExpressiveTheme.colors.buttonPrimary
                            } else {
                                ExpressiveTheme.colors.nestedElementSurface
                            }
                        ) {
                            Text(
                                text = "All",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (hiddenMarkerCategories.isEmpty()) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }

                        MarkerCategory.entries.forEach { cat ->
                            val isVisible = cat.name !in hiddenMarkerCategories
                            Surface(
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 36.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable { onToggleMarkerCategory(cat) },
                                shape = RoundedCornerShape(percent = 50),
                                color = if (isVisible) {
                                    Color(cat.defaultColorArgb)
                                } else {
                                    ExpressiveTheme.colors.nestedElementSurface
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = cat.icon,
                                        contentDescription = cat.label,
                                        tint = if (isVisible) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = cat.label,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isVisible) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (!telemetry.hasPermission) {
                ExpressiveCard(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.location_permission_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = stringResource(R.string.location_permission_rationale),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        ExpressivePillButton(
                            text = stringResource(R.string.grant_location_access),
                            onClick = onRequestPermissions,
                            modifier = Modifier.testTag("grant_gps_button")
                        )
                    }
                }
            }

            if (highlightedRoute != null) {
                ExpressiveCard(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    shadowElevation = 3.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(Color(highlightedRoute.colorArgb))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = highlightedRoute.name,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${GeoGrid.formatDurationExact(highlightedRoute.durationMillis)} • ${GeoGrid.formatDistance(highlightedRoute.distanceMeters)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = onClearHighlightedRoute) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Clear highlighted route"
                            )
                        }
                    }
                }
            }
                }
            }
        }

        // Right-side Map Controls (48dp touch targets)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .conditionalBlur(enabled = mapSettings.uiBlurEnabled && isSearchExpanded, radius = 10.dp)
                .padding(
                    end = 16.dp,
                    bottom = if (selectedSearchPlace != null && !isSearchExpanded) 186.dp else 104.dp
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MapControlCircleButton(
                icon = Icons.Filled.AddLocationAlt,
                contentDescription = "Drop custom marker",
                onClick = {
                    val lat = if (telemetry.hasFix) telemetry.latitude else cameraLat
                    val lon = if (telemetry.hasFix) telemetry.longitude else cameraLon
                    dropMarkerDraft = DropMarkerDraft(lat, lon)
                },
                containerColor = ExpressiveTheme.colors.buttonTertiarySubtle,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                testTag = "drop_marker_button"
            )
            val isMapRotated = abs(mapCommands.currentOrientation) > 2f
            MapControlCircleButton(
                icon = Icons.Filled.Explore,
                contentDescription = if (isMapRotated) "Reset map rotation to North" else "Map compass",
                onClick = { mapCommands.resetRotation() },
                containerColor = if (isMapRotated) {
                    ExpressiveTheme.colors.buttonPrimary
                } else {
                    ExpressiveTheme.colors.cardSurface
                },
                contentColor = if (isMapRotated) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                iconModifier = Modifier.graphicsLayer {
                    rotationZ = mapCommands.currentOrientation
                },
                testTag = "reset_map_rotation"
            )
            MapControlCircleButton(
                icon = Icons.Filled.Add,
                contentDescription = "Zoom in",
                onClick = { mapCommands.zoomIn() }
            )
            MapControlCircleButton(
                icon = Icons.Filled.Remove,
                contentDescription = "Zoom out",
                onClick = { mapCommands.zoomOut() }
            )
            MapControlCircleButton(
                icon = Icons.Filled.MyLocation,
                contentDescription = "Center map on GPS location",
                onClick = {
                    followUser = true
                    if (telemetry.hasFix) {
                        cameraLat = telemetry.latitude
                        cameraLon = telemetry.longitude
                    }
                    mapCommands.recenter()
                },
                containerColor = if (followUser && telemetry.hasFix) {
                    ExpressiveTheme.colors.buttonPrimary
                } else {
                    ExpressiveTheme.colors.cardSurface
                },
                contentColor = if (followUser && telemetry.hasFix) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        // Bottom Selected Place Banner + Live Route Recording Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (selectedSearchPlace != null && !isSearchExpanded) {
                SelectedSearchPlaceBanner(
                    place = selectedSearchPlace,
                    telemetry = telemetry,
                    onFocusMap = {
                        followUser = false
                        cameraLat = selectedSearchPlace.latitude
                        cameraLon = selectedSearchPlace.longitude
                        mapCommands.focusOn(selectedSearchPlace.latitude, selectedSearchPlace.longitude, zoom = 16.0)
                    },
                    onDropMarker = {
                        dropMarkerDraft = DropMarkerDraft(
                            latitude = selectedSearchPlace.latitude,
                            longitude = selectedSearchPlace.longitude,
                            initialTitle = selectedSearchPlace.title,
                            initialCategory = selectedSearchPlace.markerCategory
                        )
                    },
                    onDismiss = onClearSelectedSearchPlace
                )
            }

            if (!activeRecording.isRecording) {
                Row(
                    modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
                    Surface(
                        modifier = Modifier.weight(1f, fill = false),
                        shape = RoundedCornerShape(percent = 50),
                        color = ExpressiveTheme.colors.cardSurface,
                        shadowElevation = if (blurOn) 0.dp else 3.dp,
                        border = if (blurOn) {
                            BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
                        } else {
                            null
                        }
                    ) {
                        Text(
                            text = if (telemetry.hasFix) {
                                String.format("%.4f, %.4f • © OSM", telemetry.latitude, telemetry.longitude)
                            } else {
                                "© OpenStreetMap"
                            },
                            style = TelemetryTextStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    ExpressivePillButton(
                        text = "Start Route",
                        icon = Icons.Filled.PlayArrow,
                        onClick = onStartRoute,
                        modifier = Modifier.testTag("start_route_button")
                    )
                }
            } else {
                ActiveRecordingDock(
                    recording = activeRecording,
                    currentSpeedKmh = telemetry.speedKmh,
                    onStopClick = onStopRoute,
                    modifier = Modifier.widthIn(max = 560.dp)
                )
            }
        }
    }

    if (pendingRouteSummary != null) {
        SaveRouteDialog(
            summary = pendingRouteSummary,
            defaultColorArgb = palette.routeColor,
            onSave = onConfirmSaveRoute,
            onDiscard = onDiscardRoute
        )
    }

    val draft = dropMarkerDraft
    if (draft != null) {
        DropMarkerDialog(
            latitude = draft.latitude,
            longitude = draft.longitude,
            initialTitle = draft.initialTitle,
            initialCategory = draft.initialCategory,
            markerStyle = mapSettings.markerStyle,
            onConfirm = { title, note, category, colorArgb, iconName ->
                onAddMarker(title, note, category, draft.latitude, draft.longitude, colorArgb, iconName)
                dropMarkerDraft = null
            },
            onDismiss = { dropMarkerDraft = null }
        )
    }

    val marker = selectedMarker
    if (marker != null) {
        MarkerDetailDialog(
            marker = marker,
            telemetry = telemetry,
            markerStyle = mapSettings.markerStyle,
            onUpdateIcon = { chosenIcon ->
                onUpdateMarkerIcon(marker, chosenIcon)
                selectedMarker = marker.copy(iconName = chosenIcon)
            },
            onUpdateColor = { chosenColor ->
                onUpdateMarkerColor(marker, chosenColor)
                selectedMarker = marker.copy(colorArgb = chosenColor)
            },
            onDelete = {
                onDeleteMarker(marker.id)
                selectedMarker = null
            },
            onDismiss = { selectedMarker = null }
        )
    }
}

@Composable
private fun MapControlCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    containerColor: Color = ExpressiveTheme.colors.cardSurface,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    iconModifier: Modifier = Modifier,
    testTag: String = ""
) {
    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
    Surface(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        shape = CircleShape,
        color = containerColor,
        shadowElevation = if (blurOn) 0.dp else 4.dp,
        border = if (blurOn) {
            BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
        } else {
            null
        }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = iconModifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun ActiveRecordingDock(
    recording: ActiveRecordingState,
    currentSpeedKmh: Float,
    onStopClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulseTransition = rememberInfiniteTransition(label = "rec_dot")
    val dotAlpha by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_alpha"
    )

    ExpressiveCard(
        modifier = modifier.fillMaxWidth(),
        shadowElevation = 6.dp,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .alpha(dotAlpha)
                            .clip(CircleShape)
                            .background(Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = GeoGrid.formatTimerClock(recording.elapsedMillis),
                        style = TelemetryTextStyle.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${GeoGrid.formatDistance(recording.distanceMeters)} • ${String.format("%.1f km/h", currentSpeedKmh)} • +${recording.chunksUnlockedDuringRoute} sectors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            ExpressivePillButton(
                text = "Finish",
                icon = Icons.Filled.Stop,
                onClick = onStopClick,
                containerColorOverride = MaterialTheme.colorScheme.error,
                contentColorOverride = MaterialTheme.colorScheme.onError,
                modifier = Modifier.testTag("stop_route_button")
            )
        }
    }
}

@Composable
private fun SaveRouteDialog(
    summary: PendingRouteSummary,
    defaultColorArgb: Long,
    onSave: (name: String, colorArgb: Long) -> Unit,
    onDiscard: () -> Unit
) {
    var routeName by remember { mutableStateOf("Explorer Path") }
    var selectedColor by remember { mutableLongStateOf(defaultColorArgb) }
    var showColorPicker by remember { mutableStateOf(false) }

    val colorChoices = remember(defaultColorArgb, selectedColor) {
        (listOf(
            defaultColorArgb,
            0xFF1B6B52L,
            0xFF2563EBL,
            0xFFFF4D8DL,
            0xFFEA580CL,
            0xFF7C3AEDL,
            selectedColor
        )).distinct()
    }

    AlertDialog(
        onDismissRequest = onDiscard,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Text(
                text = "Save Recorded Route",
                style = MaterialTheme.typography.headlineMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ExpressiveTheme.colors.nestedElementSurface
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Completion Time: ${GeoGrid.formatDurationExact(summary.durationMillis)}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Distance: ${GeoGrid.formatDistance(summary.distanceMeters)} • Avg ${String.format("%.1f km/h", summary.avgSpeedKmh)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Sectors Revealed: ${summary.chunksUnlocked}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = routeName,
                    onValueChange = { routeName = it },
                    label = { Text("Route Name (e.g. Way home via Park)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("route_name_input")
                )

                Text(
                    text = "Route Line Color",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorChoices.forEach { argb ->
                        val selected = selectedColor == argb
                        Surface(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .clickable { selectedColor = argb },
                            shape = CircleShape,
                            color = Color(argb),
                            border = if (selected) {
                                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                            } else {
                                BorderStroke(1.dp, Color.White)
                            }
                        ) {}
                    }
                    Surface(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .clickable { showColorPicker = true }
                            .testTag("route_custom_color_button"),
                        shape = CircleShape,
                        color = ExpressiveTheme.colors.nestedElementSurface,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Pick custom route color",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Save Route",
                onClick = { onSave(routeName, selectedColor) },
                modifier = Modifier.testTag("confirm_save_route")
            )
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text("Discard")
            }
        }
    )

    if (showColorPicker) {
        CustomColorPickerDialog(
            initialColorArgb = selectedColor,
            title = "Route Line Color",
            subtitle = "Choose any custom hue and brightness for this route",
            onConfirm = { picked ->
                selectedColor = picked
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}

@Composable
private fun DropMarkerDialog(
    latitude: Double,
    longitude: Double,
    initialTitle: String? = null,
    initialCategory: MarkerCategory? = null,
    markerStyle: MarkerStyle = MarkerStyle.CIRCLE_ICON,
    onConfirm: (title: String, note: String, category: MarkerCategory, colorArgb: Long, iconName: String) -> Unit,
    onDismiss: () -> Unit
) {
    val startCategory = initialCategory ?: MarkerCategory.HOME
    var selectedCategory by remember(initialCategory) { mutableStateOf(startCategory) }
    var selectedColorArgb by remember(startCategory) { mutableLongStateOf(startCategory.defaultColorArgb) }
    var hasCustomColor by remember { mutableStateOf(false) }
    var selectedIconName by remember(startCategory) { mutableStateOf(startCategory.defaultIconName) }
    var hasCustomIcon by remember { mutableStateOf(false) }
    var showIconLibrary by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    var title by remember(initialTitle, startCategory) {
        mutableStateOf(initialTitle?.takeIf { it.isNotBlank() } ?: startCategory.label)
    }
    var note by remember { mutableStateOf("") }

    val colorChoices = remember(selectedColorArgb) {
        (MarkerCategory.entries.map { it.defaultColorArgb } + selectedColorArgb).distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Text("Drop Custom Marker", style = MaterialTheme.typography.headlineMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = String.format("Location: %.5f, %.5f", latitude, longitude),
                    style = TelemetryTextStyle.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text("Quick Markers", style = MaterialTheme.typography.labelLarge)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MarkerCategory.entries.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rowItems.forEach { cat ->
                                val isSelected = selectedCategory == cat
                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minHeight = 48.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            if (title == selectedCategory.label) {
                                                title = cat.label
                                            }
                                            selectedCategory = cat
                                            if (!hasCustomIcon) {
                                                selectedIconName = cat.defaultIconName
                                            }
                                            if (!hasCustomColor) {
                                                selectedColorArgb = cat.defaultColorArgb
                                            }
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) {
                                        Color(selectedColorArgb)
                                    } else {
                                        ExpressiveTheme.colors.nestedElementSurface
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = cat.icon,
                                            contentDescription = cat.label,
                                            tint = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = cat.label,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Centered Google Icon Preview + 2,100+ Google Icon Library Picker
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = ExpressiveTheme.colors.nestedElementSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MarkerStylePreviewBadge(
                                iconName = selectedIconName,
                                colorArgb = selectedColorArgb,
                                markerStyle = markerStyle,
                                badgeSize = 40.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Marker Icon",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = selectedIconName,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        ExpressivePillButton(
                            text = "2,100+ Icons",
                            icon = Icons.Filled.Apps,
                            subtle = true,
                            onClick = { showIconLibrary = true },
                            modifier = Modifier.testTag("open_icon_library_button")
                        )
                    }
                }

                // Pin Color Row + Custom Color Picker
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorChoices.forEach { argb ->
                        val selected = selectedColorArgb == argb
                        Surface(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable {
                                    selectedColorArgb = argb
                                    hasCustomColor = true
                                },
                            shape = CircleShape,
                            color = Color(argb),
                            border = if (selected) {
                                BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                            } else {
                                BorderStroke(1.5.dp, Color.White)
                            }
                        ) {}
                    }
                    Surface(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickable { showColorPicker = true }
                            .testTag("marker_custom_color_button"),
                        shape = CircleShape,
                        color = ExpressiveTheme.colors.nestedElementSurface,
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Palette,
                                contentDescription = "Custom marker color picker",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Marker Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Place Pin",
                onClick = { onConfirm(title, note, selectedCategory, selectedColorArgb, selectedIconName) }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )

    if (showIconLibrary) {
        GoogleIconLibraryDialog(
            selectedIconName = selectedIconName,
            onSelectIcon = { chosen ->
                selectedIconName = chosen
                hasCustomIcon = true
                showIconLibrary = false
            },
            onDismiss = { showIconLibrary = false }
        )
    }

    if (showColorPicker) {
        CustomColorPickerDialog(
            initialColorArgb = selectedColorArgb,
            title = "Marker Pin Color",
            subtitle = "Choose any custom color for this map pin",
            onConfirm = { picked ->
                selectedColorArgb = picked
                hasCustomColor = true
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}

@Composable
private fun MarkerDetailDialog(
    marker: MapMarkerEntity,
    telemetry: LocationTelemetry,
    markerStyle: MarkerStyle = MarkerStyle.CIRCLE_ICON,
    onUpdateIcon: (String) -> Unit,
    onUpdateColor: (Long) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    val category = MarkerCategory.entries.firstOrNull { it.name == marker.categoryName }
        ?: MarkerCategory.LANDMARK
    val effectiveIconName = marker.iconName.ifBlank { category.defaultIconName }
    var showIconLibrary by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }
    val colorHex = remember(marker.colorArgb) {
        ColorPickerMath.formatHexRgb(Color(marker.colorArgb))
    }

    val distText = if (telemetry.hasFix) {
        val d = GeoGrid.haversineMeters(
            telemetry.latitude,
            telemetry.longitude,
            marker.latitude,
            marker.longitude
        )
        "${GeoGrid.formatDistance(d)} away"
    } else null

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(20.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Row(verticalAlignment = Alignment.CenterVertically) {
                MarkerStylePreviewBadge(
                    iconName = effectiveIconName,
                    colorArgb = marker.colorArgb,
                    markerStyle = markerStyle,
                    badgeSize = 42.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(marker.title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = category.label + (if (distText != null) " • $distText" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (marker.note.isNotBlank()) {
                    Text(marker.note, style = MaterialTheme.typography.bodyLarge)
                }
                Text(
                    text = String.format("Coordinates: %.5f, %.5f", marker.latitude, marker.longitude),
                    style = TelemetryTextStyle.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showIconLibrary = true }
                        .testTag("change_marker_icon_button"),
                    shape = RoundedCornerShape(14.dp),
                    color = ExpressiveTheme.colors.nestedElementSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Apps,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Change Icon ($effectiveIconName)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Browse",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showColorPicker = true }
                        .testTag("change_marker_color_button"),
                    shape = RoundedCornerShape(14.dp),
                    color = ExpressiveTheme.colors.nestedElementSurface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = Color(marker.colorArgb),
                                border = BorderStroke(1.5.dp, Color.White),
                                modifier = Modifier.size(18.dp)
                            ) {}
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Change Color ($colorHex)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = "Picker",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Close",
                onClick = onDismiss,
                subtle = true
            )
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete marker",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
    )

    if (showIconLibrary) {
        GoogleIconLibraryDialog(
            selectedIconName = effectiveIconName,
            onSelectIcon = { chosen ->
                onUpdateIcon(chosen)
                showIconLibrary = false
            },
            onDismiss = { showIconLibrary = false }
        )
    }

    if (showColorPicker) {
        CustomColorPickerDialog(
            initialColorArgb = marker.colorArgb,
            title = "Marker Pin Color",
            subtitle = "Pick a custom color for ${marker.title}",
            onConfirm = { picked ->
                onUpdateColor(picked)
                showColorPicker = false
            },
            onDismiss = { showColorPicker = false }
        )
    }
}
