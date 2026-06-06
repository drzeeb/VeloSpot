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
     * Get the timestamp of the most recent database update.
     *
     * @return Maximum lastUpdated timestamp, or 0 if table is empty
     */
    @Query("SELECT MAX(lastUpdated) FROM bike_parking_spaces")
    suspend fun getLastUpdateTimestamp(): Long?
}

