package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.FavoriteParkingSpaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for favorite bike parking spaces.
 * Handles operations related to user favorites.
 */
@Dao
interface FavoriteParkingSpaceDao {

    /**
     * Get all favorite parking spaces as a Flow.
     * Automatically updates when favorites change.
     *
     * @return Flow of favorite parking space entities ordered by most recent
     */
    @Query("SELECT * FROM favorite_parking_spaces ORDER BY addedAt DESC")
    fun getFavoritesFlow(): Flow<List<FavoriteParkingSpaceEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_parking_spaces WHERE parkingSpaceId = :parkingSpaceId)")
    suspend fun isFavorite(parkingSpaceId: String): Boolean

    /**
     * Add a parking space to favorites.
     *
     * @param favorite The favorite parking space entity to add
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteParkingSpaceEntity)

    /**
     * Remove a parking space from favorites by ID.
     *
     * @param parkingSpaceId The ID of the parking space to remove
     */
    @Query("DELETE FROM favorite_parking_spaces WHERE parkingSpaceId = :parkingSpaceId")
    suspend fun removeFavorite(parkingSpaceId: String)
}

