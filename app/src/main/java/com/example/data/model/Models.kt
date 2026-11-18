package com.example.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Speed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class MarkerCategory(
    val label: String,
    val icon: ImageVector,
    val defaultColorArgb: Long,
    val defaultIconName: String
) {
    HOME("Home", Icons.Filled.Home, 0xFF2563EB, "Home"),
    SHOP("Shop", Icons.Filled.ShoppingBag, 0xFFDB2777, "ShoppingBag"),
    RESTAURANT("Food", Icons.Filled.Restaurant, 0xFFEA580C, "Restaurant"),
    PARK("Park", Icons.Filled.Park, 0xFF16A34A, "Park"),
    TRANSIT("Transit", Icons.Filled.DirectionsTransit, 0xFF7C3AED, "DirectionsTransit"),
    LANDMARK("Spot", Icons.Filled.Flag, 0xFF0D9488, "Flag")
}

enum class AchievementCategory(val label: String) {
    DISTANCE("Distance"),
    STEPS("Steps"),
    SPEED("Speed"),
    EXPLORER("Sectors")
}

data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val category: AchievementCategory,
    val targetValue: Double,
    val unit: String,
    val icon: ImageVector
)

data class AchievementUiItem(
    val definition: AchievementDefinition,
    val currentValue: Double,
    val isUnlocked: Boolean,
    val unlockedAt: Long?
) {
    val progressFraction: Float
        get() = if (definition.targetValue <= 0.0) 0f
        else (currentValue / definition.targetValue).toFloat().coerceIn(0f, 1f)
}

object AchievementCatalog {
    val all: List<AchievementDefinition> = listOf(
        // Distance milestones
        AchievementDefinition(
            id = "dist_100m",
            title = "First Footsteps",
            description = "Travel 100 meters with GPS tracking active",
            category = AchievementCategory.DISTANCE,
            targetValue = 100.0,
            unit = "m",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk
        ),
        AchievementDefinition(
            id = "dist_400m",
            title = "Block Walker",
            description = "Cover 400 meters across neighborhood streets",
            category = AchievementCategory.DISTANCE,
            targetValue = 400.0,
            unit = "m",
            icon = Icons.Filled.Route
        ),
        AchievementDefinition(
            id = "dist_1000m",
            title = "Kilometer Club",
            description = "Reach 1,000 meters (1.0 km) of total distance",
            category = AchievementCategory.DISTANCE,
            targetValue = 1000.0,
            unit = "m",
            icon = Icons.Filled.Explore
        ),
        AchievementDefinition(
            id = "dist_5000m",
            title = "Trailblazer",
            description = "Explore 5,000 meters (5.0 km) on foot or bike",
            category = AchievementCategory.DISTANCE,
            targetValue = 5000.0,
            unit = "m",
            icon = Icons.Filled.Landscape
        ),
        AchievementDefinition(
            id = "dist_10000m",
            title = "Horizon Seeker",
            description = "Accumulate 10,000 meters (10.0 km) explored",
            category = AchievementCategory.DISTANCE,
            targetValue = 10000.0,
            unit = "m",
            icon = Icons.Filled.MilitaryTech
        ),
        // Step count milestones
        AchievementDefinition(
            id = "steps_500",
            title = "Warm Up",
            description = "Record 500 steps on the step sensor",
            category = AchievementCategory.STEPS,
            targetValue = 500.0,
            unit = "steps",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk
        ),
        AchievementDefinition(
            id = "steps_2500",
            title = "Daily Stride",
            description = "Log 2,500 real steps while exploring",
            category = AchievementCategory.STEPS,
            targetValue = 2500.0,
            unit = "steps",
            icon = Icons.AutoMirrored.Filled.DirectionsRun
        ),
        AchievementDefinition(
            id = "steps_5000",
            title = "Five-K Strider",
            description = "Cross 5,000 tracked footsteps",
            category = AchievementCategory.STEPS,
            targetValue = 5000.0,
            unit = "steps",
            icon = Icons.Filled.Explore
        ),
        AchievementDefinition(
            id = "steps_10000",
            title = "Ten-K Legend",
            description = "Complete 10,000 steps in TerraPath",
            category = AchievementCategory.STEPS,
            targetValue = 10000.0,
            unit = "steps",
            icon = Icons.Filled.MilitaryTech
        ),
        // Speed tier milestones
        AchievementDefinition(
            id = "speed_1",
            title = "Mindful Stroll",
            description = "Reach a steady walking pace of 1 km/h",
            category = AchievementCategory.SPEED,
            targetValue = 1.0,
            unit = "km/h",
            icon = Icons.AutoMirrored.Filled.DirectionsWalk
        ),
        AchievementDefinition(
            id = "speed_4",
            title = "Brisk Pace",
            description = "Hit a brisk walking speed of 4 km/h",
            category = AchievementCategory.SPEED,
            targetValue = 4.0,
            unit = "km/h",
            icon = Icons.AutoMirrored.Filled.DirectionsRun
        ),
        AchievementDefinition(
            id = "speed_12",
            title = "Velocity Dash",
            description = "Clock 12 km/h while running or cycling",
            category = AchievementCategory.SPEED,
            targetValue = 12.0,
            unit = "km/h",
            icon = Icons.Filled.DirectionsBike
        ),
        AchievementDefinition(
            id = "speed_20",
            title = "Express Cruiser",
            description = "Reach 20 km/h peak movement speed",
            category = AchievementCategory.SPEED,
            targetValue = 20.0,
            unit = "km/h",
            icon = Icons.Filled.Bolt
        ),
        // Fog of War chunk explorer milestones
        AchievementDefinition(
            id = "chunk_1",
            title = "First Sector",
            description = "Reveal your very first fog-of-war sector",
            category = AchievementCategory.EXPLORER,
            targetValue = 1.0,
            unit = "chunks",
            icon = Icons.Filled.GridView
        ),
        AchievementDefinition(
            id = "chunk_10",
            title = "Neighborhood Scout",
            description = "Clear fog from 10 geographic sectors",
            category = AchievementCategory.EXPLORER,
            targetValue = 10.0,
            unit = "chunks",
            icon = Icons.Filled.Map
        ),
        AchievementDefinition(
            id = "chunk_50",
            title = "Cartographer",
            description = "Unlock 50 map sectors around your area",
            category = AchievementCategory.EXPLORER,
            targetValue = 50.0,
            unit = "chunks",
            icon = Icons.Filled.Explore
        ),
        AchievementDefinition(
            id = "chunk_100",
            title = "District Master",
            description = "Reveal 100 sectors across the world grid",
            category = AchievementCategory.EXPLORER,
            targetValue = 100.0,
            unit = "chunks",
            icon = Icons.Filled.Speed
        ),
        AchievementDefinition(
            id = "chunk_250",
            title = "Territory Pioneer",
            description = "Unlock 250 map sectors and push back the fog",
            category = AchievementCategory.EXPLORER,
            targetValue = 250.0,
            unit = "chunks",
            icon = Icons.Filled.Landscape
        ),
        AchievementDefinition(
            id = "chunk_500",
            title = "Regional Pathfinder",
            description = "Reveal 500 sectors across your exploring adventures",
            category = AchievementCategory.EXPLORER,
            targetValue = 500.0,
            unit = "chunks",
            icon = Icons.Filled.Explore
        ),
        AchievementDefinition(
            id = "chunk_1000",
            title = "Grand World Explorer",
            description = "Unlock 1,000 total fog-of-war sectors as a master explorer",
            category = AchievementCategory.EXPLORER,
            targetValue = 1000.0,
            unit = "chunks",
            icon = Icons.Filled.MilitaryTech
        )
    )
}

data class MapPalette(
    val id: Long = 0L,
    val name: String,
    val isBuiltIn: Boolean = false,
    val landColor: Long,
    val roadColor: Long,
    val waterColor: Long,
    val fogColor: Long,
    val gridStrokeColor: Long,
    val routeColor: Long
) {
    val land: Color get() = Color(landColor)
    val road: Color get() = Color(roadColor)
    val water: Color get() = Color(waterColor)
    val fog: Color get() = Color(fogColor)
    val gridStroke: Color get() = Color(gridStrokeColor)
    val route: Color get() = Color(routeColor)
}

enum class MapColorChannel(val label: String, val subtitle: String) {
    LAND("Background / Land", "Base terrain ground fill"),
    ROAD("Roads & Paths", "Streets and arterial avenues"),
    WATER("Waterways", "Rivers, lakes, and streams"),
    FOG("Fog of War", "Solid overlay for unexplored sectors"),
    GRID("Chunk Grid Stroke", "Sector borders across the map"),
    ROUTE("Default Route Line", "Active & default recorded path")
}

data class ChunkCoord(val cx: Int, val cy: Int) {
    val key: String get() = "$cx:$cy"
}

data class DistrictInfo(
    val dx: Int,
    val dy: Int,
    val displayNumber: Int,
    val unlockedCount: Int,
    val totalCount: Int = 100
) {
    val key: String get() = "$dx:$dy"
    val percentage: Int
        get() = ((unlockedCount.toFloat() / totalCount.toFloat()) * 100f).toInt().coerceIn(0, 100)
}

data class LatLonPoint(
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float = 0f,
    val timestamp: Long = System.currentTimeMillis()
)

data class LocationTelemetry(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val speedKmh: Float = 0f,
    val accuracyMeters: Float = 0f,
    val hasFix: Boolean = false,
    val hasPermission: Boolean = false,
    val lastUpdatedAt: Long = 0L
)

data class ActiveRecordingState(
    val isRecording: Boolean = false,
    val startedAtMillis: Long = 0L,
    val elapsedMillis: Long = 0L,
    val distanceMeters: Double = 0.0,
    val maxSpeedKmh: Float = 0f,
    val chunksUnlockedDuringRoute: Int = 0,
    val points: List<LatLonPoint> = emptyList()
) {
    val avgSpeedKmh: Float
        get() {
            if (elapsedMillis < 1000L || distanceMeters <= 0.0) return 0f
            val hours = elapsedMillis / 3_600_000.0
            return (distanceMeters / 1000.0 / hours).toFloat()
        }
}

enum class SearchResultSource {
    SAVED_PIN,
    COORDINATE,
    OPENSTREETMAP
}

data class SearchPlaceResult(
    val id: String,
    val title: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double,
    val categoryLabel: String,
    val source: SearchResultSource,
    val markerCategory: MarkerCategory = MarkerCategory.LANDMARK
)

data class LocationSearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val results: List<SearchPlaceResult> = emptyList(),
    val errorMessage: String? = null,
    val hasSearched: Boolean = false,
    val activeProviderLabel: String? = null
)

sealed interface ApiKeyTestState {
    data object Idle : ApiKeyTestState
    data object Testing : ApiKeyTestState
    data class Verified(val message: String) : ApiKeyTestState
    data class Failed(val message: String) : ApiKeyTestState
}

enum class MarkerStyle(
    val label: String,
    val subtitle: String
) {
    ICON_ONLY(
        label = "Icon Only",
        subtitle = "Colored icon floating directly on the map"
    ),
    ICON_OUTLINE(
        label = "Icon with Outline",
        subtitle = "Colored icon with an adaptive contrast outline"
    ),
    CIRCLE_ICON(
        label = "Icon in Circle",
        subtitle = "Colored circle with an adaptive outline and centered icon"
    )
}

data class MapDisplaySettings(
    val easyModeEnabled: Boolean = false,
    val showGridLines: Boolean = true,
    val showStreetNames: Boolean = true,
    val showMarkerLabels: Boolean = true,
    val disableCreatedMarkerLabels: Boolean = false,
    val showSearchLabels: Boolean = true,
    val showSectorLabels: Boolean = false,
    val smoothFadeEnabled: Boolean = true,
    val uiBlurEnabled: Boolean = false,
    val markerStyle: MarkerStyle = MarkerStyle.CIRCLE_ICON
)

enum class ApiProvider(
    val label: String,
    val subtitle: String,
    val keyPlaceholder: String
) {
    CUSTOM_GENERAL(
        label = "General",
        subtitle = "Store a personal API key; search continues via OpenStreetMap",
        keyPlaceholder = "Paste your API key…"
    ),
    GEOAPIFY(
        label = "Geoapify",
        subtitle = "Use your Geoapify API key for fast place autocomplete (OSM fallback)",
        keyPlaceholder = "Geoapify API key…"
    ),
    LOCATION_IQ(
        label = "LocationIQ",
        subtitle = "Use your LocationIQ access token for geocoding search (OSM fallback)",
        keyPlaceholder = "LocationIQ token (pk.…)"
    )
}


