package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.dao.FavoriteParkingSpaceDao
import de.velospot.data.local.entity.BikeParkingSpaceEntity
import de.velospot.data.local.entity.FavoriteParkingSpaceEntity

/**
 * Room database for storing bike parking space information locally.
 * Provides single-threaded access through a singleton pattern.
 */
@Database(
    entities = [BikeParkingSpaceEntity::class, FavoriteParkingSpaceEntity::class],
    version = 3,
    exportSchema = true
)
abstract class BikeParkingDatabase : RoomDatabase() {

    /**
     * Returns the DAO for bike parking space database operations.
     */
    abstract fun bikeParkingSpaceDao(): BikeParkingSpaceDao

    /**
     * Returns the DAO for favorite parking space operations.
     */
    abstract fun favoriteParkingSpaceDao(): FavoriteParkingSpaceDao

    companion object {
        @Volatile
        private var instance: BikeParkingDatabase? = null

        /**
         * Get singleton instance of the database.
         * Thread-safe initialization using double-checked locking.
         *
         * @param context Application context
         * @return Singleton instance of BikeParkingDatabase
         */
        fun getInstance(context: Context): BikeParkingDatabase {
            return instance ?: synchronized(this) {
                instance ?: createDatabase(context).also { instance = it }
            }
        }

        /**
         * Create the Room database instance with proper configuration.
         *
         * @param context Application context
         * @return New BikeParkingDatabase instance
         */
        private fun createDatabase(context: Context): BikeParkingDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                BikeParkingDatabase::class.java,
                // New name intentionally differs from the legacy "velospot_database.db" used
                // by the Trier-WFS era (up to v1.0.5).  Using a fresh name guarantees that
                // every device — whether a clean install or an upgrade — gets a new database
                // file which Room will seed from the OSM asset on first open.
                // The old file is simply ignored; old favorites (which referenced Trier WFS IDs
                // that no longer exist) are not migrated.
                "velospot_osm.db"
            )
                .createFromAsset("bike_parking_germany.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
        }
    }
}

