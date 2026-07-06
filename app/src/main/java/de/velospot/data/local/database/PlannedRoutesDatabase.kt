package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.PlannedRouteDao
import de.velospot.data.local.dao.RouteAttemptDao
import de.velospot.data.local.entity.PlannedRouteEntity
import de.velospot.data.local.entity.RouteAttemptEntity

/**
 * Dedicated Room database for user-planned multi-waypoint routes and their
 * leaderboard attempts.
 *
 * Kept completely separate from the asset-seeded [BikeParkingDatabase] and the
 * other user stores, so schema changes to one can never wipe the user's planned
 * routes or their recorded best times.
 */
@Database(
    entities = [PlannedRouteEntity::class, RouteAttemptEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PlannedRoutesDatabase : RoomDatabase() {

    abstract fun plannedRouteDao(): PlannedRouteDao
    abstract fun routeAttemptDao(): RouteAttemptDao

    companion object {
        @Volatile
        private var instance: PlannedRoutesDatabase? = null

        fun getInstance(context: Context): PlannedRoutesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PlannedRoutesDatabase::class.java,
                    "velospot_planned_routes.db"
                ).build().also { instance = it }
            }
        }
    }
}

