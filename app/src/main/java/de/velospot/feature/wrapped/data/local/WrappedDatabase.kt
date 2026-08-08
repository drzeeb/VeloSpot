package de.velospot.feature.wrapped.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Dedicated Room database for generated "VeloSpot Wrapped" reports.
 *
 * Intentionally kept separate from [de.velospot.data.local.database.RidesDatabase]
 * (the ride-history store) so the whole Wrapped feature stays self-contained and
 * can be extracted into a `:feature:wrapped` Gradle module later without coupling
 * to — or risking — the recorded-ride schema. Mirrors the app's established
 * one-store-per-database pattern (`SavedPlacesDatabase`, `DestinationHistoryDatabase`).
 *
 * `exportSchema = true` writes the schema JSON under `app/schemas/` so this store
 * has migration-test coverage from v1 onward.
 */
@Database(
    entities = [WrappedReportEntity::class],
    version = 1,
    exportSchema = true
)
abstract class WrappedDatabase : RoomDatabase() {

    abstract fun wrappedReportDao(): WrappedReportDao

    companion object {
        @Volatile
        private var instance: WrappedDatabase? = null

        fun getInstance(context: Context): WrappedDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WrappedDatabase::class.java,
                    "velospot_wrapped.db"
                ).build().also { instance = it }
            }
        }
    }
}

