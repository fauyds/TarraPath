package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Query("SELECT * FROM explored_chunks ORDER BY unlockedAt DESC")
    fun observeAllChunks(): Flow<List<ExploredChunkEntity>>

    @Query("SELECT COUNT(*) FROM explored_chunks")
    fun observeTotalChunkCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM explored_chunks")
    suspend fun getTotalChunkCountOnce(): Int

    @Query("SELECT COUNT(*) FROM explored_chunks WHERE districtKey = :districtKey")
    fun observeDistrictUnlockedCount(districtKey: String): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunk(chunk: ExploredChunkEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunks(chunks: List<ExploredChunkEntity>): List<Long>

    @Query("SELECT EXISTS(SELECT 1 FROM explored_chunks WHERE chunkKey = :chunkKey)")
    suspend fun isChunkExplored(chunkKey: String): Boolean
}

@Dao
interface RouteDao {
    @Query("SELECT * FROM routes ORDER BY endedAt DESC")
    fun observeAllRoutes(): Flow<List<RouteEntity>>

    @Query("SELECT * FROM routes WHERE id = :routeId")
    fun observeRouteById(routeId: Long): Flow<RouteEntity?>

    @Query("SELECT * FROM routes WHERE id = :routeId")
    suspend fun getRouteByIdOnce(routeId: Long): RouteEntity?

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY orderIndex ASC")
    fun observePointsForRoute(routeId: Long): Flow<List<RoutePointEntity>>

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY orderIndex ASC")
    suspend fun getPointsForRoute(routeId: Long): List<RoutePointEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoute(route: RouteEntity): Long

    @Update
    suspend fun updateRoute(route: RouteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutePoints(points: List<RoutePointEntity>)

    @Query("DELETE FROM route_points WHERE routeId = :routeId")
    suspend fun deletePointsForRoute(routeId: Long)

    @Query("UPDATE routes SET folderId = :folderId WHERE id = :routeId")
    suspend fun updateRouteFolder(routeId: Long, folderId: Long?)

    @Query("UPDATE routes SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolderFromRoutes(folderId: Long)

    @Query("DELETE FROM routes WHERE id = :routeId")
    suspend fun deleteRouteById(routeId: Long)
}

@Dao
interface RouteFolderDao {
    @Query("SELECT * FROM route_folders ORDER BY createdAt ASC")
    fun observeAllFolders(): Flow<List<RouteFolderEntity>>

    @Query("SELECT * FROM route_folders ORDER BY createdAt ASC")
    suspend fun getAllFoldersOnce(): List<RouteFolderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFolder(folder: RouteFolderEntity): Long

    @Update
    suspend fun updateFolder(folder: RouteFolderEntity)

    @Query("DELETE FROM route_folders WHERE id = :folderId")
    suspend fun deleteFolderById(folderId: Long)
}

@Dao
interface MarkerDao {
    @Query("SELECT * FROM map_markers ORDER BY createdAt DESC")
    fun observeAllMarkers(): Flow<List<MapMarkerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMarker(marker: MapMarkerEntity): Long

    @Update
    suspend fun updateMarker(marker: MapMarkerEntity)

    @Query("DELETE FROM map_markers WHERE id = :markerId")
    suspend fun deleteMarkerById(markerId: Long)
}

@Dao
interface PaletteDao {
    @Query("SELECT * FROM map_palette_presets ORDER BY isBuiltIn DESC, createdAt ASC")
    fun observeAllPalettes(): Flow<List<MapPalettePresetEntity>>

    @Query("SELECT COUNT(*) FROM map_palette_presets")
    suspend fun getPaletteCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPalette(palette: MapPalettePresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPalettes(palettes: List<MapPalettePresetEntity>)

    @Update
    suspend fun updatePalette(palette: MapPalettePresetEntity)

    @Query("DELETE FROM map_palette_presets WHERE id = :paletteId AND isBuiltIn = 0")
    suspend fun deleteCustomPalette(paletteId: Long)
}

@Dao
interface AchievementDao {
    @Query("SELECT * FROM achievement_unlocks")
    fun observeAllUnlocks(): Flow<List<AchievementUnlockEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertUnlock(unlock: AchievementUnlockEntity): Long

    @Query("SELECT * FROM explorer_stats WHERE id = 1")
    fun observeStats(): Flow<ExplorerStatsEntity?>

    @Query("SELECT * FROM explorer_stats WHERE id = 1")
    suspend fun getStatsOnce(): ExplorerStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStats(stats: ExplorerStatsEntity)
}

@Dao
interface OfflineRegionDao {
    @Query("SELECT * FROM offline_regions ORDER BY downloadedAt DESC")
    fun observeAllRegions(): Flow<List<OfflineRegionEntity>>

    @Query("SELECT * FROM offline_regions ORDER BY downloadedAt DESC")
    suspend fun getAllRegionsOnce(): List<OfflineRegionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegion(region: OfflineRegionEntity): Long

    @Query("DELETE FROM offline_regions WHERE id = :regionId")
    suspend fun deleteRegionById(regionId: Long)
}
