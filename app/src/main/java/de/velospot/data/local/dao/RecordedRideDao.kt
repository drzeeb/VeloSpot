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
 * Track-free **full-meta** projection of a recorded ride: every entity column
 * **except** the heavy `pointsJson` (including `sourceRouteId`, which the leaner
 * [RecordedRideSummaryRow] omits). Returned by the meta queries so a full
 * [de.velospot.domain.model.RecordedRide] can be rebuilt without ever selecting
 * `pointsJson` as a whole cell — a dense imported track can exceed SQLite's
 * ~2 MB per-row `CursorWindow` limit and would otherwise throw
 * `SQLiteBlobTooBigException` on any `SELECT *`. The track is read separately in
 * chunks via [RecordedRideDao.getPointsJsonChunk].
 */
data class RecordedRideMetaRow(
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
    val bikeProfileId: String?,
    val sourceRouteId: String?,
    val weatherJson: String?
)

/**
 * Geometry-**key** projection of a recorded ride: just enough to feed the map
 * overlays' geometry-only source without touching the aggregate columns. Holds the
 * ride [id], whether it [isMock] (mock rides are excluded from the overlays) and
 * the character [pointsLength] of its stored `pointsJson`. The length lets the
 * repository read the track in chunks *and* lets collectors gate re-emission on
 * the **track set** alone: a rename / bike-reassign / archive changes other
 * columns but never these, so `distinctUntilChanged` suppresses a needless
 * re-deserialisation of the whole ride history while a layer is visible.
 */
data class RecordedRideTrackKeyRow(
    val id: String,
    val isMock: Boolean,
    val pointsLength: Int
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

    /**
     * All recorded rides as **track-free** full-meta rows, newest first. Explicitly
     * lists every column except `pointsJson` so an oversized track never has to be
     * squeezed into the `CursorWindow`; callers reassemble the track separately via
     * the chunked [getPointsJsonChunk] reads.
     */
    @Query(
        "SELECT id, startedAt, endedAt, distanceMeters, elapsedSeconds, movingSeconds, " +
        "avgSpeedMps, maxSpeedMps, elevationGainMeters, elevationLossMeters, " +
        "name, isMock, archivedAt, bikeProfileId, sourceRouteId, weatherJson " +
        "FROM recorded_rides ORDER BY startedAt DESC"
    )
    fun getAllMetaFlow(): Flow<List<RecordedRideMetaRow>>

    /**
     * Geometry-key rows for **every** ride, newest first: id, `isMock` and the
     * length of the stored track. Reactive, but selects no aggregate column and
     * never the track itself, so it stays cheap and — combined with a
     * `distinctUntilChanged` on the collector — only changes when a track is
     * added/removed/replaced (not on a rename / archive / bike-reassign). Drives
     * the map overlays' geometry-only source.
     */
    @Query(
        "SELECT id, isMock, length(pointsJson) AS pointsLength " +
        "FROM recorded_rides ORDER BY startedAt DESC"
    )
    fun getTrackKeysFlow(): Flow<List<RecordedRideTrackKeyRow>>

    /** A single ride's **track-free** full-meta row, or `null` when it no longer exists. */
    @Query(
        "SELECT id, startedAt, endedAt, distanceMeters, elapsedSeconds, movingSeconds, " +
        "avgSpeedMps, maxSpeedMps, elevationGainMeters, elevationLossMeters, " +
        "name, isMock, archivedAt, bikeProfileId, sourceRouteId, weatherJson " +
        "FROM recorded_rides WHERE id = :id"
    )
    suspend fun getMetaById(id: String): RecordedRideMetaRow?

    /** The **track-free** full-meta rows for the given [ids]. */
    @Query(
        "SELECT id, startedAt, endedAt, distanceMeters, elapsedSeconds, movingSeconds, " +
        "avgSpeedMps, maxSpeedMps, elevationGainMeters, elevationLossMeters, " +
        "name, isMock, archivedAt, bikeProfileId, sourceRouteId, weatherJson " +
        "FROM recorded_rides WHERE id IN (:ids)"
    )
    suspend fun getMetaByIds(ids: List<String>): List<RecordedRideMetaRow>

    /**
     * Byte/char length of the stored `pointsJson` for [id], or `null` when the ride
     * does not exist. Drives the chunked track reassembly (see [getPointsJsonChunk])
     * so the huge cell is never read whole into the `CursorWindow`.
     */
    @Query("SELECT length(pointsJson) FROM recorded_rides WHERE id = :id")
    suspend fun getPointsJsonLength(id: String): Int?

    /**
     * A [count]-character slice of `pointsJson` for [id] starting at 1-based [start]
     * (SQLite `substr` is 1-based). Reading the track in bounded chunks keeps each
     * cursor row comfortably under the ~2 MB `CursorWindow` limit that a dense
     * imported track would otherwise blow when selected as one cell.
     */
    @Query("SELECT substr(pointsJson, :start, :count) FROM recorded_rides WHERE id = :id")
    suspend fun getPointsJsonChunk(id: String, start: Int, count: Int): String?

    /**
     * Every recorded ride's id, newest first. Used by the local backup export to
     * iterate rides one at a time and read each track separately in chunks (via
     * [getPointsJsonLength] / [getPointsJsonChunk]) so a dense multi-MB track never
     * has to be squeezed into the `CursorWindow` in a `SELECT *`.
     */
    @Query("SELECT id FROM recorded_rides ORDER BY startedAt DESC")
    suspend fun getAllIds(): List<String>

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

    /**
     * Overwrites the denormalised elevation aggregate columns for a ride. Used by
     * the one-off backfill that recomputes gain/loss from the stored raw track;
     * touches only the two derived columns (no track rewrite, no schema change).
     */
    @Query("UPDATE recorded_rides SET elevationGainMeters = :gain, elevationLossMeters = :loss WHERE id = :id")
    suspend fun updateElevation(id: String, gain: Double, loss: Double)

    /**
     * Overwrites the denormalised max-speed aggregate column for a ride. Used by
     * the one-off backfill that recomputes the peak from the stored per-point
     * Doppler speeds; touches only this derived column (no track rewrite, no
     * schema change).
     */
    @Query("UPDATE recorded_rides SET maxSpeedMps = :maxSpeedMps WHERE id = :id")
    suspend fun updateMaxSpeed(id: String, maxSpeedMps: Double)

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

