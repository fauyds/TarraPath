package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.TerraPathDatabase
import com.example.data.model.LatLonPoint
import com.example.data.repository.TerraRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteFolderTest {

    private lateinit var context: Context
    private lateinit var db: TerraPathDatabase
    private lateinit var repository: TerraRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, TerraPathDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TerraRepository(
            chunkDao = db.chunkDao(),
            routeDao = db.routeDao(),
            markerDao = db.markerDao(),
            paletteDao = db.paletteDao(),
            achievementDao = db.achievementDao(),
            routeFolderDao = db.routeFolderDao()
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun createUpdateMoveAndDeleteFolderKeepsRoutesInAll() = runBlocking {
        val weekendId = repository.createRouteFolder("Weekend Hikes", 0xFF1B6B52L)
        val commuteId = repository.createRouteFolder("Daily Walks", 0xFF2563EBL)
        assertTrue(weekendId > 0L)
        assertTrue(commuteId > 0L)

        val folders = repository.allRouteFolders.first()
        assertEquals(listOf("Weekend Hikes", "Daily Walks"), folders.map { it.name })

        // Rename and recolor folder
        repository.updateRouteFolder(folders.first(), "Mountain Trails", 0xFFEA580CL)
        val renamed = repository.allRouteFolders.first().first { it.id == weekendId }
        assertEquals("Mountain Trails", renamed.name)
        assertEquals(0xFFEA580CL, renamed.colorArgb)

        // Save route directly inside folder
        val samplePoints = listOf(
            LatLonPoint(37.7749, -122.4194, 4.5f, 1000L),
            LatLonPoint(37.7755, -122.4188, 4.8f, 2000L)
        )
        val routeA = repository.saveRecordedRoute(
            name = "Ridge Loop",
            colorArgb = 0xFF1B6B52L,
            distanceMeters = 1420.0,
            durationMillis = 900_000L,
            avgSpeedKmh = 5.6f,
            maxSpeedKmh = 6.2f,
            chunksUnlocked = 8,
            startedAt = 1000L,
            endedAt = 901_000L,
            points = samplePoints,
            folderId = weekendId
        )

        // Save route without folder then move it into folder
        val routeB = repository.saveRecordedRoute(
            name = "Morning Park",
            colorArgb = 0xFF2563EBL,
            distanceMeters = 800.0,
            durationMillis = 600_000L,
            avgSpeedKmh = 4.8f,
            maxSpeedKmh = 5.4f,
            chunksUnlocked = 4,
            startedAt = 2000L,
            endedAt = 602_000L,
            points = samplePoints
        )

        assertEquals(weekendId, db.routeDao().getRouteByIdOnce(routeA)?.folderId)
        assertNull(db.routeDao().getRouteByIdOnce(routeB)?.folderId)

        repository.moveRouteToFolder(routeB, commuteId)
        assertEquals(commuteId, db.routeDao().getRouteByIdOnce(routeB)?.folderId)

        repository.moveRouteToFolder(routeB, weekendId)
        assertEquals(weekendId, db.routeDao().getRouteByIdOnce(routeB)?.folderId)

        // Deleting folder keeps all saved routes intact in All (folderId = null)
        repository.deleteRouteFolder(weekendId)
        assertEquals(listOf("Daily Walks"), repository.allRouteFolders.first().map { it.name })

        val allAfterDelete = repository.allRoutes.first()
        assertEquals(setOf(routeA, routeB), allAfterDelete.map { it.id }.toSet())
        val updatedA = db.routeDao().getRouteByIdOnce(routeA)
        val updatedB = db.routeDao().getRouteByIdOnce(routeB)
        assertNotNull(updatedA)
        assertNotNull(updatedB)
        assertNull(updatedA?.folderId)
        assertNull(updatedB?.folderId)
        assertEquals(2, repository.getRoutePointsOnce(routeA).size)
    }

    @Test
    fun migration6To7PreservesExistingRoutesAndSupportsFolders() = runBlocking {
        val dbName = "migration_6_7_test.db"
        context.deleteDatabase(dbName)

        // Seed a version 6 schema database
        val helper = object : SQLiteOpenHelper(context, dbName, null, 6) {
            override fun onCreate(sqLiteDb: SQLiteDatabase) {
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS explored_chunks (chunkKey TEXT PRIMARY KEY NOT NULL, chunkX INTEGER NOT NULL, chunkY INTEGER NOT NULL, districtKey TEXT NOT NULL, centerLat REAL NOT NULL, centerLon REAL NOT NULL, unlockedAt INTEGER NOT NULL)")
                sqLiteDb.execSQL("CREATE INDEX IF NOT EXISTS index_explored_chunks_districtKey ON explored_chunks(districtKey)")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS routes (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, colorArgb INTEGER NOT NULL, distanceMeters REAL NOT NULL, durationMillis INTEGER NOT NULL, avgSpeedKmh REAL NOT NULL, maxSpeedKmh REAL NOT NULL, chunksUnlocked INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL)")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS route_points (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, routeId INTEGER NOT NULL, orderIndex INTEGER NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, speedKmh REAL NOT NULL, timestamp INTEGER NOT NULL, FOREIGN KEY(routeId) REFERENCES routes(id) ON DELETE CASCADE)")
                sqLiteDb.execSQL("CREATE INDEX IF NOT EXISTS index_route_points_routeId ON route_points(routeId)")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS map_markers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, note TEXT NOT NULL, categoryName TEXT NOT NULL, latitude REAL NOT NULL, longitude REAL NOT NULL, colorArgb INTEGER NOT NULL, createdAt INTEGER NOT NULL, iconName TEXT NOT NULL DEFAULT '')")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS map_palette_presets (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, isBuiltIn INTEGER NOT NULL, landColor INTEGER NOT NULL, roadColor INTEGER NOT NULL, waterColor INTEGER NOT NULL, fogColor INTEGER NOT NULL, gridStrokeColor INTEGER NOT NULL, routeColor INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS achievement_unlocks (achievementId TEXT PRIMARY KEY NOT NULL, unlockedAt INTEGER NOT NULL, bestValue REAL NOT NULL)")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS explorer_stats (id INTEGER PRIMARY KEY NOT NULL, totalDistanceMeters REAL NOT NULL, totalSteps INTEGER NOT NULL, maxSpeedKmh REAL NOT NULL, totalRecordedMillis INTEGER NOT NULL)")
                sqLiteDb.execSQL("CREATE TABLE IF NOT EXISTS offline_regions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, centerLat REAL NOT NULL, centerLon REAL NOT NULL, radiusMeters INTEGER NOT NULL, southLat REAL NOT NULL, westLon REAL NOT NULL, northLat REAL NOT NULL, eastLon REAL NOT NULL, nodeCount INTEGER NOT NULL, edgeCount INTEGER NOT NULL, tileCount INTEGER NOT NULL, sizeBytes INTEGER NOT NULL, graphFileName TEXT NOT NULL, downloadedAt INTEGER NOT NULL)")
                sqLiteDb.execSQL("INSERT INTO routes (id, name, colorArgb, distanceMeters, durationMillis, avgSpeedKmh, maxSpeedKmh, chunksUnlocked, startedAt, endedAt) VALUES (42, 'Legacy Explorer Path', -14979758, 613.0, 350000, 6.3, 7.1, 6, 1000, 351000)")
            }

            override fun onUpgrade(sqLiteDb: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()

        val migratedDb = Room.databaseBuilder(context, TerraPathDatabase::class.java, dbName)
            .addMigrations(TerraPathDatabase.MIGRATION_6_7)
            .allowMainThreadQueries()
            .build()

        try {
            val existing = migratedDb.routeDao().getRouteByIdOnce(42L)
            assertNotNull(existing)
            assertEquals("Legacy Explorer Path", existing?.name)
            assertNull(existing?.folderId)

            val repo = TerraRepository(
                chunkDao = migratedDb.chunkDao(),
                routeDao = migratedDb.routeDao(),
                markerDao = migratedDb.markerDao(),
                paletteDao = migratedDb.paletteDao(),
                achievementDao = migratedDb.achievementDao(),
                routeFolderDao = migratedDb.routeFolderDao()
            )
            val folderId = repo.createRouteFolder("Favorites", 0xFF7C3AEDL)
            repo.moveRouteToFolder(42L, folderId)

            val updated = migratedDb.routeDao().getRouteByIdOnce(42L)
            assertEquals(folderId, updated?.folderId)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
