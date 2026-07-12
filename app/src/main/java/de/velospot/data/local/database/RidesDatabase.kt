package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.BikeProfileDao
import de.velospot.data.local.entity.RecordedRideEntity
import de.velospot.data.local.entity.BikeProfileEntity

/**
 * Dedicated Room database for user-recorded rides (the "My rides" timeline).
 *
 * Kept completely separate from [BikeParkingDatabase] (asset-seeded, destructive
 * migration) and [SavedPlacesDatabase], so schema changes to one store can never
 * wipe the user's ride history.
 */
@Database(
    entities = [RecordedRideEntity::class, BikeProfileEntity::class],
    version = 6,
    exportSchema = false
)
abstract class RidesDatabase : RoomDatabase() {

    abstract fun recordedRideDao(): RecordedRideDao

    abstract fun bikeProfileDao(): BikeProfileDao

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

        /**
         * v3 → v4: indexes `startedAt` (the timeline's newest-first ordering) and
         * `archivedAt` (the active/archived split) so the history list scales to
         * large ride counts without full-table sorts.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_recorded_rides_started_at ON recorded_rides (startedAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_recorded_rides_archived_at ON recorded_rides (archivedAt)")
            }
        }

        /**
         * v4 → v5: adds the bike garage. Creates the `bike_profiles` table and tags
         * rides with the bike they were recorded with (`bikeProfileId`) so statistics
         * can be split per bike. Existing rides start untagged (`NULL`).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS bike_profiles (" +
                        "id TEXT NOT NULL PRIMARY KEY, " +
                        "name TEXT NOT NULL, " +
                        "brand TEXT, " +
                        "model TEXT, " +
                        "type TEXT NOT NULL, " +
                        "tireSize TEXT, " +
                        "weightKg REAL, " +
                        "color TEXT, " +
                        "modelYear INTEGER, " +
                        "notes TEXT, " +
                        "isDefault INTEGER NOT NULL DEFAULT 0, " +
                        "createdAt INTEGER NOT NULL)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bike_profiles_created_at ON bike_profiles (createdAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_bike_profiles_is_default ON bike_profiles (isDefault)")
                db.execSQL("ALTER TABLE recorded_rides ADD COLUMN bikeProfileId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS idx_recorded_rides_bike_profile_id ON recorded_rides (bikeProfileId)")
            }
        }

        /**
         * v5 → v6: adds shop-service reminders to the bike garage. `serviceIntervalKm`
         * is the km between services (NULL/0 = off) and `lastServiceNotifiedKm` tracks
         * the highest milestone already notified so each one fires exactly once.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bike_profiles ADD COLUMN serviceIntervalKm INTEGER")
                db.execSQL("ALTER TABLE bike_profiles ADD COLUMN lastServiceNotifiedKm INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): RidesDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RidesDatabase::class.java,
                    "velospot_rides.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6).build().also { instance = it }
            }
        }
    }
}

