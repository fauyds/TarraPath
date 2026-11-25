package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ExploredChunkEntity::class,
        RouteEntity::class,
        RoutePointEntity::class,
        RouteFolderEntity::class,
        MapMarkerEntity::class,
        MapPalettePresetEntity::class,
        AchievementUnlockEntity::class,
        ExplorerStatsEntity::class,
        OfflineRegionEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class TerraPathDatabase : RoomDatabase() {
    abstract fun chunkDao(): ChunkDao
    abstract fun routeDao(): RouteDao
    abstract fun routeFolderDao(): RouteFolderDao
    abstract fun markerDao(): MarkerDao
    abstract fun paletteDao(): PaletteDao
    abstract fun achievementDao(): AchievementDao
    abstract fun offlineRegionDao(): OfflineRegionDao

    companion object {
        @Volatile
        private var INSTANCE: TerraPathDatabase? = null

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM explored_chunks")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE map_markers ADD COLUMN iconName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS offline_regions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        centerLat REAL NOT NULL,
                        centerLon REAL NOT NULL,
                        radiusMeters INTEGER NOT NULL,
                        southLat REAL NOT NULL,
                        westLon REAL NOT NULL,
                        northLat REAL NOT NULL,
                        eastLon REAL NOT NULL,
                        nodeCount INTEGER NOT NULL,
                        edgeCount INTEGER NOT NULL,
                        tileCount INTEGER NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        graphFileName TEXT NOT NULL,
                        downloadedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS route_folders (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "ALTER TABLE routes ADD COLUMN folderId INTEGER DEFAULT NULL REFERENCES route_folders(id) ON DELETE SET NULL"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_routes_folderId ON routes(folderId)"
                )
            }
        }

        fun getInstance(context: Context): TerraPathDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TerraPathDatabase::class.java,
                    "terrapath_db"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                    .fallbackToDestructiveMigration(true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
