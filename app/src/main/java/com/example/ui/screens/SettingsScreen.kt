package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Signpost
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.example.data.local.OfflineRegionEntity
import com.example.data.model.ApiKeyTestState
import com.example.data.model.ApiProvider
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MapPalette
import com.example.data.model.MarkerStyle
import com.example.data.model.OfflineDownloadProgress
import com.example.data.model.RoutingMode
import com.example.data.model.ThemeMode
import com.example.ui.components.ApplyDialogBlurIfEnabled
import com.example.ui.components.ColorPickerMath
import com.example.ui.components.CustomColorPickerDialog
import com.example.ui.components.ExpressiveCard
import com.example.ui.components.ExpressiveHorizontalSegmentedSelector
import com.example.ui.components.ExpressivePillButton
import com.example.ui.components.GoogleIconLibraryDialog
import com.example.ui.components.LocalBackdropHazeState
import com.example.ui.components.LocalBottomOverlayPadding
import com.example.ui.components.MarkerStylePreviewBadge
import com.example.ui.components.OfflineAreasDialog
import com.example.ui.components.conditionalBlur
import com.example.ui.components.formatStorageBytes
import com.example.ui.components.groupedCardShape
import com.example.ui.icons.GoogleIconRegistry
import com.example.ui.map.MiniMapPalettePreview
import com.example.ui.theme.ExpressiveTheme
import dev.chrisbanes.haze.hazeSource

@Composable
fun SettingsScreen(
    activePalette: MapPalette,
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    customAccentColorArgb: Long?,
    apiKey: String,
    apiProvider: ApiProvider,
    apiKeyTestState: ApiKeyTestState,
    mapSettings: MapDisplaySettings,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSetCustomAccentColor: (Long?) -> Unit,
    onSaveApiKeyConfig: (key: String, provider: ApiProvider) -> Unit,
    onTestApiKey: (key: String, provider: ApiProvider) -> Unit,
    onResetApiKeyTest: () -> Unit,
    onClearApiKey: () -> Unit,
    onToggleEasyMode: (Boolean) -> Unit,
    onToggleGridLines: (Boolean) -> Unit,
    onToggleSmoothFade: (Boolean) -> Unit,
    onToggleStreetNames: (Boolean) -> Unit,
    onToggleMarkerLabels: (Boolean) -> Unit,
    onToggleDisableCreatedMarkerLabels: (Boolean) -> Unit,
    onToggleSearchLabels: (Boolean) -> Unit,
    onToggleSectorLabels: (Boolean) -> Unit,
    onToggleUiBlur: (Boolean) -> Unit,
    onChangeUiTransparency: (Float) -> Unit = {},
    onChangeUiBlurRadius: (Float) -> Unit = {},
    onToggleLocationOutline: (Boolean) -> Unit,
    onSelectMarkerStyle: (MarkerStyle) -> Unit,
    offlineRegions: List<OfflineRegionEntity> = emptyList(),
    offlineDownloadProgress: OfflineDownloadProgress = OfflineDownloadProgress(),
    defaultDownloadLat: Double = 37.7749,
    defaultDownloadLon: Double = -122.4194,
    onSelectRoutingMode: (RoutingMode) -> Unit = {},
    onDownloadOfflineArea: (name: String, lat: Double, lon: Double, radiusMeters: Int) -> Unit = { _, _, _, _ -> },
    onDeleteOfflineRegion: (OfflineRegionEntity) -> Unit = {},
    onClearOfflineDownloadError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val totalGoogleIcons = remember(context) { GoogleIconRegistry.loadAllIcons(context).size }
    var showIconBrowser by remember { mutableStateOf(false) }
    var previewedIconName by remember { mutableStateOf("Place") }
    var showThemeColorPicker by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showOfflineAreasDialog by remember { mutableStateOf(false) }

    val primaryColorInt = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    val displayedAccentColor = customAccentColorArgb?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    val accentHexLabel = remember(displayedAccentColor) {
        ColorPickerMath.formatHexRgb(displayedAccentColor)
    }

    val anyDialogOpen = showIconBrowser || showThemeColorPicker || showApiKeyDialog || showOfflineAreasDialog
    val hazeState = LocalBackdropHazeState.current
    val bottomOverlayPadding = LocalBottomOverlayPadding.current

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ExpressiveTheme.colors.appBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .then(
                    if (mapSettings.uiBlurEnabled && hazeState != null) {
                        Modifier.hazeSource(state = hazeState)
                    } else {
                        Modifier
                    }
                )
                .conditionalBlur(
                    enabled = mapSettings.uiBlurEnabled && anyDialogOpen,
                    radius = (mapSettings.uiBlurRadiusDp * 0.5f).coerceIn(2f, 20f).dp
                )
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + bottomOverlayPadding
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Fog mode, map appearance, and on-map labels",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Live Map Behavior Preview
            item {
                ExpressiveCard(shadowElevation = 4.dp) {
                    Column {
                        MiniMapPalettePreview(
                            palette = activePalette,
                            showGridLines = mapSettings.showGridLines,
                            showStreetLabels = mapSettings.showStreetNames,
                            showMarkerLabels = mapSettings.showMarkerLabels && !mapSettings.disableCreatedMarkerLabels,
                            showSectorLabels = mapSettings.showSectorLabels,
                            isDarkTheme = isDarkTheme,
                            markerStyle = mapSettings.markerStyle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Map,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isDarkTheme) "Default Dark Map" else "Default Light Map",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (mapSettings.easyModeEnabled) {
                                        "Easy Mode active • Transparent fog veil"
                                    } else {
                                        "Classic Mode • Solid fog of war"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // App & Map Light / Dark Appearance + Custom Chrome/Theme Color Picker
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Appearance & Map Theme",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExpressiveHorizontalSegmentedSelector(
                        options = ThemeMode.entries,
                        selectedOption = themeMode,
                        onOptionSelected = onSelectThemeMode,
                        labelProvider = { it.label },
                        testTagPrefix = "theme_mode"
                    )
                    ExpressiveCard(
                        onClick = { showThemeColorPicker = true },
                        modifier = Modifier.testTag("settings_custom_color_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = displayedAccentColor,
                                border = BorderStroke(2.dp, Color.White),
                                modifier = Modifier.size(38.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Palette,
                                        contentDescription = null,
                                        tint = ColorPickerMath.contrastTextColor(displayedAccentColor),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom Color Picker",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (customAccentColorArgb != null) {
                                        "Custom chrome & route accent • $accentHexLabel"
                                    } else {
                                        "Dynamic Monet (Default) • Tap to pick any color"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (customAccentColorArgb != null) {
                                TextButton(
                                    onClick = { onSetCustomAccentColor(null) },
                                    modifier = Modifier.testTag("reset_custom_color_button")
                                ) {
                                    Text("Reset")
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = "Open custom color picker",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingToggleCard(
                            icon = Icons.Filled.Gradient,
                            title = "UI Blur",
                            subtitle = "Enable frosted translucent cards and blur behind overlays and dialogs",
                            checked = mapSettings.uiBlurEnabled,
                            onCheckedChange = onToggleUiBlur,
                            shape = groupedCardShape(0, 2),
                            switchTestTag = "ui_blur_switch"
                        )
                        ExpressiveCard(shape = groupedCardShape(1, 2)) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                val transparencyPct = (mapSettings.uiTransparency * 100f).roundToInt()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "UI Transparency",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$transparencyPct%",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("ui_transparency_value")
                                    )
                                }
                                Text(
                                    text = "Adjust how translucent cards, bars, and frosted overlays appear",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = mapSettings.uiTransparency,
                                    onValueChange = { value ->
                                        if (!mapSettings.uiBlurEnabled) onToggleUiBlur(true)
                                        onChangeUiTransparency(value)
                                    },
                                    valueRange = 0f..0.85f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ui_transparency_slider")
                                )

                                HorizontalDivider(
                                    thickness = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )

                                val blurRadiusInt = mapSettings.uiBlurRadiusDp.roundToInt()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Frosted Glass Blur Radius",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "$blurRadiusInt dp",
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.testTag("ui_blur_radius_value")
                                    )
                                }
                                Text(
                                    text = "Control the backdrop blur intensity behind translucent UI elements",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Slider(
                                    value = mapSettings.uiBlurRadiusDp,
                                    onValueChange = { value ->
                                        if (!mapSettings.uiBlurEnabled) onToggleUiBlur(true)
                                        onChangeUiBlurRadius(value)
                                    },
                                    valueRange = 0f..40f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ui_blur_radius_slider")
                                )
                            }
                        }
                    }
                    Text(
                        text = "Map colors automatically follow Light and Dark mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            // Fog of War & Grid Settings
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Fog of War & Grid",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingToggleCard(
                            icon = Icons.Filled.Visibility,
                            title = "Easy Mode (Transparent Fog)",
                            subtitle = "See streets and terrain through a light fog veil before unlocking sectors",
                            checked = mapSettings.easyModeEnabled,
                            onCheckedChange = onToggleEasyMode,
                            shape = groupedCardShape(0, 3),
                            switchTestTag = "easy_mode_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.BlurOn,
                            title = "Smooth Fade Between Zones",
                            subtitle = "Apply a smooth vignette gradient blur at the border between fog and explored territory",
                            checked = mapSettings.smoothFadeEnabled,
                            onCheckedChange = onToggleSmoothFade,
                            shape = groupedCardShape(1, 3),
                            switchTestTag = "smooth_fade_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.GridOn,
                            title = "Sector Grid Borders",
                            subtitle = "Show 250m × 250m sector and district boundaries",
                            checked = mapSettings.showGridLines,
                            onCheckedChange = onToggleGridLines,
                            shape = groupedCardShape(2, 3),
                            switchTestTag = "grid_lines_switch"
                        )
                    }
                }
            }

            // Location Indicator Toggle
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Location Indicator",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SettingToggleCard(
                        icon = Icons.Filled.MyLocation,
                        title = "Location Dot & Arrow Outline",
                        subtitle = "Show the white outline around your GPS dot and direction arrow",
                        checked = mapSettings.showLocationOutline,
                        onCheckedChange = onToggleLocationOutline,
                        shape = RoundedCornerShape(16.dp),
                        switchTestTag = "location_outline_switch"
                    )
                }
            }

            // Walking Navigation & Offline Areas
            item {
                val totalOfflineBytes = remember(offlineRegions) { offlineRegions.sumOf { it.sizeBytes } }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Walking Navigation & Offline Areas",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExpressiveHorizontalSegmentedSelector(
                        options = RoutingMode.entries,
                        selectedOption = mapSettings.routingMode,
                        onOptionSelected = onSelectRoutingMode,
                        labelProvider = { it.label },
                        testTagPrefix = "routing_mode"
                    )
                    Text(
                        text = mapSettings.routingMode.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    ExpressiveCard(
                        onClick = { showOfflineAreasDialog = true },
                        modifier = Modifier.testTag("settings_offline_areas_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = ExpressiveTheme.colors.nestedElementSurface,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = if (offlineRegions.isNotEmpty()) {
                                            Icons.Filled.OfflinePin
                                        } else {
                                            Icons.AutoMirrored.Filled.DirectionsWalk
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Offline Map & Route Areas",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (offlineRegions.isEmpty()) {
                                        "No areas downloaded • Tap to download for CPU offline walking"
                                    } else {
                                        "${offlineRegions.size} areas downloaded • ${formatStorageBytes(totalOfflineBytes)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Manage offline areas",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // On-Map Labels Toggles
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "On-Map Labels",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        SettingToggleCard(
                            icon = Icons.Filled.Signpost,
                            title = "Street & Map Names",
                            subtitle = "Show street, neighborhood, and city names on map tiles",
                            checked = mapSettings.showStreetNames,
                            onCheckedChange = onToggleStreetNames,
                            shape = groupedCardShape(0, 5),
                            switchTestTag = "street_names_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.Bookmark,
                            title = "Pin Marker Labels",
                            subtitle = "Display text title badges below your custom dropped pins",
                            checked = mapSettings.showMarkerLabels && !mapSettings.disableCreatedMarkerLabels,
                            onCheckedChange = onToggleMarkerLabels,
                            shape = groupedCardShape(1, 5),
                            switchTestTag = "marker_labels_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.TouchApp,
                            title = "Disable Created Marker Labels",
                            subtitle = "Hide labels on created markers; press a marker to show its label for 2 seconds",
                            checked = mapSettings.disableCreatedMarkerLabels,
                            onCheckedChange = onToggleDisableCreatedMarkerLabels,
                            shape = groupedCardShape(2, 5),
                            switchTestTag = "disable_marker_labels_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.LocationSearching,
                            title = "Search Beacon Labels",
                            subtitle = "Show the floating name tag under searched locations",
                            checked = mapSettings.showSearchLabels,
                            onCheckedChange = onToggleSearchLabels,
                            shape = groupedCardShape(3, 5),
                            switchTestTag = "search_labels_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.Tag,
                            title = "Sector Coordinate Labels",
                            subtitle = "Show subtle sector index numbers inside map grid cells",
                            checked = mapSettings.showSectorLabels,
                            onCheckedChange = onToggleSectorLabels,
                            shape = groupedCardShape(4, 5),
                            switchTestTag = "sector_labels_switch"
                        )
                    }
                }
            }

            // Marker Style Chooser & Google Icon Library Browser Entry
            item {
                val previewAccentArgb = displayedAccentColor.toArgb().toLong() and 0xFFFFFFFFL
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Marker Style",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val styles = MarkerStyle.entries
                        styles.forEachIndexed { index, style ->
                            val selected = mapSettings.markerStyle == style
                            ExpressiveCard(
                                shape = groupedCardShape(index, styles.size),
                                border = if (selected) {
                                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    null
                                },
                                onClick = { onSelectMarkerStyle(style) },
                                modifier = Modifier.testTag("marker_style_${style.name}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .defaultMinSize(minHeight = 64.dp)
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = ExpressiveTheme.colors.nestedElementSurface,
                                        modifier = Modifier.size(46.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            MarkerStylePreviewBadge(
                                                iconName = "Place",
                                                colorArgb = previewAccentArgb,
                                                markerStyle = style,
                                                badgeSize = 34.dp
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = style.label,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = style.subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    RadioButton(
                                        selected = selected,
                                        onClick = { onSelectMarkerStyle(style) }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "Markers & Icon Library",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExpressiveCard(
                        onClick = { showIconBrowser = true },
                        modifier = Modifier.testTag("settings_icon_library_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = ExpressiveTheme.colors.nestedElementSurface,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Apps,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Google Icon Library",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Browse & search $totalGoogleIcons Material icons available for map markers",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Open Google Icon Library",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // API Key Setting Section
            item {
                val hasKey = apiKey.isNotBlank()
                val maskedKey = if (apiKey.length > 4) {
                    "••••" + apiKey.takeLast(4)
                } else if (hasKey) {
                    "••••"
                } else {
                    ""
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "API & Services",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ExpressiveCard(
                        onClick = {
                            onResetApiKeyTest()
                            showApiKeyDialog = true
                        },
                        modifier = Modifier.testTag("settings_api_key_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = ExpressiveTheme.colors.nestedElementSurface,
                                modifier = Modifier.size(40.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Filled.Key,
                                        contentDescription = null,
                                        tint = if (hasKey) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "API Key",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (hasKey) {
                                        "Configured ($maskedKey) • ${apiProvider.label}"
                                    } else {
                                        "Not set • Tap to add a custom or geocoding API key"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Configure API key",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showIconBrowser) {
        GoogleIconLibraryDialog(
            selectedIconName = previewedIconName,
            onSelectIcon = { chosen ->
                previewedIconName = chosen
                showIconBrowser = false
            },
            onDismiss = { showIconBrowser = false }
        )
    }

    if (showThemeColorPicker) {
        CustomColorPickerDialog(
            initialColorArgb = customAccentColorArgb ?: primaryColorInt,
            title = "Theme & Chrome Color",
            subtitle = "Customize buttons, selectors, route line, and grid accents",
            showResetToDefault = customAccentColorArgb != null,
            onResetToDefault = {
                onSetCustomAccentColor(null)
                showThemeColorPicker = false
            },
            onConfirm = { pickedArgb ->
                onSetCustomAccentColor(pickedArgb)
                showThemeColorPicker = false
            },
            onDismiss = { showThemeColorPicker = false }
        )
    }

    if (showApiKeyDialog) {
        ApiKeySettingsDialog(
            initialApiKey = apiKey,
            initialProvider = apiProvider,
            testState = apiKeyTestState,
            onTestKey = onTestApiKey,
            onResetTest = onResetApiKeyTest,
            onSave = { newKey, newProvider ->
                onSaveApiKeyConfig(newKey, newProvider)
                showApiKeyDialog = false
            },
            onClear = {
                onClearApiKey()
                showApiKeyDialog = false
            },
            onDismiss = {
                onResetApiKeyTest()
                showApiKeyDialog = false
            }
        )
    }

    if (showOfflineAreasDialog) {
        OfflineAreasDialog(
            centerLat = defaultDownloadLat,
            centerLon = defaultDownloadLon,
            offlineRegions = offlineRegions,
            downloadProgress = offlineDownloadProgress,
            onDownloadArea = onDownloadOfflineArea,
            onDeleteRegion = onDeleteOfflineRegion,
            onClearError = onClearOfflineDownloadError,
            onDismiss = { showOfflineAreasDialog = false }
        )
    }
}

@Composable
private fun ApiKeySettingsDialog(
    initialApiKey: String,
    initialProvider: ApiProvider,
    testState: ApiKeyTestState,
    onTestKey: (key: String, provider: ApiProvider) -> Unit,
    onResetTest: () -> Unit,
    onSave: (key: String, provider: ApiProvider) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var draftKey by remember(initialApiKey) { mutableStateOf(initialApiKey) }
    var selectedProvider by remember(initialProvider) { mutableStateOf(initialProvider) }
    var revealKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(24.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Set API Key",
                    style = MaterialTheme.typography.headlineSmall
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Provider Mode",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ApiProvider.entries.forEach { provider ->
                        val selected = selectedProvider == provider
                        Surface(
                            modifier = Modifier
                                .weight(provider.label.length.toFloat() + 4f)
                                .defaultMinSize(minHeight = 48.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable {
                                    selectedProvider = provider
                                    if (testState !is ApiKeyTestState.Idle) onResetTest()
                                }
                                .testTag("api_provider_${provider.name}"),
                            shape = RoundedCornerShape(percent = 50),
                            color = if (selected) {
                                ExpressiveTheme.colors.selectorSelected
                            } else {
                                ExpressiveTheme.colors.nestedElementSurface
                            }
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = provider.label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                Text(
                    text = selectedProvider.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = draftKey,
                    onValueChange = {
                        draftKey = it
                        if (testState !is ApiKeyTestState.Idle) onResetTest()
                    },
                    label = { Text("API Key") },
                    placeholder = { Text(selectedProvider.keyPlaceholder) },
                    singleLine = true,
                    visualTransformation = if (revealKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { revealKey = !revealKey },
                            modifier = Modifier.testTag("toggle_api_key_visibility")
                        ) {
                            Icon(
                                imageVector = if (revealKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (revealKey) "Hide API key" else "Show API key"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("api_key_input")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ExpressivePillButton(
                        text = if (testState is ApiKeyTestState.Testing) "Testing…" else "Test Key",
                        onClick = { onTestKey(draftKey.trim(), selectedProvider) },
                        enabled = draftKey.trim().isNotEmpty() && testState !is ApiKeyTestState.Testing,
                        subtle = true,
                        modifier = Modifier.testTag("test_api_key_button")
                    )
                    if (testState is ApiKeyTestState.Testing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.5.dp
                        )
                    }
                }

                when (testState) {
                    ApiKeyTestState.Idle, ApiKeyTestState.Testing -> Unit
                    is ApiKeyTestState.Verified -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_test_verified")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CheckCircle,
                                    contentDescription = "Verified",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = testState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    is ApiKeyTestState.Failed -> {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("api_key_test_failed")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = "Verification failed",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = testState.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "Stored locally on-device. Geoapify keys power map tiles and place search; without one, standard OpenStreetMap tiles are used.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Save Key",
                onClick = { onSave(draftKey.trim(), selectedProvider) },
                modifier = Modifier.testTag("save_api_key_button")
            )
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (initialApiKey.isNotBlank()) {
                    TextButton(
                        onClick = onClear,
                        modifier = Modifier.testTag("clear_api_key_button")
                    ) {
                        Text("Clear", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}

@Composable
private fun SettingToggleCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    shape: Shape,
    switchTestTag: String
) {
    ExpressiveCard(
        shape = shape,
        onClick = { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = ExpressiveTheme.colors.nestedElementSurface,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                modifier = Modifier.testTag(switchTestTag)
            )
        }
    }
}
