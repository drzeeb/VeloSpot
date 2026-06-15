package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.SavedPlaceEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for user-saved custom places.
 */
@Dao
interface SavedPlaceDao {

    /** All saved places, newest first. Updates reactively. */
    @Query("SELECT * FROM saved_places ORDER BY addedAt DESC")
    fun getAllFlow(): Flow<List<SavedPlaceEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE id = :id")
    suspend fun delete(id: String)
}

