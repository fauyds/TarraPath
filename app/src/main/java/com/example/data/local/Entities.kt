package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "explored_chunks",
    indices = [Index(value = ["districtKey"])]
)
data class ExploredChunkEntity(
    @PrimaryKey val chunkKey: String,
    val chunkX: Int,
    val chunkY: Int,
    val districtKey: String,
    val centerLat: Double,
    val centerLon: Double,
    val unlockedAt: Long
)

@Entity(tableName = "routes")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val colorArgb: Long,
    val distanceMeters: Double,
    val durationMillis: Long,
    val avgSpeedKmh: Float,
    val maxSpeedKmh: Float,
    val chunksUnlocked: Int,
    val startedAt: Long,
    val endedAt: Long
)

@Entity(
    tableName = "route_points",
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["routeId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["routeId"])]
)
data class RoutePointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val routeId: Long,
    val orderIndex: Int,
    val latitude: Double,
    val longitude: Double,
    val speedKmh: Float,
    val timestamp: Long
)

@Entity(tableName = "map_markers")
data class MapMarkerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val note: String,
    val categoryName: String,
    val latitude: Double,
    val longitude: Double,
    val colorArgb: Long,
    val createdAt: Long,
    val iconName: String = ""
)

@Entity(tableName = "map_palette_presets")
data class MapPalettePresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val isBuiltIn: Boolean,
    val landColor: Long,
    val roadColor: Long,
    val waterColor: Long,
    val fogColor: Long,
    val gridStrokeColor: Long,
    val routeColor: Long,
    val createdAt: Long
)

@Entity(tableName = "achievement_unlocks")
data class AchievementUnlockEntity(
    @PrimaryKey val achievementId: String,
    val unlockedAt: Long,
    val bestValue: Double
)

@Entity(tableName = "explorer_stats")
data class ExplorerStatsEntity(
    @PrimaryKey val id: Int = 1,
    val totalDistanceMeters: Double = 0.0,
    val totalSteps: Long = 0L,
    val maxSpeedKmh: Float = 0f,
    val totalRecordedMillis: Long = 0L
)
