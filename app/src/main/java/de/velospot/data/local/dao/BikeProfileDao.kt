package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import de.velospot.data.local.entity.BikeProfileEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for the rider's bike garage ([BikeProfileEntity]).
 */
@Dao
interface BikeProfileDao {

    /** All bikes, oldest first (creation order), updating reactively. */
    @Query("SELECT * FROM bike_profiles ORDER BY createdAt ASC")
    fun getAllFlow(): Flow<List<BikeProfileEntity>>

    @Query("SELECT * FROM bike_profiles WHERE id = :id")
    suspend fun getById(id: String): BikeProfileEntity?

    /** The id of the default bike, or `null` when none is marked default. */
    @Query("SELECT id FROM bike_profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultId(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: BikeProfileEntity)

    @Query("DELETE FROM bike_profiles WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE bike_profiles SET isDefault = 0")
    suspend fun clearDefaultFlags()

    @Query("UPDATE bike_profiles SET isDefault = 1 WHERE id = :id")
    suspend fun markDefault(id: String)

    /**
     * Makes [id] the one and only default bike in a single transaction so there is
     * never a window with two defaults (or none) visible to a concurrent reader.
     */
    @Transaction
    suspend fun setDefault(id: String) {
        clearDefaultFlags()
        markDefault(id)
    }
}

