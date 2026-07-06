package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.PlannedRouteEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for user-planned multi-waypoint routes.
 */
@Dao
interface PlannedRouteDao {

    /** All planned routes, newest first. Updates reactively. */
    @Query("SELECT * FROM planned_routes ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PlannedRouteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(route: PlannedRouteEntity)

    /** Renames a planned route. */
    @Query("UPDATE planned_routes SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String)

    @Query("DELETE FROM planned_routes WHERE id = :id")
    suspend fun delete(id: String)
}

