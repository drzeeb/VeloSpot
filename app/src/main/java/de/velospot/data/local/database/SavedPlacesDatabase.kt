package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.SavedPlaceDao
import de.velospot.data.local.entity.SavedPlaceEntity

/**
 * Dedicated Room database for user-saved custom places.
 *
 * Kept completely separate from [BikeParkingDatabase] (which is seeded from a
 * bundled OSM asset and uses destructive migration). Isolating user-generated
 * data here means schema changes to the parking database can never wipe a user's
 * saved places, and vice versa.
 */
@Database(
    entities = [SavedPlaceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class SavedPlacesDatabase : RoomDatabase() {

    abstract fun savedPlaceDao(): SavedPlaceDao

    companion object {
        @Volatile
        private var instance: SavedPlacesDatabase? = null

        fun getInstance(context: Context): SavedPlacesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SavedPlacesDatabase::class.java,
                    "velospot_saved_places.db"
                ).build().also { instance = it }
            }
        }
    }
}

