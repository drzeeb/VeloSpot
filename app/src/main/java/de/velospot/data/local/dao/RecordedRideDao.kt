package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.RecordedRideEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for completed, recorded rides.
 */
@Dao
interface RecordedRideDao {

    /** All recorded rides, newest first. Updates reactively. */
    @Query("SELECT * FROM recorded_rides ORDER BY startedAt DESC")
    fun getAllFlow(): Flow<List<RecordedRideEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ride: RecordedRideEntity)

    /** Renames a ride (or clears its name when [name] is null). */
    @Query("UPDATE recorded_rides SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String?)

    /** Archives a ride (sets [archivedAt]) or restores it (pass `null`). */
    @Query("UPDATE recorded_rides SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun updateArchivedAt(id: String, archivedAt: Long?)

    @Query("DELETE FROM recorded_rides WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recorded_rides")
    suspend fun deleteAll()
}

