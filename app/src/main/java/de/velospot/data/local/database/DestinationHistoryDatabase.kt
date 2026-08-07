package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 2,
    exportSchema = true
)
abstract class DestinationHistoryDatabase : RoomDatabase() {

    abstract fun recentDestinationDao(): RecentDestinationDao

    companion object {
        @Volatile
        private var instance: DestinationHistoryDatabase? = null

        /**
         * v1 → v2: indexes `kind` so filtering by destination kind
         * (`RECENT` / `HOME` / `WORK`) avoids a full-table scan.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_recent_destinations_kind " +
                        "ON recent_destinations (kind)"
                )
            }
        }

        fun getInstance(context: Context): DestinationHistoryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    DestinationHistoryDatabase::class.java,
                    "velospot_destination_history.db"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}

