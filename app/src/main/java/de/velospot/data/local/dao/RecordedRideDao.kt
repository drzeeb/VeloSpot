package de.velospot.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.velospot.data.local.entity.RecordedRideEntity
import kotlinx.coroutines.flow.Flow

/**
 * Track-free projection of a recorded ride: every aggregate column **except** the
 * heavy `pointsJson`. Returned by [RecordedRideDao.getSummariesFlow] so the
 * timeline can be rendered (and re-rendered on every DB change) without ever
 * reading — let alone deserialising — a single GPS track. Room maps the selected
 * columns onto these fields by name.
 */
data class RecordedRideSummaryRow(
    val id: String,
    val startedAt: Long,
    val endedAt: Long,
    val distanceMeters: Double,
    val elapsedSeconds: Long,
    val movingSeconds: Long,
    val avgSpeedMps: Double,
    val maxSpeedMps: Double,
    val elevationGainMeters: Double,
    val elevationLossMeters: Double,
    val name: String?,
    val isMock: Boolean,
    val archivedAt: Long?,
    val bikeProfileId: String?
)

/**
 * Data Access Object for completed, recorded rides.
 */
@Dao
interface RecordedRideDao {

    /**
     * All recorded rides as lightweight, **track-free** summaries, newest first.
     * Updates reactively. Explicitly lists the columns so the multi-kilobyte
     * `pointsJson` is never loaded for the timeline.
     */
    @Query(
        "SELECT id, startedAt, endedAt, distanceMeters, elapsedSeconds, movingSeconds, " +
        "avgSpeedMps, maxSpeedMps, elevationGainMeters, elevationLossMeters, " +
        "name, isMock, archivedAt, bikeProfileId " +
        "FROM recorded_rides ORDER BY startedAt DESC"
    )
    fun getSummariesFlow(): Flow<List<RecordedRideSummaryRow>>

    /** All recorded rides **with** their full GPS track, newest first. */
    @Query("SELECT * FROM recorded_rides ORDER BY startedAt DESC")
    fun getAllFlow(): Flow<List<RecordedRideEntity>>

    /** A single ride **with** its full GPS track, or `null` when it no longer exists. */
    @Query("SELECT * FROM recorded_rides WHERE id = :id")
    suspend fun getById(id: String): RecordedRideEntity?

    /** The full rides (tracks included) for the given [ids]. */
    @Query("SELECT * FROM recorded_rides WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<RecordedRideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(ride: RecordedRideEntity)

    /** Renames a ride (or clears its name when [name] is null). */
    @Query("UPDATE recorded_rides SET name = :name WHERE id = :id")
    suspend fun updateName(id: String, name: String?)

    /** Archives a ride (sets [archivedAt]) or restores it (pass `null`). */
    @Query("UPDATE recorded_rides SET archivedAt = :archivedAt WHERE id = :id")
    suspend fun updateArchivedAt(id: String, archivedAt: Long?)

    /** (Re)assigns a ride to a bike ([bikeProfileId]), or clears it (pass `null`). */
    @Query("UPDATE recorded_rides SET bikeProfileId = :bikeProfileId WHERE id = :id")
    suspend fun updateBikeProfile(id: String, bikeProfileId: String?)

    /** Tags a ride with the planned route it was ridden along (or clears it). */
    @Query("UPDATE recorded_rides SET sourceRouteId = :sourceRouteId WHERE id = :id")
    suspend fun updateSourceRoute(id: String, sourceRouteId: String?)

    /** Detaches every ride from [bikeProfileId] (used when its bike is deleted). */
    @Query("UPDATE recorded_rides SET bikeProfileId = NULL WHERE bikeProfileId = :bikeProfileId")
    suspend fun clearBikeProfile(bikeProfileId: String)

    /**
     * Total ridden distance (metres) tagged to [bikeProfileId], real rides only
     * (mock/simulator rides excluded) — drives the per-bike service milestones.
     */
    @Query("SELECT COALESCE(SUM(distanceMeters), 0) FROM recorded_rides WHERE bikeProfileId = :bikeProfileId AND isMock = 0")
    suspend fun totalDistanceForBike(bikeProfileId: String): Double

    @Query("DELETE FROM recorded_rides WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM recorded_rides")
    suspend fun deleteAll()
}

