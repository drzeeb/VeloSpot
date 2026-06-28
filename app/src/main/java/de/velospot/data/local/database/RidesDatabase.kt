package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
    exportSchema = false
)
abstract class RidesDatabase : RoomDatabase() {

    abstract fun recordedRideDao(): RecordedRideDao

    companion object {
        @Volatile
        private var instance: RidesDatabase? = null

        /** v1 → v2: adds the nullable `name` column for user-named rides. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recorded_rides ADD COLUMN name TEXT")
            }
        }

        /**
         * v2 → v3: flags mock-recorded rides (`isMock`) so they can be marked in the
         * timeline and excluded from statistics, and adds `archivedAt` so rides can be
         * archived (hidden) without deleting them.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recorded_rides ADD COLUMN isMock INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE recorded_rides ADD COLUMN archivedAt INTEGER")
            }
        }

        fun getInstance(context: Context): RidesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RidesDatabase::class.java,
                    "velospot_rides.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
        }
    }
}

