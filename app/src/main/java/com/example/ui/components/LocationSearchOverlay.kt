package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.MapMarkerEntity
import com.example.data.model.LocationSearchState
import com.example.data.model.LocationTelemetry
import com.example.data.model.MarkerCategory
import com.example.data.model.SearchPlaceResult
import com.example.data.model.SearchResultSource
import com.example.domain.GeoGrid
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle

@Composable
fun LocationSearchBarAndResults(
    searchState: LocationSearchState,
    isExpanded: Boolean,
    savedMarkers: List<MapMarkerEntity>,
    telemetry: LocationTelemetry,
    onExpandedChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onSelectResult: (SearchPlaceResult) -> Unit,
    onQuickPinResult: (SearchPlaceResult) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val quickSavedResults = remember(savedMarkers) {
        savedMarkers.take(4).map { marker ->
            val cat = MarkerCategory.entries.firstOrNull { it.name == marker.categoryName }
                ?: MarkerCategory.LANDMARK
            SearchPlaceResult(
                id = "quick_pin_${marker.id}",
                title = marker.title,
                subtitle = marker.note.ifBlank {
                    String.format("%.4f, %.4f", marker.latitude, marker.longitude)
                },
                latitude = marker.latitude,
                longitude = marker.longitude,
                categoryLabel = "Saved ${cat.label}",
                source = SearchResultSource.SAVED_PIN,
                markerCategory = cat
            )
        }
    }

    Column(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Expressive Pill Search Input
        val blurOn = ExpressiveTheme.colors.uiBlurEnabled
        val backdropActive = isBackdropBlurActive()
        val searchPillShape = RoundedCornerShape(percent = 50)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 50.dp)
                .applyBackdropBlur(shape = searchPillShape, tintColor = ExpressiveTheme.colors.cardSurface),
            shape = searchPillShape,
            color = if (backdropActive) Color.Transparent else ExpressiveTheme.colors.cardSurface,
            shadowElevation = if (blurOn) 0.dp else 4.dp,
            border = if (blurOn) {
                androidx.compose.foundation.BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
            } else {
                null
            }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isExpanded) {
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onExpandedChange(false)
                        },
                        modifier = Modifier.size(48.dp).testTag("close_location_search")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close search",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = "Search locations",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (searchState.query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.search_places_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    BasicTextField(
                        value = searchState.query,
                        onValueChange = { text ->
                            if (!isExpanded) onExpandedChange(true)
                            onQueryChange(text)
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                onSubmitSearch()
                                keyboardController?.hide()
                                focusManager.clearFocus()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused && !isExpanded) {
                                    onExpandedChange(true)
                                }
                            }
                            .testTag("location_search_input")
                    )
                }

                if (searchState.query.isNotEmpty()) {
                    IconButton(
                        onClick = { onClearSearch() },
                        modifier = Modifier.size(48.dp).testTag("clear_location_search")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Clear search text",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Expandable Results Panel
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ExpressiveCard(
                modifier = Modifier.fillMaxWidth(),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(10.dp)) {
                    if (searchState.isSearching) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = ExpressiveTheme.colors.nestedElementSurface,
                            strokeCap = StrokeCap.Round
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    when {
                        searchState.query.trim().isEmpty() -> {
                            Text(
                                text = "Enter a place, street, park, or coordinates (e.g. 37.7749, -122.4194)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                            if (quickSavedResults.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Saved Pins",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    quickSavedResults.forEachIndexed { idx, pinResult ->
                                        SearchResultRow(
                                            result = pinResult,
                                            index = idx,
                                            totalCount = quickSavedResults.size,
                                            telemetry = telemetry,
                                            onSelect = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                onSelectResult(pinResult)
                                            },
                                            onQuickPin = null
                                        )
                                    }
                                }
                            }
                        }

                        searchState.errorMessage != null && searchState.results.isEmpty() -> {
                            val isKeyError = searchState.errorMessage.contains("rejected", ignoreCase = true) ||
                                searchState.errorMessage.contains("API key", ignoreCase = true) ||
                                searchState.errorMessage.contains("token", ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isKeyError) Icons.Filled.Key else Icons.Filled.SearchOff,
                                        contentDescription = "Search failed",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = if (isKeyError) {
                                            searchState.errorMessage
                                        } else {
                                            stringResource(R.string.search_error_retry)
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (isKeyError) {
                                            MaterialTheme.colorScheme.error
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                ExpressivePillButton(
                                    text = "Retry",
                                    onClick = onSubmitSearch,
                                    subtle = true
                                )
                            }
                        }

                        searchState.hasSearched && !searchState.isSearching && searchState.results.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.SearchOff,
                                        contentDescription = "No results",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = stringResource(R.string.search_empty_results),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                searchState.activeProviderLabel?.let { providerLabel ->
                                    SearchProviderAttributionRow(providerLabel = providerLabel)
                                }
                            }
                        }

                        searchState.results.isNotEmpty() -> {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (searchState.errorMessage != null) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.errorContainer,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Key,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = searchState.errorMessage,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 310.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    itemsIndexed(
                                        items = searchState.results,
                                        key = { _, item -> item.id }
                                    ) { idx, item ->
                                        SearchResultRow(
                                            result = item,
                                            index = idx,
                                            totalCount = searchState.results.size,
                                            telemetry = telemetry,
                                            onSelect = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                onSelectResult(item)
                                            },
                                            onQuickPin = if (item.source != SearchResultSource.SAVED_PIN) {
                                                {
                                                    keyboardController?.hide()
                                                    focusManager.clearFocus()
                                                    onQuickPinResult(item)
                                                }
                                            } else null
                                        )
                                    }
                                }
                                if (searchState.hasSearched && !searchState.isSearching) {
                                    searchState.activeProviderLabel?.let { providerLabel ->
                                        SearchProviderAttributionRow(providerLabel = providerLabel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchPlaceResult,
    index: Int,
    totalCount: Int,
    telemetry: LocationTelemetry,
    onSelect: () -> Unit,
    onQuickPin: (() -> Unit)?
) {
    val badgeColor = when (result.source) {
        SearchResultSource.SAVED_PIN -> Color(result.markerCategory.defaultColorArgb)
        SearchResultSource.COORDINATE -> MaterialTheme.colorScheme.primary
        SearchResultSource.OPENSTREETMAP -> Color(result.markerCategory.defaultColorArgb)
    }
    val badgeIcon = when (result.source) {
        SearchResultSource.SAVED_PIN -> result.markerCategory.icon
        SearchResultSource.COORDINATE -> Icons.Filled.Explore
        SearchResultSource.OPENSTREETMAP -> Icons.Filled.Place
    }
    val distanceText = if (telemetry.hasFix) {
        GeoGrid.formatDistance(
            GeoGrid.haversineMeters(
                telemetry.latitude,
                telemetry.longitude,
                result.latitude,
                result.longitude
            )
        )
    } else null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clip(groupedCardShape(index, totalCount))
            .clickable(onClick = onSelect)
            .testTag("search_result_$index"),
        shape = groupedCardShape(index, totalCount),
        color = ExpressiveTheme.colors.nestedElementSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = result.categoryLabel,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.title,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = result.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = result.categoryLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (distanceText != null) {
                        Text(
                            text = "• $distanceText",
                            style = TelemetryTextStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (onQuickPin != null) {
                IconButton(
                    onClick = onQuickPin,
                    modifier = Modifier.size(48.dp).testTag("pin_search_result_$index")
                ) {
                    Icon(
                        imageVector = Icons.Filled.AddLocationAlt,
                        contentDescription = "Save as marker",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.Bookmark,
                    contentDescription = "Saved marker",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp)
                )
            }
        }
    }
}

@Composable
fun SelectedSearchPlaceBanner(
    place: SearchPlaceResult,
    telemetry: LocationTelemetry,
    onFocusMap: () -> Unit,
    onDropMarker: () -> Unit,
    onDismiss: () -> Unit,
    onWalkHere: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val distanceText = if (telemetry.hasFix) {
        val d = GeoGrid.haversineMeters(
            telemetry.latitude,
            telemetry.longitude,
            place.latitude,
            place.longitude
        )
        "${GeoGrid.formatDistance(d)} away"
    } else null

    ExpressiveCard(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth(),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE11D48)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = place.title,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = place.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(place.categoryLabel)
                            if (distanceText != null) append(" • $distanceText")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = String.format("%.5f, %.5f", place.latitude, place.longitude),
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onFocusMap,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Place,
                        contentDescription = "Center on place",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("dismiss_selected_search_place")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss searched place",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onWalkHere != null) {
                    ExpressivePillButton(
                        text = "Walk Here",
                        icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                        onClick = onWalkHere,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("walk_to_search_place_button")
                    )
                }
                ExpressivePillButton(
                    text = "Pin",
                    icon = Icons.Filled.AddLocationAlt,
                    onClick = onDropMarker,
                    subtle = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("pin_selected_search_place")
                )
            }
        }
    }
}

@Composable
private fun SearchProviderAttributionRow(
    providerLabel: String,
    modifier: Modifier = Modifier
) {
    val usesApiKey = providerLabel.contains("API Key", ignoreCase = true)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag("search_provider_attribution"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = if (usesApiKey) Icons.Filled.Key else Icons.Filled.Public,
            contentDescription = null,
            tint = if (usesApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = "Search via $providerLabel",
            style = MaterialTheme.typography.labelSmall,
            color = if (usesApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
