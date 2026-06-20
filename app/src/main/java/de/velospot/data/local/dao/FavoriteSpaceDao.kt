package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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
}

