package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.BikeParkingSpaceEntity

/**
 * Data Access Object for bike parking spaces.
 * Defines database query methods and operations.
 */
@Dao
interface BikeParkingSpaceDao {

    /**
     * Retrieve all bike parking spaces from the database.
     *
     * @return List of all parking spaces stored locally
     */
    @Query("SELECT * FROM bike_parking_spaces ORDER BY name ASC")
    suspend fun getAllSpaces(): List<BikeParkingSpaceEntity>

    /**
     * Insert a single parking space. If it already exists, replace it.
     *
     * @param space The parking space entity to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpace(space: BikeParkingSpaceEntity)

    /**
     * Insert multiple parking spaces. Existing entries are replaced.
     *
     * @param spaces List of parking space entities to insert
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpaces(spaces: List<BikeParkingSpaceEntity>)

    /**
     * Delete all parking spaces from the database.
     * Useful when refreshing data from remote API.
     */
    @Query("DELETE FROM bike_parking_spaces")
    suspend fun deleteAllSpaces()

    /**
     * Delete a specific parking space.
     *
     * @param space The parking space entity to delete
     */
    @Delete
    suspend fun deleteSpace(space: BikeParkingSpaceEntity)

    /**
     * Get the count of parking spaces currently in the database.
     *
     * @return Number of parking spaces stored
     */
    @Query("SELECT COUNT(*) FROM bike_parking_spaces")
    suspend fun getSpaceCount(): Int

    /**
     * Retrieve parking spaces whose coordinates fall within the given bounding box.
     * Used for viewport-based loading to avoid fetching the entire Germany dataset at once.
     *
     * @param minLat Southern latitude bound (WGS-84)
     * @param maxLat Northern latitude bound (WGS-84)
     * @param minLon Western longitude bound (WGS-84)
     * @param maxLon Eastern longitude bound (WGS-84)
     * @return List of parking spaces inside the bounding box
     */
    @Query(
        "SELECT * FROM bike_parking_spaces " +
        "WHERE latitude BETWEEN :minLat AND :maxLat " +
        "AND longitude BETWEEN :minLon AND :maxLon"
    )
    suspend fun getSpacesInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): List<BikeParkingSpaceEntity>

    /**
     * Retrieve a specific set of parking spaces by their IDs.
     * Used to load full details of favorited spaces regardless of the current viewport.
     *
     * @param ids List of parking space IDs to fetch
     * @return List of matching parking space entities
     */
    @Query("SELECT * FROM bike_parking_spaces WHERE id IN (:ids)")
    suspend fun getSpacesByIds(ids: List<String>): List<BikeParkingSpaceEntity>

    /**
     * Get the timestamp of the most recent database update.
     *
     * @return Maximum lastUpdated timestamp, or 0 if table is empty
     */
    @Query("SELECT MAX(lastUpdated) FROM bike_parking_spaces")
    suspend fun getLastUpdateTimestamp(): Long?

    /**
     * Persist a resolved address for a single parking space.
     * Called after a successful Nominatim reverse geocoding to cache the result
     * and avoid redundant network requests in the future.
     *
     * @param id The unique ID of the parking space
     * @param address The human-readable address string to store
     */
    @Query("UPDATE bike_parking_spaces SET address = :address WHERE id = :id")
    suspend fun updateAddress(id: String, address: String)
}

