package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.entity.BikeParkingSpaceEntity

/**
 * Room database for storing bike parking space information locally.
 * Provides single-threaded access through a singleton pattern.
 */
@Database(
    entities = [BikeParkingSpaceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BikeParkingDatabase : RoomDatabase() {

    /**
     * Returns the DAO for bike parking space database operations.
     */
    abstract fun bikeParkingSpaceDao(): BikeParkingSpaceDao

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
                "velospot_database.db"
            )
                .build()
        }
    }
}

