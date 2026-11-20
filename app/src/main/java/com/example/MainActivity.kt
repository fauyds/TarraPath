package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.model.ThemeMode
import com.example.domain.GeoGrid
import com.example.ui.TerraViewModel
import com.example.ui.components.AchievementUnlockedBanner
import com.example.ui.components.LocalBackdropHazeState
import com.example.ui.components.LocalBottomOverlayPadding
import com.example.ui.components.applyBackdropBlur
import com.example.ui.screens.AchievementsScreen
import com.example.ui.screens.ExploreScreen
import com.example.ui.screens.RouteDetailScreen
import com.example.ui.screens.RoutesLibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TerraPathTheme
import dev.chrisbanes.haze.HazeState

const val ROUTE_EXPLORE = "explore"
const val ROUTE_ROUTES = "routes"
const val ROUTE_ACHIEVEMENTS = "achievements"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_DETAIL = "route_detail/{routeId}"

private data class NavTab(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val PrimaryTabs = listOf(
    NavTab(ROUTE_EXPLORE, R.string.nav_explore, Icons.Filled.Explore, Icons.Outlined.Explore),
    NavTab(ROUTE_ROUTES, R.string.nav_routes, Icons.Filled.Route, Icons.Outlined.Route),
    NavTab(ROUTE_ACHIEVEMENTS, R.string.nav_achievements, Icons.Filled.EmojiEvents, Icons.Outlined.EmojiEvents),
    NavTab(ROUTE_SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val appContainer = (application as TerraPathApplication).container
        setContent {
            TerraPathApp(appContainer = appContainer)
        }
    }
}

@Composable
fun TerraPathApp(appContainer: AppContainer) {
    val viewModel: TerraViewModel = viewModel(factory = TerraViewModel.Factory(appContainer))

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val customAccentColorArgb by viewModel.customAccentColorArgb.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiKey.collectAsStateWithLifecycle()
    val apiProvider by viewModel.apiProvider.collectAsStateWithLifecycle()
    val apiKeyTestState by viewModel.apiKeyTestState.collectAsStateWithLifecycle()
    val mapSettings by viewModel.mapDisplaySettings.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    TerraPathTheme(
        darkTheme = isDarkTheme,
        dynamicColor = true,
        customAccentColorArgb = customAccentColorArgb,
        uiBlurEnabled = mapSettings.uiBlurEnabled
    ) {
        val context = LocalContext.current

        fun checkLocationPerm(): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        fun checkActivityPerm(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACTIVITY_RECOGNITION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        var hasLocationPerm by remember { mutableStateOf(checkLocationPerm()) }
        var hasActivityPerm by remember { mutableStateOf(checkActivityPerm()) }

        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { _ ->
            hasLocationPerm = checkLocationPerm()
            hasActivityPerm = checkActivityPerm()
            viewModel.updatePermissions(hasLocationPerm, hasActivityPerm)
        }

        fun launchPermissionRequest() {
            val perms = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.ACTIVITY_RECOGNITION)
                }
            }.toTypedArray()
            permissionLauncher.launch(perms)
        }

        LaunchedEffect(Unit) {
            viewModel.updatePermissions(hasLocationPerm, hasActivityPerm)
            if (!hasLocationPerm) {
                launchPermissionRequest()
            }
        }

        val colorScheme = MaterialTheme.colorScheme
        val activeMapPalette = remember(colorScheme, isDarkTheme, mapSettings.easyModeEnabled) {
            GeoGrid.deriveDefaultMapPalette(
                colorScheme = colorScheme,
                isDark = isDarkTheme,
                easyMode = mapSettings.easyModeEnabled
            )
        }

        val telemetry by viewModel.locationTelemetry.collectAsStateWithLifecycle()
        val districtInfo by viewModel.currentDistrictInfo.collectAsStateWithLifecycle()
        val exploredChunks by viewModel.exploredChunks.collectAsStateWithLifecycle()
        val totalChunkCount by viewModel.totalChunkCount.collectAsStateWithLifecycle()
        val explorerStats by viewModel.explorerStats.collectAsStateWithLifecycle()
        val activeRecording by viewModel.activeRecording.collectAsStateWithLifecycle()
        val pendingRouteSummary by viewModel.pendingRouteSummary.collectAsStateWithLifecycle()
        val allRoutes by viewModel.allRoutes.collectAsStateWithLifecycle()
        val highlightedRouteId by viewModel.highlightedRouteId.collectAsStateWithLifecycle()
        val highlightedRoutePoints by viewModel.highlightedRoutePoints.collectAsStateWithLifecycle()
        val markers by viewModel.allMarkers.collectAsStateWithLifecycle()
        val hiddenMarkerCategories by viewModel.hiddenMarkerCategories.collectAsStateWithLifecycle()
        val achievements by viewModel.achievements.collectAsStateWithLifecycle()
        val activeBanner by viewModel.activeAchievementBanner.collectAsStateWithLifecycle()
        val locationSearchState by viewModel.locationSearchState.collectAsStateWithLifecycle()
        val selectedSearchPlace by viewModel.selectedSearchPlace.collectAsStateWithLifecycle()

        val highlightedRoute = remember(allRoutes, highlightedRouteId) {
            allRoutes.firstOrNull { it.id == highlightedRouteId }
        }

        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        val showBottomNav = currentDestination?.route in PrimaryTabs.map { it.route }

        fun navigateToTab(route: String) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }

        val appHazeState = remember(mapSettings.uiBlurEnabled) {
            if (mapSettings.uiBlurEnabled) HazeState() else null
        }
        val density = LocalDensity.current
        var measuredBottomBarHeightDp by remember { mutableStateOf(0.dp) }

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp
            val effectiveBottomPadding = if (!useNavRail && showBottomNav) measuredBottomBarHeightDp else 0.dp

            CompositionLocalProvider(
                LocalBackdropHazeState provides appHazeState,
                LocalBottomOverlayPadding provides effectiveBottomPadding
            ) {
                Scaffold(
                    containerColor = ExpressiveTheme.colors.appBackground,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0)
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            if (useNavRail && showBottomNav) {
                                NavigationRail(
                                    containerColor = ExpressiveTheme.colors.cardSurface
                                ) {
                                    PrimaryTabs.forEach { tab ->
                                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                                        val label = stringResource(tab.labelRes)
                                        NavigationRailItem(
                                            selected = selected,
                                            onClick = { navigateToTab(tab.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = label
                                                )
                                            },
                                            label = { Text(label) }
                                        )
                                    }
                                }
                            }

                            NavHost(
                                navController = navController,
                                startDestination = ROUTE_EXPLORE,
                                modifier = Modifier.weight(1f).fillMaxSize()
                            ) {
                        composable(ROUTE_EXPLORE) {
                            ExploreScreen(
                                palette = activeMapPalette,
                                isDarkTheme = isDarkTheme,
                                mapSettings = mapSettings,
                                apiKey = apiKey,
                                apiProvider = apiProvider,
                                telemetry = telemetry,
                                districtInfo = districtInfo,
                                exploredChunks = exploredChunks,
                                explorerStats = explorerStats,
                                activeRecording = activeRecording,
                                pendingRouteSummary = pendingRouteSummary,
                                highlightedRoute = highlightedRoute,
                                highlightedRoutePoints = highlightedRoutePoints,
                                markers = markers,
                                hiddenMarkerCategories = hiddenMarkerCategories,
                                locationSearchState = locationSearchState,
                                selectedSearchPlace = selectedSearchPlace,
                                onSearchQueryChange = { q, lat, lon ->
                                    viewModel.onSearchQueryChanged(q, lat, lon)
                                },
                                onSubmitSearch = { lat, lon ->
                                    viewModel.submitLocationSearch(lat, lon)
                                },
                                onClearSearch = { viewModel.clearLocationSearch() },
                                onSelectSearchResult = { result ->
                                    viewModel.selectSearchResult(result)
                                },
                                onClearSelectedSearchPlace = {
                                    viewModel.clearSelectedSearchPlace()
                                },
                                onRequestPermissions = { launchPermissionRequest() },
                                onStartRoute = { viewModel.startRouteRecording() },
                                onStopRoute = { viewModel.stopRouteRecordingAndPrompt() },
                                onConfirmSaveRoute = { name, colorArgb ->
                                    viewModel.confirmSaveRoute(name, colorArgb)
                                },
                                onDiscardRoute = { viewModel.discardPendingRoute() },
                                onClearHighlightedRoute = { viewModel.highlightRouteOnMap(null) },
                                onAddMarker = { title, note, category, lat, lon, colorArgb, iconName ->
                                    viewModel.addMarker(
                                        title = title,
                                        note = note,
                                        category = category,
                                        latitude = lat,
                                        longitude = lon,
                                        colorArgb = colorArgb,
                                        iconName = iconName
                                    )
                                },
                                onUpdateMarkerIcon = { marker, iconName ->
                                    viewModel.updateMarkerIcon(marker, iconName)
                                },
                                onUpdateMarkerColor = { marker, colorArgb ->
                                    viewModel.updateMarkerColor(marker, colorArgb)
                                },
                                onDeleteMarker = { id -> viewModel.deleteMarker(id) },
                                onToggleMarkerCategory = { cat -> viewModel.toggleMarkerCategory(cat) },
                                onShowAllMarkerCategories = { viewModel.showAllMarkers() }
                            )
                        }

                        composable(ROUTE_ROUTES) {
                            RoutesLibraryScreen(
                                routes = allRoutes,
                                onRouteClick = { routeId ->
                                    navController.navigate("route_detail/$routeId")
                                },
                                onHighlightOnMap = { routeId ->
                                    viewModel.highlightRouteOnMap(routeId)
                                    navigateToTab(ROUTE_EXPLORE)
                                },
                                onDeleteRoute = { routeId -> viewModel.deleteRoute(routeId) }
                            )
                        }

                        composable(
                            route = ROUTE_DETAIL,
                            arguments = listOf(navArgument("routeId") { type = NavType.LongType })
                        ) { backStackEntry ->
                            val routeId = backStackEntry.arguments?.getLong("routeId") ?: 0L
                            val route by viewModel.observeRoute(routeId).collectAsStateWithLifecycle(initialValue = null)
                            val points by viewModel.observeRoutePoints(routeId).collectAsStateWithLifecycle(initialValue = emptyList())
                            RouteDetailScreen(
                                route = route,
                                points = points,
                                palette = activeMapPalette,
                                showGridLines = mapSettings.showGridLines,
                                onBack = { navController.popBackStack() },
                                onShowOnExploreMap = { id ->
                                    viewModel.highlightRouteOnMap(id)
                                    navController.popBackStack()
                                    navigateToTab(ROUTE_EXPLORE)
                                },
                                onDeleteRoute = { id -> viewModel.deleteRoute(id) }
                            )
                        }

                        composable(ROUTE_ACHIEVEMENTS) {
                            AchievementsScreen(
                                stats = explorerStats,
                                totalChunksUnlocked = totalChunkCount,
                                achievements = achievements
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsScreen(
                                activePalette = activeMapPalette,
                                isDarkTheme = isDarkTheme,
                                themeMode = themeMode,
                                customAccentColorArgb = customAccentColorArgb,
                                apiKey = apiKey,
                                apiProvider = apiProvider,
                                apiKeyTestState = apiKeyTestState,
                                mapSettings = mapSettings,
                                onSelectThemeMode = { viewModel.setThemeMode(it) },
                                onSetCustomAccentColor = { viewModel.setCustomAccentColor(it) },
                                onSaveApiKeyConfig = { key, provider ->
                                    viewModel.saveApiKeyConfig(key, provider)
                                },
                                onTestApiKey = { key, provider ->
                                    viewModel.testApiKey(key, provider)
                                },
                                onResetApiKeyTest = { viewModel.resetApiKeyTestState() },
                                onClearApiKey = { viewModel.clearApiKey() },
                                onToggleEasyMode = { viewModel.setEasyModeEnabled(it) },
                                onToggleSmoothFade = { viewModel.setSmoothFadeEnabled(it) },
                                onToggleGridLines = { viewModel.setShowGridLines(it) },
                                onToggleStreetNames = { viewModel.setShowStreetNames(it) },
                                onToggleMarkerLabels = { viewModel.setShowMarkerLabels(it) },
                                onToggleDisableCreatedMarkerLabels = { viewModel.setDisableCreatedMarkerLabels(it) },
                                onToggleSearchLabels = { viewModel.setShowSearchLabels(it) },
                                onToggleSectorLabels = { viewModel.setShowSectorLabels(it) },
                                onToggleUiBlur = { viewModel.setUiBlurEnabled(it) },
                                onToggleLocationOutline = { viewModel.setShowLocationOutline(it) },
                                onSelectMarkerStyle = { viewModel.setMarkerStyle(it) }
                            )
                        }
                    }
                }

                        if (!useNavRail && showBottomNav) {
                            val blurOn = mapSettings.uiBlurEnabled && appHazeState != null
                            Column(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .onSizeChanged { size ->
                                        measuredBottomBarHeightDp = with(density) { size.height.toDp() }
                                    }
                                    .applyBackdropBlur(
                                        shape = RectangleShape,
                                        tintColor = ExpressiveTheme.colors.cardSurface,
                                        blurRadius = 22.dp
                                    )
                            ) {
                                if (mapSettings.uiBlurEnabled) {
                                    HorizontalDivider(
                                        thickness = 0.8.dp,
                                        color = ExpressiveTheme.colors.frostedBorderColor
                                    )
                                }
                                NavigationBar(
                                    containerColor = if (blurOn) {
                                        Color.Transparent
                                    } else {
                                        ExpressiveTheme.colors.cardSurface
                                    },
                                    tonalElevation = 0.dp,
                                    windowInsets = WindowInsets.navigationBars
                                ) {
                                    PrimaryTabs.forEach { tab ->
                                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                                        val label = stringResource(tab.labelRes)
                                        NavigationBarItem(
                                            selected = selected,
                                            onClick = { navigateToTab(tab.route) },
                                            icon = {
                                                Icon(
                                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                    contentDescription = label
                                                )
                                            },
                                            label = { Text(label) },
                                            modifier = Modifier.testTag("nav_${tab.route}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Global Expressive Achievement Unlock Toast Banner
            AchievementUnlockedBanner(
                achievement = activeBanner,
                onDismiss = { viewModel.dismissCurrentAchievementBanner() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
            )
        }
    }
}
