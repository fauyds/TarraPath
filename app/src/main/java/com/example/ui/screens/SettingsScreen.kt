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
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Signpost
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.model.ApiProvider
import com.example.data.model.MapDisplaySettings
import com.example.data.model.MapPalette
import com.example.data.model.ThemeMode
import com.example.ui.components.ColorPickerMath
import com.example.ui.components.CustomColorPickerDialog
import com.example.ui.components.ExpressiveCard
import com.example.ui.components.ExpressiveHorizontalSegmentedSelector
import com.example.ui.components.ExpressivePillButton
import com.example.ui.components.GoogleIconLibraryDialog
import com.example.ui.components.groupedCardShape
import com.example.ui.icons.GoogleIconRegistry
import com.example.ui.map.MiniMapPalettePreview
import com.example.ui.theme.ExpressiveTheme

@Composable
fun SettingsScreen(
    activePalette: MapPalette,
    isDarkTheme: Boolean,
    themeMode: ThemeMode,
    customAccentColorArgb: Long?,
    apiKey: String,
    apiProvider: ApiProvider,
    mapSettings: MapDisplaySettings,
    onSelectThemeMode: (ThemeMode) -> Unit,
    onSetCustomAccentColor: (Long?) -> Unit,
    onSaveApiKeyConfig: (key: String, provider: ApiProvider) -> Unit,
    onClearApiKey: () -> Unit,
    onToggleEasyMode: (Boolean) -> Unit,
    onToggleGridLines: (Boolean) -> Unit,
    onToggleSmoothFade: (Boolean) -> Unit,
    onToggleStreetNames: (Boolean) -> Unit,
    onToggleMarkerLabels: (Boolean) -> Unit,
    onToggleSearchLabels: (Boolean) -> Unit,
    onToggleSectorLabels: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val totalGoogleIcons = remember(context) { GoogleIconRegistry.loadAllIcons(context).size }
    var showIconBrowser by remember { mutableStateOf(false) }
    var previewedIconName by remember { mutableStateOf("Place") }
    var showThemeColorPicker by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    val primaryColorInt = MaterialTheme.colorScheme.primary.toArgb().toLong() and 0xFFFFFFFFL
    val displayedAccentColor = customAccentColorArgb?.let { Color(it.toInt()) } ?: MaterialTheme.colorScheme.primary
    val accentHexLabel = remember(displayedAccentColor) {
        ColorPickerMath.formatHexRgb(displayedAccentColor)
    }

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
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
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
                            showMarkerLabels = mapSettings.showMarkerLabels,
                            showSectorLabels = mapSettings.showSectorLabels,
                            isDarkTheme = isDarkTheme,
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
                            subtitle = "Show street, neighborhood, and city names on OpenStreetMap tiles",
                            checked = mapSettings.showStreetNames,
                            onCheckedChange = onToggleStreetNames,
                            shape = groupedCardShape(0, 4),
                            switchTestTag = "street_names_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.Bookmark,
                            title = "Pin Marker Labels",
                            subtitle = "Display text title badges below your custom dropped pins",
                            checked = mapSettings.showMarkerLabels,
                            onCheckedChange = onToggleMarkerLabels,
                            shape = groupedCardShape(1, 4),
                            switchTestTag = "marker_labels_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.LocationSearching,
                            title = "Search Beacon Labels",
                            subtitle = "Show the floating name tag under searched locations",
                            checked = mapSettings.showSearchLabels,
                            onCheckedChange = onToggleSearchLabels,
                            shape = groupedCardShape(2, 4),
                            switchTestTag = "search_labels_switch"
                        )
                        SettingToggleCard(
                            icon = Icons.Filled.Tag,
                            title = "Sector Coordinate Labels",
                            subtitle = "Show subtle sector index numbers inside map grid cells",
                            checked = mapSettings.showSectorLabels,
                            onCheckedChange = onToggleSectorLabels,
                            shape = groupedCardShape(3, 4),
                            switchTestTag = "sector_labels_switch"
                        )
                    }
                }
            }

            // Google Icon Library Browser Entry
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                        onClick = { showApiKeyDialog = true },
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
            onSave = { newKey, newProvider ->
                onSaveApiKeyConfig(newKey, newProvider)
                showApiKeyDialog = false
            },
            onClear = {
                onClearApiKey()
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }
}

@Composable
private fun ApiKeySettingsDialog(
    initialApiKey: String,
    initialProvider: ApiProvider,
    onSave: (key: String, provider: ApiProvider) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var draftKey by remember(initialApiKey) { mutableStateOf(initialApiKey) }
    var selectedProvider by remember(initialProvider) { mutableStateOf(initialProvider) }
    var revealKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.cardSurface,
        shape = RoundedCornerShape(24.dp),
        title = {
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
                                .weight(1f)
                                .defaultMinSize(minHeight = 42.dp)
                                .clip(RoundedCornerShape(percent = 50))
                                .clickable { selectedProvider = provider }
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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = provider.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1
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
                    onValueChange = { draftKey = it },
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

                Text(
                    text = "Stored locally in on-device preferences.",
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
