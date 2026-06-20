package de.velospot.data.local.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import de.velospot.data.local.dao.FavoriteSpaceDao
import de.velospot.data.local.entity.FavoriteSpaceEntity
import kotlinx.coroutines.runBlocking
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
         * The legacy rows are read through a **separate, read-only [SQLiteDatabase]
         * connection** and inserted via the Room [FavoriteSpaceDao]. This deliberately
         * avoids touching `db.openHelper.writableDatabase` / `ATTACH DATABASE`: opening
         * the Room database through its low-level support connection bypasses Room's
         * (2.7+) connection-pool initialisation, so the internal
         * `room_table_modification_log` invalidation table is never created — which
         * later crashes the first `Flow` observer with
         * `no such table: room_table_modification_log`. Going through the DAO keeps the
         * database opening exclusively on Room's normal path.
         */
        private fun migrateLegacyFavoritesIfNeeded(context: Context, db: FavoritesDatabase) {
            val prefs = context.getSharedPreferences(MIGRATION_PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_FAVORITES_ISOLATED, false)) return

            val legacyFile: File = context.getDatabasePath(LEGACY_PARKING_DB)
            if (legacyFile.exists()) {
                runCatching {
                    val legacyFavorites = readLegacyFavorites(legacyFile)
                    if (legacyFavorites.isNotEmpty()) {
                        // A blocking insert is fine for this one-time, tiny copy; Room
                        // runs the suspend DAO call on its own executor and, crucially,
                        // initialises the connection pool + invalidation tracker first.
                        runBlocking {
                            val dao = db.favoriteSpaceDao()
                            legacyFavorites.forEach { dao.addFavorite(it) }
                        }
                    }
                }
            }
            prefs.edit().putBoolean(KEY_FAVORITES_ISOLATED, true).apply()
        }

        /**
         * Reads the favourite rows out of the legacy parking database via a
         * standalone read-only connection (no `ATTACH`, no Room involvement).
         * Returns an empty list when the table is missing or empty.
         */
        private fun readLegacyFavorites(legacyFile: File): List<FavoriteSpaceEntity> {
            val favorites = mutableListOf<FavoriteSpaceEntity>()
            SQLiteDatabase.openDatabase(
                legacyFile.path, null, SQLiteDatabase.OPEN_READONLY
            ).use { source ->
                source.rawQuery(
                    "SELECT parkingSpaceId, addedAt, notes FROM favorite_parking_spaces", null
                ).use { c ->
                    val idIdx = c.getColumnIndexOrThrow("parkingSpaceId")
                    val addedAtIdx = c.getColumnIndexOrThrow("addedAt")
                    val notesIdx = c.getColumnIndexOrThrow("notes")
                    while (c.moveToNext()) {
                        favorites += FavoriteSpaceEntity(
                            parkingSpaceId = c.getString(idIdx),
                            addedAt = c.getLong(addedAtIdx),
                            notes = if (c.isNull(notesIdx)) null else c.getString(notesIdx)
                        )
                    }
                }
            }
            return favorites
        }
    }
}

