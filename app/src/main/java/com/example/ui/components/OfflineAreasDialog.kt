package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.OfflinePin
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.OfflineRegionEntity
import com.example.data.model.OfflineDownloadProgress
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle
import java.util.Locale

@Composable
fun OfflineAreasDialog(
    centerLat: Double,
    centerLon: Double,
    offlineRegions: List<OfflineRegionEntity>,
    downloadProgress: OfflineDownloadProgress,
    onDownloadArea: (name: String, lat: Double, lon: Double, radiusMeters: Int) -> Unit,
    onDeleteRegion: (OfflineRegionEntity) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    var areaName by remember(offlineRegions.size) {
        mutableStateOf("Area ${offlineRegions.size + 1}")
    }
    var selectedRadiusMeters by remember { mutableIntStateOf(2000) }
    val radiusOptions = listOf(1000, 2000, 3000, 4000)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ExpressiveTheme.colors.dialogSurface,
        shape = RoundedCornerShape(24.dp),
        title = {
            ApplyDialogBlurIfEnabled()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.OfflinePin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Offline Map & Route Areas",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = String.format(Locale.US, "Center: %.4f, %.4f", centerLat, centerLon),
                        style = TelemetryTextStyle.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = areaName,
                    onValueChange = { areaName = it },
                    label = { Text("Area Name") },
                    singleLine = true,
                    enabled = !downloadProgress.isDownloading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("offline_area_name_input")
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Area Radius (streets, footpaths & map tiles)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        radiusOptions.forEach { radius ->
                            val selected = selectedRadiusMeters == radius
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = 48.dp)
                                    .clip(RoundedCornerShape(percent = 50))
                                    .clickable(enabled = !downloadProgress.isDownloading) {
                                        selectedRadiusMeters = radius
                                    }
                                    .testTag("offline_radius_$radius"),
                                shape = RoundedCornerShape(percent = 50),
                                color = if (selected) {
                                    ExpressiveTheme.colors.selectorSelected
                                } else {
                                    ExpressiveTheme.colors.nestedElementSurface
                                }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "${radius / 1000} km",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
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
                }

                ExpressivePillButton(
                    text = if (downloadProgress.isDownloading) "Downloading…" else "Download Area",
                    icon = Icons.Filled.CloudDownload,
                    enabled = !downloadProgress.isDownloading,
                    onClick = {
                        onDownloadArea(
                            areaName.trim().ifEmpty { "Offline Area" },
                            centerLat,
                            centerLon,
                            selectedRadiusMeters
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("download_offline_area_button")
                )

                if (downloadProgress.isDownloading) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ExpressiveTheme.colors.nestedElementSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = downloadProgress.stage.ifBlank { "Preparing offline download…" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            LinearProgressIndicator(
                                progress = { downloadProgress.progressFraction.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = ExpressiveTheme.colors.cardSurface,
                                strokeCap = StrokeCap.Round
                            )
                        }
                    }
                }

                downloadProgress.errorMessage?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = "Download error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onClearError) {
                                Text("Dismiss")
                            }
                        }
                    }
                }

                Text(
                    text = "Saved Offline Areas (${offlineRegions.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (offlineRegions.isEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ExpressiveTheme.colors.nestedElementSurface,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No offline areas downloaded yet. Download an area while connected so CPU walking routes and map tiles work with zero internet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = offlineRegions,
                            key = { _, item -> item.id }
                        ) { index, region ->
                            Surface(
                                shape = groupedCardShape(index, offlineRegions.size),
                                color = ExpressiveTheme.colors.nestedElementSurface,
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = region.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${region.radiusMeters / 1000} km • ${region.edgeCount} paths • ${region.tileCount} tiles • ${formatStorageBytes(region.sizeBytes)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteRegion(region) },
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("delete_offline_region_${region.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "Delete ${region.name}",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            ExpressivePillButton(
                text = "Done",
                onClick = onDismiss,
                subtle = true
            )
        }
    )
}

fun formatStorageBytes(bytes: Long): String {
    return when {
        bytes < 1024L -> "$bytes B"
        bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
