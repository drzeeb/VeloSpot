package de.velospot.data.local

import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.data.local.database.BikeParkingDatabase
import de.velospot.data.local.mapper.toDomainModels
import de.velospot.data.local.mapper.toEntities
import de.velospot.domain.model.BikeParkingSpace
import android.content.Context
import javax.inject.Inject

/**
 * Local cache data source using Room SQLite database.
 * Handles all local storage operations for bike parking spaces.
 */
class BikeParkingCacheDataSource @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val database by lazy { BikeParkingDatabase.getInstance(context) }
    private val dao by lazy { database.bikeParkingSpaceDao() }

    /**
     * Retrieve all cached bike parking spaces from the database.
     *
     * @return List of cached parking spaces, or empty list if none exist
     */
    suspend fun readSpaces(): List<BikeParkingSpace> {
        return runCatching {
            dao.getAllSpaces().toDomainModels()
        }.getOrDefault(emptyList())
    }

    /**
     * Write (or update) bike parking spaces to the database.
     * Clears existing data and inserts new entries.
     *
     * @param spaces List of parking spaces to cache
     */
    suspend fun writeSpaces(spaces: List<BikeParkingSpace>) {
        runCatching {
            dao.deleteAllSpaces()
            dao.insertSpaces(spaces.toEntities())
        }
    }

    /**
     * Get the timestamp of the last successful cache update.
     *
     * @return Epoch milliseconds of last update, or 0 if never updated
     */
    suspend fun lastSyncEpochMs(): Long {
        return runCatching {
            dao.getLastUpdateTimestamp() ?: 0L
        }.getOrDefault(0L)
    }
}



