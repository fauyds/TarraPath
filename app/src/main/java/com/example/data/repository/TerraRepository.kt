package com.example.data.repository

import com.example.data.local.AchievementDao
import com.example.data.local.AchievementUnlockEntity
import com.example.data.local.ChunkDao
import com.example.data.local.ExploredChunkEntity
import com.example.data.local.ExplorerStatsEntity
import com.example.data.local.MapMarkerEntity
import com.example.data.local.MapPalettePresetEntity
import com.example.data.local.MarkerDao
import com.example.data.local.PaletteDao
import com.example.data.local.RouteDao
import com.example.data.local.RouteEntity
import com.example.data.local.RoutePointEntity
import com.example.data.model.AchievementCatalog
import com.example.data.model.AchievementCategory
import com.example.data.model.AchievementDefinition
import com.example.data.model.LatLonPoint
import com.example.data.model.MapPalette
import com.example.data.model.MarkerCategory
import com.example.domain.GeoGrid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TerraRepository(
    private val chunkDao: ChunkDao,
    private val routeDao: RouteDao,
    private val markerDao: MarkerDao,
    private val paletteDao: PaletteDao,
    private val achievementDao: AchievementDao
) {
    val exploredChunks: Flow<List<ExploredChunkEntity>> = chunkDao.observeAllChunks()
    val totalChunkCount: Flow<Int> = chunkDao.observeTotalChunkCount()
    suspend fun getTotalChunkCountOnce(): Int = chunkDao.getTotalChunkCountOnce()
    val allRoutes: Flow<List<RouteEntity>> = routeDao.observeAllRoutes()
    val allMarkers: Flow<List<MapMarkerEntity>> = markerDao.observeAllMarkers()
    val achievementUnlocks: Flow<List<AchievementUnlockEntity>> = achievementDao.observeAllUnlocks()
    val explorerStats: Flow<ExplorerStatsEntity> = achievementDao.observeStats().map {
        it ?: ExplorerStatsEntity()
    }

    val allPalettes: Flow<List<MapPalette>> = paletteDao.observeAllPalettes().map { list ->
        list.map { it.toDomain() }
    }

    fun observeDistrictUnlockedCount(districtKey: String): Flow<Int> =
        chunkDao.observeDistrictUnlockedCount(districtKey)

    fun observeRoute(routeId: Long): Flow<RouteEntity?> = routeDao.observeRouteById(routeId)

    fun observeRoutePoints(routeId: Long): Flow<List<RoutePointEntity>> =
        routeDao.observePointsForRoute(routeId)

    suspend fun getRoutePointsOnce(routeId: Long): List<RoutePointEntity> =
        routeDao.getPointsForRoute(routeId)

    suspend fun ensureDefaultDataSeeded() {
        if (paletteDao.getPaletteCount() == 0) {
            val now = System.currentTimeMillis()
            val builtInPresets = listOf(
                MapPalettePresetEntity(
                    name = "Topographic Sage",
                    isBuiltIn = true,
                    landColor = 0xFFE9EFE6,
                    roadColor = 0xFFFFFFFF,
                    waterColor = 0xFF77B7C7,
                    fogColor = 0xFF1F2B24,
                    gridStrokeColor = 0x664A6B53,
                    routeColor = 0xFF1B6B52,
                    createdAt = now
                ),
                MapPalettePresetEntity(
                    name = "Cyber Pastel",
                    isBuiltIn = true,
                    landColor = 0xFFF0EBF8,
                    roadColor = 0xFFFFFFFF,
                    waterColor = 0xFF7CE8FF,
                    fogColor = 0xFF281C3E,
                    gridStrokeColor = 0x668F75C8,
                    routeColor = 0xFFFF4D8D,
                    createdAt = now + 1
                ),
                MapPalettePresetEntity(
                    name = "Monochrome Ink",
                    isBuiltIn = true,
                    landColor = 0xFFF4F2EC,
                    roadColor = 0xFF2B2B2B,
                    waterColor = 0xFFBAC2C6,
                    fogColor = 0xFF171717,
                    gridStrokeColor = 0x55444444,
                    routeColor = 0xFFC83232,
                    createdAt = now + 2
                ),
                MapPalettePresetEntity(
                    name = "Tactical Dark",
                    isBuiltIn = true,
                    landColor = 0xFF1D242B,
                    roadColor = 0xFFE5A93B,
                    waterColor = 0xFF1A4358,
                    fogColor = 0xFF0C1014,
                    gridStrokeColor = 0x663B7A57,
                    routeColor = 0xFF4ADE80,
                    createdAt = now + 3
                )
            )
            paletteDao.insertPalettes(builtInPresets)
        }
        if (achievementDao.getStatsOnce() == null) {
            achievementDao.upsertStats(ExplorerStatsEntity())
        }
    }

    /**
     * Unlocks the chunk containing (lat, lon) if not already explored.
     * Returns true if a brand-new chunk was revealed.
     */
    suspend fun unlockChunkAt(lat: Double, lon: Double): Boolean {
        val coord = GeoGrid.latLonToChunk(lat, lon)
        val district = GeoGrid.chunkToDistrict(coord.cx, coord.cy)
        val (centerLat, centerLon) = GeoGrid.chunkCenter(coord.cx, coord.cy)
        val rowId = chunkDao.insertChunk(
            ExploredChunkEntity(
                chunkKey = coord.key,
                chunkX = coord.cx,
                chunkY = coord.cy,
                districtKey = district.key,
                centerLat = centerLat,
                centerLon = centerLon,
                unlockedAt = System.currentTimeMillis()
            )
        )
        return rowId != -1L
    }

    /**
     * Unlocks all sectors along the segment (startLat, startLon) -> (endLat, endLon) when
     * [interpolateSegment] is true, or only the destination sector otherwise.
     * Returns the count of newly unlocked sectors.
     */
    suspend fun unlockChunksAlongSegment(
        startLat: Double,
        startLon: Double,
        endLat: Double,
        endLon: Double,
        interpolateSegment: Boolean = true
    ): Int {
        val coords = if (interpolateSegment) {
            GeoGrid.chunksAlongSegment(startLat, startLon, endLat, endLon)
        } else {
            listOf(GeoGrid.latLonToChunk(endLat, endLon))
        }
        val now = System.currentTimeMillis()
        val entities = coords.map { coord ->
            val district = GeoGrid.chunkToDistrict(coord.cx, coord.cy)
            val (centerLat, centerLon) = GeoGrid.chunkCenter(coord.cx, coord.cy)
            ExploredChunkEntity(
                chunkKey = coord.key,
                chunkX = coord.cx,
                chunkY = coord.cy,
                districtKey = district.key,
                centerLat = centerLat,
                centerLon = centerLon,
                unlockedAt = now
            )
        }
        val rowIds = chunkDao.insertChunks(entities)
        return rowIds.count { it != -1L }
    }

    suspend fun recordTelemetryProgress(
        distanceDeltaMeters: Double,
        stepsDelta: Long,
        currentSpeedKmh: Float,
        totalChunksUnlocked: Int
    ): List<AchievementDefinition> {
        val current = achievementDao.getStatsOnce() ?: ExplorerStatsEntity()
        val updated = current.copy(
            totalDistanceMeters = current.totalDistanceMeters + distanceDeltaMeters.coerceAtLeast(0.0),
            totalSteps = current.totalSteps + stepsDelta.coerceAtLeast(0L),
            maxSpeedKmh = maxOf(current.maxSpeedKmh, currentSpeedKmh)
        )
        achievementDao.upsertStats(updated)
        return evaluateAchievements(updated, totalChunksUnlocked)
    }

    suspend fun evaluateAchievements(
        stats: ExplorerStatsEntity,
        totalChunksUnlocked: Int
    ): List<AchievementDefinition> {
        val newlyUnlocked = mutableListOf<AchievementDefinition>()
        val now = System.currentTimeMillis()

        for (def in AchievementCatalog.all) {
            val metric = when (def.category) {
                AchievementCategory.DISTANCE -> stats.totalDistanceMeters
                AchievementCategory.STEPS -> stats.totalSteps.toDouble()
                AchievementCategory.SPEED -> stats.maxSpeedKmh.toDouble()
                AchievementCategory.EXPLORER -> totalChunksUnlocked.toDouble()
            }
            if (metric >= def.targetValue) {
                val inserted = achievementDao.insertUnlock(
                    AchievementUnlockEntity(
                        achievementId = def.id,
                        unlockedAt = now,
                        bestValue = metric
                    )
                )
                if (inserted != -1L) {
                    newlyUnlocked.add(def)
                }
            }
        }
        return newlyUnlocked
    }

    suspend fun saveRecordedRoute(
        name: String,
        colorArgb: Long,
        distanceMeters: Double,
        durationMillis: Long,
        avgSpeedKmh: Float,
        maxSpeedKmh: Float,
        chunksUnlocked: Int,
        startedAt: Long,
        endedAt: Long,
        points: List<LatLonPoint>
    ): Long {
        val routeId = routeDao.insertRoute(
            RouteEntity(
                name = name.trim().ifEmpty { "Explored Path" },
                colorArgb = colorArgb,
                distanceMeters = distanceMeters,
                durationMillis = durationMillis,
                avgSpeedKmh = avgSpeedKmh,
                maxSpeedKmh = maxSpeedKmh,
                chunksUnlocked = chunksUnlocked,
                startedAt = startedAt,
                endedAt = endedAt
            )
        )
        if (points.isNotEmpty()) {
            val entities = points.mapIndexed { index, pt ->
                RoutePointEntity(
                    routeId = routeId,
                    orderIndex = index,
                    latitude = pt.latitude,
                    longitude = pt.longitude,
                    speedKmh = pt.speedKmh,
                    timestamp = pt.timestamp
                )
            }
            routeDao.insertRoutePoints(entities)
        }
        val current = achievementDao.getStatsOnce() ?: ExplorerStatsEntity()
        achievementDao.upsertStats(
            current.copy(totalRecordedMillis = current.totalRecordedMillis + durationMillis)
        )
        return routeId
    }

    suspend fun deleteRoute(routeId: Long) {
        routeDao.deleteRouteById(routeId)
    }

    suspend fun replaceRoutePoints(routeId: Long, points: List<LatLonPoint>): Boolean {
        val existing = routeDao.getRouteByIdOnce(routeId) ?: return false
        if (points.isEmpty()) return false
        routeDao.deletePointsForRoute(routeId)
        val entities = points.mapIndexed { index, pt ->
            RoutePointEntity(
                routeId = routeId,
                orderIndex = index,
                latitude = pt.latitude,
                longitude = pt.longitude,
                speedKmh = pt.speedKmh,
                timestamp = pt.timestamp
            )
        }
        routeDao.insertRoutePoints(entities)

        val newDistance = if (points.size >= 2) {
            com.example.domain.OfflineRoadGraph.polylineDistanceMeters(points)
        } else {
            existing.distanceMeters
        }
        val hours = existing.durationMillis / 3_600_000.0
        val newAvgSpeed = if (hours > 0.0 && newDistance > 0.0) {
            (newDistance / 1000.0 / hours).toFloat()
        } else {
            existing.avgSpeedKmh
        }
        routeDao.updateRoute(
            existing.copy(
                distanceMeters = newDistance,
                avgSpeedKmh = newAvgSpeed
            )
        )
        return true
    }

    suspend fun addMarker(
        title: String,
        note: String,
        category: MarkerCategory,
        latitude: Double,
        longitude: Double,
        colorArgb: Long = category.defaultColorArgb,
        iconName: String = category.defaultIconName
    ): Long {
        return markerDao.insertMarker(
            MapMarkerEntity(
                title = title.trim().ifEmpty { category.label },
                note = note.trim(),
                categoryName = category.name,
                latitude = latitude,
                longitude = longitude,
                colorArgb = colorArgb,
                createdAt = System.currentTimeMillis(),
                iconName = iconName.ifBlank { category.defaultIconName }
            )
        )
    }

    suspend fun updateMarkerIcon(marker: MapMarkerEntity, newIconName: String) {
        markerDao.updateMarker(marker.copy(iconName = newIconName))
    }

    suspend fun updateMarkerColor(marker: MapMarkerEntity, newColorArgb: Long) {
        markerDao.updateMarker(marker.copy(colorArgb = newColorArgb))
    }

    suspend fun deleteMarker(markerId: Long) {
        markerDao.deleteMarkerById(markerId)
    }

    suspend fun saveCustomPalette(palette: MapPalette): Long {
        return paletteDao.insertPalette(
            MapPalettePresetEntity(
                name = palette.name.trim().ifEmpty { "Custom Theme" },
                isBuiltIn = false,
                landColor = palette.landColor,
                roadColor = palette.roadColor,
                waterColor = palette.waterColor,
                fogColor = palette.fogColor,
                gridStrokeColor = palette.gridStrokeColor,
                routeColor = palette.routeColor,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updatePaletteColors(palette: MapPalette) {
        if (palette.id > 0L) {
            paletteDao.updatePalette(
                MapPalettePresetEntity(
                    id = palette.id,
                    name = palette.name,
                    isBuiltIn = palette.isBuiltIn,
                    landColor = palette.landColor,
                    roadColor = palette.roadColor,
                    waterColor = palette.waterColor,
                    fogColor = palette.fogColor,
                    gridStrokeColor = palette.gridStrokeColor,
                    routeColor = palette.routeColor,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun deleteCustomPalette(paletteId: Long) {
        paletteDao.deleteCustomPalette(paletteId)
    }

    private fun MapPalettePresetEntity.toDomain(): MapPalette = MapPalette(
        id = id,
        name = name,
        isBuiltIn = isBuiltIn,
        landColor = landColor,
        roadColor = roadColor,
        waterColor = waterColor,
        fogColor = fogColor,
        gridStrokeColor = gridStrokeColor,
        routeColor = routeColor
    )
}
