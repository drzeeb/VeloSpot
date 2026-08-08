package de.velospot.feature.wrapped.domain

import kotlinx.coroutines.flow.Flow

/**
 * Persistence gateway for generated "VeloSpot Wrapped" reports.
 *
 * The domain layer defines this interface; the data layer
 * ([de.velospot.feature.wrapped.data.WrappedRepositoryImpl]) implements it against
 * the dedicated `velospot_wrapped.db` store. Kept inside the `feature.wrapped`
 * package tree so the whole feature can move to a `:feature:wrapped` module later.
 */
internal interface WrappedRepository {

    /** All stored reports, newest generated first. Updates reactively. */
    fun observeReports(): Flow<List<WrappedReport>>

    /** The stored report with [id], or `null` when none exists. */
    suspend fun getReport(id: String): WrappedReport?

    /**
     * The stored report covering exactly [period], or `null`. Lets a scheduled run
     * avoid regenerating a duplicate report for a closed bucket it already covered.
     */
    suspend fun getReportForPeriod(period: WrappedPeriod): WrappedReport?

    /**
     * Persists [report] under a stable id derived from its period, so re-generating
     * the same closed bucket upserts rather than duplicates.
     */
    suspend fun saveReport(report: WrappedReport)

    /** Deletes the stored report with [id] (no-op when it does not exist). */
    suspend fun deleteReport(id: String)

    /** Removes every stored report. */
    suspend fun clearAll()
}

