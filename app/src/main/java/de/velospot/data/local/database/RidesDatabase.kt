package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.entity.RecordedRideEntity

/**
 * Dedicated Room database for user-recorded rides (the "My rides" timeline).
 *
 * Kept completely separate from [BikeParkingDatabase] (asset-seeded, destructive
 * migration) and [SavedPlacesDatabase], so schema changes to one store can never
 * wipe the user's ride history.
 */
@Database(
    entities = [RecordedRideEntity::class],
    version = 1,
    exportSchema = false
)
abstract class RidesDatabase : RoomDatabase() {

    abstract fun recordedRideDao(): RecordedRideDao

    companion object {
        @Volatile
        private var instance: RidesDatabase? = null

        fun getInstance(context: Context): RidesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RidesDatabase::class.java,
                    "velospot_rides.db"
                ).build().also { instance = it }
            }
        }
    }
}

