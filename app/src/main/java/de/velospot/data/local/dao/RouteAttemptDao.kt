package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.RouteAttemptEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for leaderboard attempts (completed rides of a planned route).
 */
@Dao
interface RouteAttemptDao {

    /** All attempts for a route (both directions), fastest first. */
    @Query("SELECT * FROM route_attempts WHERE routeId = :routeId ORDER BY elapsedSeconds ASC")
    fun getAttemptsFlow(routeId: String): Flow<List<RouteAttemptEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(attempt: RouteAttemptEntity)

    @Query("DELETE FROM route_attempts WHERE id = :id")
    suspend fun delete(id: String)

    /** Removes every attempt of a route (called when the route itself is deleted). */
    @Query("DELETE FROM route_attempts WHERE routeId = :routeId")
    suspend fun deleteForRoute(routeId: String)
}

