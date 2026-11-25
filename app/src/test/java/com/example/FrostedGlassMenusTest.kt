package com.example

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.example.data.local.ExplorerStatsEntity
import com.example.data.local.RouteEntity
import com.example.data.local.RouteFolderEntity
import com.example.data.model.ApiKeyTestState
import com.example.data.model.ApiProvider
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MapPalette
import com.example.data.model.ThemeMode
import com.example.ui.components.FrostedMenuContainer
import com.example.ui.components.LocalBackdropHazeState
import com.example.ui.components.isBackdropBlurActive
import com.example.ui.screens.AchievementsScreen
import com.example.ui.screens.RouteDetailScreen
import com.example.ui.screens.RoutesLibraryScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.TerraPathTheme
import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w412dp-h915dp")
class FrostedGlassMenusTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun frostedMenuContainer_providesIsolatedCardHazeStateDifferentFromOuterNavHazeState() {
        val outerNavHazeState = HazeState()
        var innerCardHazeState: HazeState? = null
        var innerBackdropActive = false

        composeTestRule.setContent {
            TerraPathTheme(
                darkTheme = true,
                uiBlurEnabled = true,
                uiTransparency = 0.40f,
                uiBlurRadiusDp = 24f
            ) {
                CompositionLocalProvider(LocalBackdropHazeState provides outerNavHazeState) {
                    FrostedMenuContainer {
                        innerCardHazeState = LocalBackdropHazeState.current
                        innerBackdropActive = isBackdropBlurActive()
                        Text("Menu Body")
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Menu Body").assertIsDisplayed()
        assertNotNull(innerCardHazeState)
        assertNotSame(outerNavHazeState, innerCardHazeState)
        assertTrue(innerBackdropActive)
    }

    private val sampleLightPalette = MapPalette(
        name = "Test Light",
        isBuiltIn = true,
        landColor = 0xFFF3EFE6L,
        roadColor = 0xFFFFFFFFL,
        waterColor = 0xFFAED9E0L,
        fogColor = 0xFFCFD8DCL,
        gridStrokeColor = 0xFF90A4AEL,
        routeColor = 0xFF2563EBL
    )

    @Test
    fun routesLibraryAndDetailScreens_renderWithFrostedGlassAndInteractiveFilters() {
        val folder = RouteFolderEntity(id = 1L, name = "Hills", colorArgb = 0xFF1B6B52L)
        val route = RouteEntity(
            id = 10L,
            name = "Summit Loop",
            startedAt = 1_000L,
            endedAt = 61_000L,
            durationMillis = 60_000L,
            distanceMeters = 1200.0,
            avgSpeedKmh = 4.5f,
            maxSpeedKmh = 6.0f,
            chunksUnlocked = 3,
            colorArgb = 0xFF2563EBL,
            folderId = 1L
        )
        var openedRouteId by mutableStateOf<Long?>(null)

        composeTestRule.setContent {
            TerraPathTheme(
                darkTheme = false,
                uiBlurEnabled = true,
                uiTransparency = 0.35f,
                uiBlurRadiusDp = 18f
            ) {
                CompositionLocalProvider(LocalBackdropHazeState provides HazeState()) {
                    if (openedRouteId == null) {
                        RoutesLibraryScreen(
                            routes = listOf(route),
                            folders = listOf(folder),
                            onRouteClick = { openedRouteId = it },
                            onHighlightOnMap = {},
                            onDeleteRoute = {}
                        )
                    } else {
                        RouteDetailScreen(
                            route = route,
                            points = emptyList(),
                            folders = listOf(folder),
                            palette = sampleLightPalette,
                            showGridLines = true,
                            onBack = { openedRouteId = null },
                            onShowOnExploreMap = {},
                            onDeleteRoute = {}
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("Route Library").assertIsDisplayed()
        composeTestRule.onNodeWithTag("folder_filter_1").performScrollTo().performClick()
        composeTestRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
        composeTestRule.onNodeWithTag("route_sort_1").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Summit Loop").performScrollTo().performClick()

        composeTestRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
        composeTestRule.onNodeWithTag("detail_walk_to_start_button").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("detail_walk_to_finish_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun achievementsAndSettingsScreens_renderCleanlyWithFrostedGlassEnabled() {
        composeTestRule.setContent {
            TerraPathTheme(
                darkTheme = true,
                uiBlurEnabled = true,
                uiTransparency = 0.50f,
                uiBlurRadiusDp = 28f
            ) {
                CompositionLocalProvider(LocalBackdropHazeState provides HazeState()) {
                    AchievementsScreen(
                        stats = ExplorerStatsEntity(),
                        totalChunksUnlocked = 12,
                        achievements = emptyList()
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Explorer Trophies").assertIsDisplayed()
        composeTestRule.onAllNodes(hasScrollAction())[0].performTouchInput { swipeUp() }
        composeTestRule.onNodeWithTag("achievement_filter_1").performScrollTo().performClick()
    }

    @Test
    fun settingsScreen_rendersFrostedGlassControlsAndSliders() {
        val settings = MapDisplaySettings(
            uiBlurEnabled = true,
            uiTransparency = 0.45f,
            uiBlurRadiusDp = 24f
        )

        composeTestRule.setContent {
            TerraPathTheme(
                darkTheme = false,
                uiBlurEnabled = settings.uiBlurEnabled,
                uiTransparency = settings.uiTransparency,
                uiBlurRadiusDp = settings.uiBlurRadiusDp
            ) {
                CompositionLocalProvider(LocalBackdropHazeState provides HazeState()) {
                    SettingsScreen(
                        activePalette = sampleLightPalette,
                        isDarkTheme = false,
                        themeMode = ThemeMode.LIGHT,
                        customAccentColorArgb = null,
                        apiKey = "",
                        apiProvider = ApiProvider.GEOAPIFY,
                        apiKeyTestState = ApiKeyTestState.Idle,
                        mapSettings = settings,
                        onSelectThemeMode = {},
                        onSetCustomAccentColor = {},
                        onSaveApiKeyConfig = { _, _ -> },
                        onTestApiKey = { _, _ -> },
                        onResetApiKeyTest = {},
                        onClearApiKey = {},
                        onToggleEasyMode = {},
                        onToggleGridLines = {},
                        onToggleSmoothFade = {},
                        onToggleStreetNames = {},
                        onToggleMarkerLabels = {},
                        onToggleDisableCreatedMarkerLabels = {},
                        onToggleSearchLabels = {},
                        onToggleSectorLabels = {},
                        onToggleUiBlur = {},
                        onToggleLocationOutline = {},
                        onSelectMarkerStyle = {}
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithTag("theme_mode_0").assertIsDisplayed()
    }
}
