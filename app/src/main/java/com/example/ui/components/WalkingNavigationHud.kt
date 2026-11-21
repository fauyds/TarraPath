package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.LocationTelemetry
import com.example.data.model.RoutingMode
import com.example.data.model.WalkingRouteState
import com.example.domain.GeoGrid
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle

@Composable
fun WalkingNavigationHud(
    walkingState: WalkingRouteState,
    activeMode: RoutingMode,
    telemetry: LocationTelemetry,
    onToggleMode: (RoutingMode) -> Unit,
    onRetry: () -> Unit,
    onOpenOfflineAreas: () -> Unit,
    onStop: () -> Unit,
    onDismissArrival: () -> Unit,
    showModeBadge: Boolean = true,
    modifier: Modifier = Modifier
) {
    val target = walkingState.target
    val arrivedLabel = walkingState.arrivedBannerLabel

    if (target == null && arrivedLabel != null) {
        ExpressiveCard(
            modifier = modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .testTag("walking_arrival_banner"),
            shadowElevation = 6.dp,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Arrived",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = arrivedLabel,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismissArrival,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dismiss_arrival_banner_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss arrival notification",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        return
    }

    if (target == null) return

    val walkMinutes = ((walkingState.estimatedWalkSeconds + 30L) / 60L).coerceAtLeast(1L)

    Column(
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExpressiveCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("walking_nav_hud"),
            shadowElevation = 6.dp,
            showBorder = false
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF0284C7)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                            contentDescription = "Walking navigation",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Walk to ${target.label}",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (telemetry.hasFix) {
                                "${GeoGrid.formatDistance(walkingState.remainingDistanceMeters)} remaining • ~$walkMinutes min walk"
                            } else {
                                "Waiting for GPS location…"
                            },
                            style = TelemetryTextStyle.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                            color = Color(0xFF0284C7),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("walking_remaining_distance")
                        )
                        walkingState.providerLabel?.let { provider ->
                            Text(
                                text = provider,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("stop_walking_nav_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Cancel walking route",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (walkingState.isCalculating) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 6.dp)
                            .height(4.dp)
                            .clip(CircleShape),
                        color = Color(0xFF0284C7),
                        trackColor = ExpressiveTheme.colors.nestedElementSurface,
                        strokeCap = StrokeCap.Round
                    )
                }

                walkingState.errorMessage?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 6.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (activeMode == RoutingMode.OFFLINE) {
                                    TextButton(
                                        onClick = onOpenOfflineAreas,
                                        modifier = Modifier.testTag("hud_download_offline_area_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.CloudDownload,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Download Area")
                                    }
                                }
                                TextButton(
                                    onClick = onRetry,
                                    modifier = Modifier.testTag("hud_retry_walking_route_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showModeBadge) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                WalkingModeBadge(
                    activeMode = activeMode,
                    onToggleMode = onToggleMode
                )
            }
        }
    }
}

@Composable
fun WalkingModeBadge(
    activeMode: RoutingMode,
    onToggleMode: (RoutingMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val nextMode = if (activeMode == RoutingMode.ONLINE) RoutingMode.OFFLINE else RoutingMode.ONLINE
    val blurOn = ExpressiveTheme.colors.uiBlurEnabled
    val backdropActive = isBackdropBlurActive()
    val modeBadgeShape = RoundedCornerShape(percent = 50)

    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .applyBackdropBlur(
                shape = modeBadgeShape,
                tintColor = ExpressiveTheme.colors.cardSurface
            )
            .clip(modeBadgeShape)
            .clickable { onToggleMode(nextMode) }
            .testTag("walking_mode_chip"),
        shape = modeBadgeShape,
        color = if (backdropActive) Color.Transparent else ExpressiveTheme.colors.cardSurface,
        shadowElevation = if (blurOn) 0.dp else 4.dp,
        border = if (blurOn) {
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
                imageVector = if (activeMode == RoutingMode.OFFLINE) {
                    Icons.Filled.OfflinePin
                } else {
                    Icons.Filled.Public
                },
                contentDescription = "Switch walking route mode",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (activeMode == RoutingMode.OFFLINE) "Offline" else "Online",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}
