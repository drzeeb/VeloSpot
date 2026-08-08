package de.velospot.feature.wrapped.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for stored "VeloSpot Wrapped" reports.
 */
@Dao
interface WrappedReportDao {

    /** All stored reports, newest generated first. Updates reactively. */
    @Query("SELECT * FROM wrapped_reports ORDER BY generatedAt DESC")
    fun getAllFlow(): Flow<List<WrappedReportEntity>>

    /** A single report by its stable id, or `null` when it no longer exists. */
    @Query("SELECT * FROM wrapped_reports WHERE id = :id")
    suspend fun getById(id: String): WrappedReportEntity?

    /**
     * The report already stored for the exact `[type, periodStart, periodEnd)`
     * bucket, or `null`. Lets a scheduled run skip regenerating a duplicate report
     * for a period it already covered.
     */
    @Query(
        "SELECT * FROM wrapped_reports " +
            "WHERE type = :type AND periodStart = :periodStart AND periodEnd = :periodEnd " +
            "LIMIT 1"
    )
    suspend fun getByPeriod(type: String, periodStart: Long, periodEnd: Long): WrappedReportEntity?

    @Upsert
    suspend fun upsert(entity: WrappedReportEntity)

    @Query("DELETE FROM wrapped_reports WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM wrapped_reports")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM wrapped_reports")
    suspend fun count(): Int
}

