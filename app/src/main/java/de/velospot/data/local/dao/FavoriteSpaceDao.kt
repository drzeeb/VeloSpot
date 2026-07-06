package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.velospot.data.local.entity.FavoriteSpaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for favourite bike parking spaces stored in the isolated
 * [de.velospot.data.local.database.FavoritesDatabase].
 */
@Dao
interface FavoriteSpaceDao {

    /** All favourites as a reactive flow, most recently added first. */
    @Query("SELECT * FROM favorite_parking_spaces ORDER BY addedAt DESC")
    fun getFavoritesFlow(): Flow<List<FavoriteSpaceEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_parking_spaces WHERE parkingSpaceId = :parkingSpaceId)")
    suspend fun isFavorite(parkingSpaceId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteSpaceEntity)

    @Query("DELETE FROM favorite_parking_spaces WHERE parkingSpaceId = :parkingSpaceId")
    suspend fun removeFavorite(parkingSpaceId: String)

    /**
     * Atomically flips a space's favourite state in a single transaction: removes
     * it when present, otherwise adds it. Replaces the previous read-then-write in
     * the ViewModel, which raced when the button was tapped twice in quick
     * succession (both reads could observe the same state and both add/remove).
     */
    @Transaction
    suspend fun toggleFavorite(parkingSpaceId: String) {
        if (isFavorite(parkingSpaceId)) {
            removeFavorite(parkingSpaceId)
        } else {
            addFavorite(FavoriteSpaceEntity(parkingSpaceId = parkingSpaceId))
        }
    }
}

