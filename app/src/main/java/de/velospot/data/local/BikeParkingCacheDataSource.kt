package de.velospot.data.local

import de.velospot.data.local.dao.BikeParkingSpaceDao
import de.velospot.data.local.mapper.toDomainModels
import de.velospot.data.local.mapper.toEntities
import de.velospot.domain.model.BikeParkingSpace
import de.velospot.domain.model.BoundingBox
import javax.inject.Inject

/**
 * Local cache data source using Room SQLite database.
 * Handles all local storage operations for bike parking spaces.
 */
class BikeParkingCacheDataSource @Inject constructor(
    private val dao: BikeParkingSpaceDao
) : BikeParkingLocalDataSource {

    /**
     * Retrieve all cached bike parking spaces from the database.
     *
     * @return List of cached parking spaces, or empty list if none exist
     */
    override suspend fun readSpaces(): List<BikeParkingSpace> {
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
    override suspend fun writeSpaces(spaces: List<BikeParkingSpace>) {
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
    override suspend fun lastSyncEpochMs(): Long {
        return runCatching {
            dao.getLastUpdateTimestamp() ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * Retrieve cached bike parking spaces within a specific bounding box.
     *
     * @param bbox The bounding box defining the area of interest
     * @return List of cached parking spaces within the bounding box, or empty list if none exist
     */
    override suspend fun readSpacesInBoundingBox(bbox: BoundingBox): List<BikeParkingSpace> {
        return runCatching {
            dao.getSpacesInBoundingBox(
                minLat = bbox.minLat,
                maxLat = bbox.maxLat,
                minLon = bbox.minLon,
                maxLon = bbox.maxLon
            ).toDomainModels()
        }.getOrDefault(emptyList())
    }

    /**
     * Retrieve cached bike parking spaces by their unique IDs.
     *
     * @param ids List of unique IDs of the parking spaces to retrieve
     * @return List of cached parking spaces with the given IDs, or empty list if none exist
     */
    override suspend fun readSpacesByIds(ids: List<String>): List<BikeParkingSpace> {
        if (ids.isEmpty()) return emptyList()
        return runCatching {
            dao.getSpacesByIds(ids).toDomainModels()
        }.getOrDefault(emptyList())
    }

    /**
     * Persist a reverse-geocoded address for a single parking space.
     * Silently ignores failures (e.g., space was removed from DB).
     *
     * @param id    The unique ID of the parking space
     * @param address The resolved address string
     */
    override suspend fun updateAddress(id: String, address: String) {
        runCatching { dao.updateAddress(id, address) }
    }
}
