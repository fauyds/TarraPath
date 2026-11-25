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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.RouteEntity
import com.example.data.local.RouteFolderEntity
import com.example.data.local.RoutePointEntity
import com.example.data.model.LatLonPoint
import com.example.data.model.MapPalette
import com.example.domain.GeoGrid
import com.example.ui.components.DeleteFolderConfirmDialog
import com.example.ui.components.ExpressiveCard
import com.example.ui.components.ExpressiveHorizontalSegmentedSelector
import com.example.ui.components.ExpressivePillButton
import com.example.ui.components.FolderBadgeChip
import com.example.ui.components.FolderEditorDialog
import com.example.ui.components.FrostedMenuContainer
import com.example.ui.components.LocalBottomOverlayPadding
import com.example.ui.components.MoveRouteToFolderDialog
import com.example.ui.components.applyBackdropBlur
import com.example.ui.components.groupedCardShape
import com.example.ui.components.isBackdropBlurActive
import com.example.ui.map.MiniMapPalettePreview
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

enum class RouteSortOrder(val label: String) {
    RECENT("Recent"),
    FASTEST("Fastest Time"),
    SHORTEST("Shortest")
}

private sealed interface FolderFilter {
    data object All : FolderFilter
    data class Specific(val folderId: Long) : FolderFilter
}

@Composable
fun RoutesLibraryScreen(
    routes: List<RouteEntity>,
    folders: List<RouteFolderEntity> = emptyList(),
    onRouteClick: (Long) -> Unit,
    onHighlightOnMap: (Long) -> Unit,
    onWalkToRouteStart: (Long) -> Unit = {},
    onDeleteRoute: (Long) -> Unit,
    onCreateFolder: (name: String, colorArgb: Long, onCreated: (Long) -> Unit) -> Unit = { _, _, _ -> },
    onUpdateFolder: (folder: RouteFolderEntity, newName: String, newColorArgb: Long) -> Unit = { _, _, _ -> },
    onDeleteFolder: (folderId: Long) -> Unit = {},
    onMoveRouteToFolder: (routeId: Long, folderId: Long?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    var sortOrder by remember { mutableStateOf(RouteSortOrder.RECENT) }
    var compareMode by remember { mutableStateOf(false) }
    var selectedCompareIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var selectedFilter by remember { mutableStateOf<FolderFilter>(FolderFilter.All) }

    var showCreateFolderDialog by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<RouteFolderEntity?>(null) }
    var deletingFolder by remember { mutableStateOf<RouteFolderEntity?>(null) }
    var movingRoute by remember { mutableStateOf<RouteEntity?>(null) }

    LaunchedEffect(folders, selectedFilter) {
        val specific = selectedFilter as? FolderFilter.Specific
        if (specific != null && folders.none { it.id == specific.folderId }) {
            selectedFilter = FolderFilter.All
        }
    }

    val folderMap = remember(folders) { folders.associateBy { it.id } }
    val folderCounts = remember(routes) {
        routes.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
    }
    val activeFolder = remember(selectedFilter, folderMap) {
        (selectedFilter as? FolderFilter.Specific)?.let { folderMap[it.folderId] }
    }

    val filteredRoutes = remember(routes, selectedFilter) {
        when (val f = selectedFilter) {
            FolderFilter.All -> routes
            is FolderFilter.Specific -> routes.filter { it.folderId == f.folderId }
        }
    }

    val sortedRoutes = remember(filteredRoutes, sortOrder) {
        when (sortOrder) {
            RouteSortOrder.RECENT -> filteredRoutes.sortedByDescending { it.endedAt }
            RouteSortOrder.FASTEST -> filteredRoutes.sortedBy { it.durationMillis }
            RouteSortOrder.SHORTEST -> filteredRoutes.sortedBy { it.distanceMeters }
        }
    }

    val totalDistance = remember(routes) { routes.sumOf { it.distanceMeters } }
    val totalDuration = remember(routes) { routes.sumOf { it.durationMillis } }
    val fastestAvgSpeed = remember(routes) { routes.maxOfOrNull { it.avgSpeedKmh } ?: 0f }

    val anyDialogOpen = showCreateFolderDialog ||
        editingFolder != null ||
        deletingFolder != null ||
        movingRoute != null
    val bottomOverlayPadding = LocalBottomOverlayPadding.current

    FrostedMenuContainer(
        modifier = modifier,
        blurBehindDialog = anyDialogOpen
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + bottomOverlayPadding
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Route Library",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = "Organize walks in folders and compare paths",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (routes.size >= 2) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ExpressivePillButton(
                            text = if (compareMode) "Done" else "Compare",
                            icon = Icons.Filled.CompareArrows,
                            subtle = !compareMode,
                            onClick = {
                                compareMode = !compareMode
                                if (!compareMode) selectedCompareIds = emptyList()
                            },
                            modifier = Modifier.testTag("compare_routes_button")
                        )
                    }
                }
            }

            // Bento Grid Summary Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BentoStatTile(
                            title = "Saved Routes",
                            value = "${routes.size}",
                            icon = Icons.Filled.Route,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 4.dp,
                                bottomStart = 4.dp, bottomEnd = 4.dp
                            )
                        )
                        BentoStatTile(
                            title = "Total Distance",
                            value = GeoGrid.formatDistance(totalDistance),
                            icon = Icons.Filled.Directions,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 16.dp,
                                bottomStart = 4.dp, bottomEnd = 4.dp
                            )
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        BentoStatTile(
                            title = "Total Time",
                            value = GeoGrid.formatTimerClock(totalDuration),
                            icon = Icons.Filled.Schedule,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 4.dp,
                                bottomStart = 16.dp, bottomEnd = 4.dp
                            )
                        )
                        BentoStatTile(
                            title = "Best Avg Pace",
                            value = String.format("%.1f km/h", fastestAvgSpeed),
                            icon = Icons.Filled.Speed,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 4.dp,
                                bottomStart = 4.dp, bottomEnd = 16.dp
                            )
                        )
                    }
                }
            }

            // Route Folders Shelf & Filter
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Folders",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        ExpressivePillButton(
                            text = "New Folder",
                            icon = Icons.Filled.CreateNewFolder,
                            subtle = true,
                            onClick = { showCreateFolderDialog = true },
                            modifier = Modifier.testTag("new_folder_button")
                        )
                    }

                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item(key = "filter_all") {
                            FolderFilterPill(
                                label = "All",
                                count = routes.size,
                                icon = Icons.Filled.Route,
                                accentColor = null,
                                selected = selectedFilter == FolderFilter.All,
                                onClick = { selectedFilter = FolderFilter.All },
                                modifier = Modifier.testTag("folder_filter_all")
                            )
                        }
                        items(folders, key = { it.id }) { folder ->
                            FolderFilterPill(
                                label = folder.name,
                                count = folderCounts[folder.id] ?: 0,
                                icon = Icons.Filled.Folder,
                                accentColor = Color(folder.colorArgb),
                                selected = (selectedFilter as? FolderFilter.Specific)?.folderId == folder.id,
                                onClick = { selectedFilter = FolderFilter.Specific(folder.id) },
                                modifier = Modifier.testTag("folder_filter_${folder.id}")
                            )
                        }
                    }

                    if (activeFolder != null) {
                        val folderRoutesCount = folderCounts[activeFolder.id] ?: 0
                        val folderDistance = filteredRoutes.sumOf { it.distanceMeters }
                        ExpressiveCard {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(Color(activeFolder.colorArgb).copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Folder,
                                        contentDescription = null,
                                        tint = Color(activeFolder.colorArgb),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = activeFolder.name,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "$folderRoutesCount route${if (folderRoutesCount == 1) "" else "s"} • ${GeoGrid.formatDistance(folderDistance)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(
                                    onClick = { editingFolder = activeFolder },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("edit_folder_${activeFolder.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = "Edit folder",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                IconButton(
                                    onClick = { deletingFolder = activeFolder },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("delete_folder_${activeFolder.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Delete folder",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Route Comparison Bento Card
            if (compareMode) {
                item {
                    val first = routes.firstOrNull { it.id == selectedCompareIds.getOrNull(0) }
                    val second = routes.firstOrNull { it.id == selectedCompareIds.getOrNull(1) }
                    ExpressiveCard(
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Path Speed Comparison",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (first != null && second != null) {
                                val faster = if (first.durationMillis <= second.durationMillis) first else second
                                val slower = if (faster.id == first.id) second else first
                                val savedMs = abs(slower.durationMillis - faster.durationMillis)
                                Text(
                                    text = "${faster.name} is ${GeoGrid.formatDurationExact(savedMs)} faster than ${slower.name}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CompareRouteMiniColumn(faster, isWinner = true, modifier = Modifier.weight(1f))
                                    CompareRouteMiniColumn(slower, isWinner = false, modifier = Modifier.weight(1f))
                                }
                            } else {
                                Text(
                                    text = "Select 2 routes below (${selectedCompareIds.size}/2 selected) to see which path is faster.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (sortedRoutes.isNotEmpty()) {
                item {
                    ExpressiveHorizontalSegmentedSelector(
                        options = RouteSortOrder.entries,
                        selectedOption = sortOrder,
                        onOptionSelected = { sortOrder = it },
                        labelProvider = { it.label },
                        testTagPrefix = "route_sort"
                    )
                }
            }

            if (sortedRoutes.isEmpty()) {
                item {
                    ExpressiveCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (routes.isEmpty()) Icons.Filled.Explore else Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = if (activeFolder != null) {
                                    "No Routes in \"${activeFolder.name}\""
                                } else {
                                    "No Recorded Routes Yet"
                                },
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = if (activeFolder != null) {
                                    "Use the folder move button on any saved route or choose this folder when saving a new walk."
                                } else {
                                    "Tap Start Route on the Explore map to time a walk, reveal fog-of-war sectors, and compare your fastest paths."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // Grouped Expressive List with 4.dp spacing and 16.dp outer / 4.dp inner corners
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        sortedRoutes.forEachIndexed { index, route ->
                            val isSelected = route.id in selectedCompareIds
                            val routeFolder = route.folderId?.let { folderMap[it] }
                            RouteListRow(
                                route = route,
                                folder = routeFolder,
                                showFolderBadge = selectedFilter == FolderFilter.All && routeFolder != null,
                                shape = groupedCardShape(index, sortedRoutes.size),
                                compareMode = compareMode,
                                isSelectedForCompare = isSelected,
                                onClick = {
                                    if (compareMode) {
                                        selectedCompareIds = when {
                                            isSelected -> selectedCompareIds - route.id
                                            selectedCompareIds.size < 2 -> selectedCompareIds + route.id
                                            else -> listOf(selectedCompareIds.last(), route.id)
                                        }
                                    } else {
                                        onRouteClick(route.id)
                                    }
                                },
                                onMoveToFolder = { movingRoute = route },
                                onHighlightOnMap = { onHighlightOnMap(route.id) },
                                onWalkToStart = { onWalkToRouteStart(route.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateFolderDialog) {
        FolderEditorDialog(
            onConfirm = { name, colorArgb ->
                showCreateFolderDialog = false
                onCreateFolder(name, colorArgb) { createdId ->
                    selectedFilter = FolderFilter.Specific(createdId)
                }
            },
            onDismiss = { showCreateFolderDialog = false }
        )
    }

    val folderToEdit = editingFolder
    if (folderToEdit != null) {
        FolderEditorDialog(
            initialFolder = folderToEdit,
            onConfirm = { newName, newColor ->
                onUpdateFolder(folderToEdit, newName, newColor)
                editingFolder = null
            },
            onDismiss = { editingFolder = null }
        )
    }

    val folderToDelete = deletingFolder
    if (folderToDelete != null) {
        DeleteFolderConfirmDialog(
            folder = folderToDelete,
            routeCount = folderCounts[folderToDelete.id] ?: 0,
            onConfirmDelete = {
                onDeleteFolder(folderToDelete.id)
                if ((selectedFilter as? FolderFilter.Specific)?.folderId == folderToDelete.id) {
                    selectedFilter = FolderFilter.All
                }
                deletingFolder = null
            },
            onDismiss = { deletingFolder = null }
        )
    }

    val routeToMove = movingRoute
    if (routeToMove != null) {
        MoveRouteToFolderDialog(
            route = routeToMove,
            folders = folders,
            onSelectFolder = { targetFolderId ->
                onMoveRouteToFolder(routeToMove.id, targetFolderId)
                movingRoute = null
            },
            onCreateAndSelectFolder = { name, colorArgb ->
                onCreateFolder(name, colorArgb) { newFolderId ->
                    onMoveRouteToFolder(routeToMove.id, newFolderId)
                }
                movingRoute = null
            },
            onDismiss = { movingRoute = null }
        )
    }
}

@Composable
private fun FolderFilterPill(
    label: String,
    count: Int,
    icon: ImageVector,
    accentColor: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
    val backdropActive = isBackdropBlurActive()
    val pillShape = RoundedCornerShape(percent = 50)
    val bgColor = when {
        selected -> ExpressiveTheme.colors.selectorSelected
        backdropActive -> Color.Transparent
        else -> ExpressiveTheme.colors.selectorUnselected
    }
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    val iconTint = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        accentColor != null -> accentColor
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (!selected) {
                    Modifier.applyBackdropBlur(
                        shape = pillShape,
                        tintColor = ExpressiveTheme.colors.selectorUnselected
                    )
                } else {
                    Modifier.clip(pillShape)
                }
            )
            .clip(pillShape)
            .clickable(onClick = onClick),
        shape = pillShape,
        color = bgColor,
        shadowElevation = if (blurOn) 0.dp else if (selected) 2.dp else 1.dp,
        border = if (blurOn && !selected) {
            BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
        } else {
            null
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Surface(
                shape = CircleShape,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                } else {
                    ExpressiveTheme.colors.nestedElementSurface
                }
            ) {
                Text(
                    text = "$count",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun BentoStatTile(
    title: String,
    value: String,
    icon: ImageVector,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier
) {
    ExpressiveCard(modifier = modifier, shape = shape) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = TelemetryTextStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompareRouteMiniColumn(
    route: RouteEntity,
    isWinner: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = ExpressiveTheme.colors.nestedElementSurface
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(route.colorArgb))
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = GeoGrid.formatDurationExact(route.durationMillis),
                style = TelemetryTextStyle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                color = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${GeoGrid.formatDistance(route.distanceMeters)} • ${String.format("%.1f km/h", route.avgSpeedKmh)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RouteListRow(
    route: RouteEntity,
    folder: RouteFolderEntity?,
    showFolderBadge: Boolean,
    shape: RoundedCornerShape,
    compareMode: Boolean,
    isSelectedForCompare: Boolean,
    onClick: () -> Unit,
    onMoveToFolder: () -> Unit,
    onHighlightOnMap: () -> Unit,
    onWalkToStart: () -> Unit = {}
) {
    val dateFormatter = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }

    ExpressiveCard(
        shape = shape,
        onClick = onClick,
        border = if (isSelectedForCompare) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (compareMode) {
                Checkbox(
                    checked = isSelectedForCompare,
                    onCheckedChange = { onClick() }
                )
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color(route.colorArgb),
                    modifier = Modifier.size(16.dp)
                ) {}
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = route.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${GeoGrid.formatDurationExact(route.durationMillis)} • ${GeoGrid.formatDistance(route.distanceMeters)}",
                    style = TelemetryTextStyle.copy(fontSize = 13.sp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${String.format("%.1f km/h", route.avgSpeedKmh)} avg • ${route.chunksUnlocked} sectors • ${dateFormatter.format(Date(route.endedAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (showFolderBadge && folder != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    FolderBadgeChip(folder = folder)
                }
            }

            if (!compareMode) {
                IconButton(
                    onClick = onMoveToFolder,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("move_route_folder_${route.id}")
                ) {
                    Icon(
                        imageVector = Icons.Filled.DriveFileMove,
                        contentDescription = "Move route to folder",
                        tint = folder?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onWalkToStart,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("walk_to_route_${route.id}")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                        contentDescription = "Walk to route start",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onHighlightOnMap,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Map,
                        contentDescription = "Show route on map",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun RouteDetailScreen(
    route: RouteEntity?,
    points: List<RoutePointEntity>,
    folders: List<RouteFolderEntity> = emptyList(),
    palette: MapPalette,
    showGridLines: Boolean,
    onBack: () -> Unit,
    onShowOnExploreMap: (Long) -> Unit,
    onWalkToStart: (Long) -> Unit = {},
    onWalkToFinish: (Long) -> Unit = {},
    isSnappingToRoads: Boolean = false,
    onSnapToRoads: (Long) -> Unit = {},
    onMoveRouteToFolder: (routeId: Long, folderId: Long?) -> Unit = { _, _ -> },
    onCreateFolder: (name: String, colorArgb: Long, onCreated: (Long) -> Unit) -> Unit = { _, _, _ -> },
    onDeleteRoute: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val latLonPoints = remember(points) {
        points.map { LatLonPoint(it.latitude, it.longitude, it.speedKmh, it.timestamp) }
    }
    var showMoveFolderDialog by remember { mutableStateOf(false) }
    val currentFolder = remember(route?.folderId, folders) {
        route?.folderId?.let { fid -> folders.firstOrNull { it.id == fid } }
    }

    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    FrostedMenuContainer(
        modifier = modifier,
        blurBehindDialog = showMoveFolderDialog
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 16.dp,
                bottom = 16.dp + navBarBottom
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
                    val backdropActive = isBackdropBlurActive()
                    Surface(
                        modifier = Modifier
                            .size(48.dp)
                            .applyBackdropBlur(
                                shape = CircleShape,
                                tintColor = ExpressiveTheme.colors.cardSurface
                            )
                            .clip(CircleShape)
                            .clickable(onClick = onBack),
                        shape = CircleShape,
                        color = if (backdropActive) Color.Transparent else ExpressiveTheme.colors.cardSurface,
                        shadowElevation = if (blurOn) 0.dp else 2.dp,
                        border = if (blurOn) {
                            BorderStroke(0.9.dp, ExpressiveTheme.colors.frostedBorderColor)
                        } else {
                            null
                        }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to route library"
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = route?.name ?: "Route Details",
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (route != null) {
                item {
                    ExpressiveCard {
                        MiniMapPalettePreview(
                            palette = palette.copy(routeColor = route.colorArgb),
                            showGridLines = showGridLines,
                            routePoints = latLonPoints,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                        )
                    }
                }

                // Folder assignment card
                item {
                    ExpressiveCard(
                        onClick = { showMoveFolderDialog = true },
                        modifier = Modifier.testTag("detail_folder_card")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (currentFolder != null) Icons.Filled.Folder else Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = currentFolder?.let { Color(it.colorArgb) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Folder",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = currentFolder?.name ?: "None (All only)",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(percent = 50),
                                color = ExpressiveTheme.colors.nestedElementSurface
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DriveFileMove,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Move",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                // Bento Telemetry Summary
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        ExpressiveCard(
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 16.dp,
                                bottomStart = 4.dp, bottomEnd = 4.dp
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Exact Recorded Completion Time",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = GeoGrid.formatDurationExact(route.durationMillis),
                                    style = TelemetryTextStyle.copy(fontSize = 26.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BentoStatTile(
                                title = "Distance",
                                value = GeoGrid.formatDistance(route.distanceMeters),
                                icon = Icons.Filled.Directions,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f)
                            )
                            BentoStatTile(
                                title = "Avg Speed",
                                value = String.format("%.1f km/h", route.avgSpeedKmh),
                                icon = Icons.Filled.Speed,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            BentoStatTile(
                                title = "Peak Speed",
                                value = String.format("%.1f km/h", route.maxSpeedKmh),
                                icon = Icons.Filled.Speed,
                                shape = RoundedCornerShape(
                                    topStart = 4.dp, topEnd = 4.dp,
                                    bottomStart = 16.dp, bottomEnd = 4.dp
                                ),
                                modifier = Modifier.weight(1f)
                            )
                            BentoStatTile(
                                title = "Sectors Revealed",
                                value = "${route.chunksUnlocked}",
                                icon = Icons.Filled.Explore,
                                shape = RoundedCornerShape(
                                    topStart = 4.dp, topEnd = 4.dp,
                                    bottomStart = 4.dp, bottomEnd = 16.dp
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ExpressivePillButton(
                            text = "Walk to Start",
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            onClick = { onWalkToStart(route.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_walk_to_start_button")
                        )
                        ExpressivePillButton(
                            text = "Walk to Finish",
                            icon = Icons.Filled.Flag,
                            onClick = { onWalkToFinish(route.id) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("detail_walk_to_finish_button")
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            ExpressivePillButton(
                                text = if (isSnappingToRoads) "Aligning…" else "Align to Roads",
                                icon = Icons.Filled.AutoFixHigh,
                                subtle = true,
                                enabled = !isSnappingToRoads,
                                onClick = { onSnapToRoads(route.id) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("detail_snap_to_roads_button")
                            )
                            ExpressivePillButton(
                                text = "Show on Map",
                                icon = Icons.Filled.Map,
                                subtle = true,
                                onClick = { onShowOnExploreMap(route.id) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        ExpressivePillButton(
                            text = "Delete",
                            icon = Icons.Filled.Delete,
                            onClick = {
                                onDeleteRoute(route.id)
                                onBack()
                            },
                            containerColorOverride = MaterialTheme.colorScheme.errorContainer,
                            contentColorOverride = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    if (showMoveFolderDialog && route != null) {
        MoveRouteToFolderDialog(
            route = route,
            folders = folders,
            onSelectFolder = { folderId ->
                onMoveRouteToFolder(route.id, folderId)
                showMoveFolderDialog = false
            },
            onCreateAndSelectFolder = { name, colorArgb ->
                onCreateFolder(name, colorArgb) { newFolderId ->
                    onMoveRouteToFolder(route.id, newFolderId)
                }
                showMoveFolderDialog = false
            },
            onDismiss = { showMoveFolderDialog = false }
        )
    }
}
