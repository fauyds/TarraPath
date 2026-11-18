package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.local.ExplorerStatsEntity
import com.example.data.model.AchievementCategory
import com.example.data.model.AchievementUiItem
import com.example.domain.GeoGrid
import com.example.ui.components.AmbientUiBlurBackdrop
import com.example.ui.components.ExpressiveCard
import com.example.ui.components.ExpressiveHorizontalSegmentedSelector
import com.example.ui.components.groupedCardShape
import com.example.ui.theme.ExpressiveTheme
import com.example.ui.theme.TelemetryTextStyle
import kotlin.math.roundToInt

enum class AchievementFilter(val label: String) {
    ALL("All"),
    DISTANCE("Distance"),
    STEPS("Steps"),
    SPEED("Speed"),
    SECTORS("Sectors")
}

@Composable
fun AchievementsScreen(
    stats: ExplorerStatsEntity,
    totalChunksUnlocked: Int,
    achievements: List<AchievementUiItem>,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf(AchievementFilter.ALL) }

    val filteredAchievements = remember(achievements, selectedFilter) {
        when (selectedFilter) {
            AchievementFilter.ALL -> achievements
            AchievementFilter.DISTANCE -> achievements.filter { it.definition.category == AchievementCategory.DISTANCE }
            AchievementFilter.STEPS -> achievements.filter { it.definition.category == AchievementCategory.STEPS }
            AchievementFilter.SPEED -> achievements.filter { it.definition.category == AchievementCategory.SPEED }
            AchievementFilter.SECTORS -> achievements.filter { it.definition.category == AchievementCategory.EXPLORER }
        }
    }

    val unlockedCount = remember(achievements) { achievements.count { it.isUnlocked } }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ExpressiveTheme.colors.appBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        AmbientUiBlurBackdrop(visible = ExpressiveTheme.colors.uiBlurEnabled)
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Explorer Trophies",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // Hero Explorer Card
            item {
                ExpressiveCard(shadowElevation = 4.dp) {
                    Column {
                        Image(
                            painter = painterResource(id = R.drawable.img_explorer_banner),
                            contentDescription = "TerraPath exploration horizon illustration",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(132.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = explorerRankTitle(totalChunksUnlocked),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "$unlockedCount of ${achievements.size} milestones unlocked",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(percent = 50),
                                color = ExpressiveTheme.colors.buttonPrimary
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.EmojiEvents,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "$unlockedCount/${achievements.size}",
                                        style = TelemetryTextStyle.copy(fontSize = 14.sp),
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2x2 Bento Grid Telemetry Stats
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AchievementBentoCard(
                            label = "Distance",
                            value = GeoGrid.formatDistance(stats.totalDistanceMeters),
                            icon = Icons.Filled.Route,
                            shape = RoundedCornerShape(
                                topStart = 16.dp, topEnd = 4.dp,
                                bottomStart = 4.dp, bottomEnd = 4.dp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        AchievementBentoCard(
                            label = "Step Counter",
                            value = "${stats.totalSteps}",
                            icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 16.dp,
                                bottomStart = 4.dp, bottomEnd = 4.dp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AchievementBentoCard(
                            label = "Peak Speed",
                            value = String.format("%.1f km/h", stats.maxSpeedKmh),
                            icon = Icons.Filled.Speed,
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 4.dp,
                                bottomStart = 16.dp, bottomEnd = 4.dp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        AchievementBentoCard(
                            label = "Sectors Cleared",
                            value = "$totalChunksUnlocked",
                            icon = Icons.Filled.GridView,
                            shape = RoundedCornerShape(
                                topStart = 4.dp, topEnd = 4.dp,
                                bottomStart = 4.dp, bottomEnd = 16.dp
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Category Filter Selector
            item {
                ExpressiveHorizontalSegmentedSelector(
                    options = AchievementFilter.entries,
                    selectedOption = selectedFilter,
                    onOptionSelected = { selectedFilter = it },
                    labelProvider = { it.label },
                    testTagPrefix = "achievement_filter"
                )
            }

            // Grouped Achievement Cards
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    filteredAchievements.forEachIndexed { index, item ->
                        AchievementRowCard(
                            item = item,
                            shape = groupedCardShape(index, filteredAchievements.size)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AchievementBentoCard(
    label: String,
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
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = value,
                style = TelemetryTextStyle.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun AchievementRowCard(
    item: AchievementUiItem,
    shape: RoundedCornerShape
) {
    val def = item.definition
    val currentDisplay = when (def.category) {
        AchievementCategory.SPEED -> String.format("%.1f", item.currentValue)
        else -> "${item.currentValue.roundToInt()}"
    }
    val targetDisplay = when (def.category) {
        AchievementCategory.SPEED -> String.format("%.0f", def.targetValue)
        else -> "${def.targetValue.roundToInt()}"
    }

    ExpressiveCard(shape = shape) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (item.isUnlocked) {
                    ExpressiveTheme.colors.buttonPrimary
                } else {
                    ExpressiveTheme.colors.nestedElementSurface
                },
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = def.icon,
                        contentDescription = def.title,
                        tint = if (item.isUnlocked) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = def.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (item.isUnlocked) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = "Unlocked",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Unlocked",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            text = "$currentDisplay / $targetDisplay ${def.unit}",
                            style = TelemetryTextStyle.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = def.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = if (item.isUnlocked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    trackColor = ExpressiveTheme.colors.nestedElementSurface,
                    strokeCap = StrokeCap.Round
                )
            }
        }
    }
}

private fun explorerRankTitle(chunks: Int): String {
    return when {
        chunks >= 100 -> "Master Cartographer"
        chunks >= 50 -> "District Pathfinder"
        chunks >= 10 -> "Urban Wayfinder"
        chunks >= 1 -> "Field Scout"
        else -> "Novice Explorer"
    }
}
