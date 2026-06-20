package de.velospot.data.local.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.FavoriteSpaceDao
import de.velospot.data.local.entity.FavoriteSpaceEntity
import java.io.File

/**
 * Dedicated Room database for the user's favourite bike parking spaces.
 *
 * Favourites used to live inside [BikeParkingDatabase], which is seeded from a
 * bundled OSM asset and configured with `fallbackToDestructiveMigration` — so any
 * future parking-schema bump without an explicit migration would silently drop the
 * favourites table along with the parking data. Isolating favourites in their own
 * database (mirroring [SavedPlacesDatabase] / [RidesDatabase]) means a parking-data
 * schema change can never wipe them.
 *
 * On first creation the existing favourites are copied over from the legacy parking
 * database exactly once (see [migrateLegacyFavoritesIfNeeded]).
 */
@Database(
    entities = [FavoriteSpaceEntity::class],
    version = 1,
    exportSchema = false
)
abstract class FavoritesDatabase : RoomDatabase() {

    abstract fun favoriteSpaceDao(): FavoriteSpaceDao

    companion object {
        /** Filename of the legacy, asset-seeded parking database holding the old favourites. */
        private const val LEGACY_PARKING_DB = "velospot_osm.db"
        private const val MIGRATION_PREFS = "velospot_migrations"
        private const val KEY_FAVORITES_ISOLATED = "favorites_isolated_v1"

        @Volatile
        private var instance: FavoritesDatabase? = null

        fun getInstance(context: Context): FavoritesDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }
        }

        private fun build(context: Context): FavoritesDatabase {
            val appContext = context.applicationContext
            val db = Room.databaseBuilder(
                appContext,
                FavoritesDatabase::class.java,
                "velospot_favorites.db"
            ).build()
            migrateLegacyFavoritesIfNeeded(appContext, db)
            return db
        }

        /**
         * One-time copy of any favourites that were stored in the legacy parking
         * database into this isolated database. Guarded by a SharedPreferences flag
         * so it runs at most once. Failures (e.g. the legacy table never existed on
         * a clean install) are ignored — there is simply nothing to migrate.
         *
         * Uses `ATTACH DATABASE` outside any transaction (legal here, unlike inside
         * a Room migration callback) followed by an `INSERT OR IGNORE … SELECT`.
         */
        private fun migrateLegacyFavoritesIfNeeded(context: Context, db: FavoritesDatabase) {
            val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_FAVORITES_ISOLATED, false)) return

            val legacyFile: File = context.getDatabasePath(LEGACY_PARKING_DB)
            if (legacyFile.exists()) {
                runCatching {
                    val writable = db.openHelper.writableDatabase
                    // Triggers onCreate so favorite_parking_spaces exists before the copy.
                    writable.execSQL("ATTACH DATABASE '${legacyFile.absolutePath}' AS legacy")
                    try {
                        writable.execSQL(
                            "INSERT OR IGNORE INTO favorite_parking_spaces (parkingSpaceId, addedAt, notes) " +
                                "SELECT parkingSpaceId, addedAt, notes FROM legacy.favorite_parking_spaces"
                        )
                    } finally {
                        writable.execSQL("DETACH DATABASE legacy")
                    }
                }
            }
            prefs.edit().putBoolean(KEY_FAVORITES_ISOLATED, true).apply()
        }
    }
}

