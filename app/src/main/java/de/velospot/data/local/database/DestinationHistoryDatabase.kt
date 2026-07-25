package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.RecentDestinationDao
import de.velospot.data.local.entity.RecentDestinationEntity

/**
 * Dedicated Room database for recently-navigated destinations and the pinned
 * Home / Work shortcuts.
 *
 * Kept completely separate from [BikeParkingDatabase] (asset-seeded, destructive
 * migration) and the other user stores, so schema changes to one can never wipe
 * the rider's destination history.
 */
@Database(
    entities = [RecentDestinationEntity::class],
    version = 1,
    exportSchema = false
)
abstract class DestinationHistoryDatabase : RoomDatabase() {

    abstract fun recentDestinationDao(): RecentDestinationDao

    companion object {
        @Volatile
        private var instance: DestinationHistoryDatabase? = null

        fun getInstance(context: Context): DestinationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DestinationHistoryDatabase::class.java,
                    "velospot_destination_history.db"
                ).build().also { instance = it }
            }
        }
    }
}

