package de.velospot.feature.wrapped.data

import com.squareup.moshi.Moshi
import de.velospot.feature.wrapped.data.local.WrappedReportDao
import de.velospot.feature.wrapped.data.local.WrappedReportEntity
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedReport
import de.velospot.feature.wrapped.domain.WrappedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [WrappedRepository].
 *
 * The full [WrappedReport] is serialised to a compact JSON snapshot (via the
 * shared [Moshi]) so the nested stats / comparison / highlights round-trip
 * losslessly; the period + `generatedAt` are also mirrored into dedicated columns
 * so the history list can be ordered/queried without parsing a snapshot.
 *
 * All (de)serialisation runs off the main thread ([Dispatchers.Default]); a corrupt
 * snapshot row is skipped rather than allowed to crash the reactive flow.
 */
@Singleton
internal class WrappedRepositoryImpl @Inject constructor(
    private val dao: WrappedReportDao,
    moshi: Moshi
) : WrappedRepository {

    private val adapter = moshi.adapter(WrappedReport::class.java)

    override fun observeReports(): Flow<List<WrappedReport>> =
        dao.getAllFlow()
            .map { rows -> rows.mapNotNull { it.toDomainOrNull() } }
            .flowOn(Dispatchers.Default)

    override suspend fun getReport(id: String): WrappedReport? =
        withContext(Dispatchers.Default) {
            dao.getById(id)?.toDomainOrNull()
        }

    override suspend fun getReportForPeriod(period: WrappedPeriod): WrappedReport? =
        withContext(Dispatchers.Default) {
            dao.getByPeriod(
                type = period.type.name,
                periodStart = period.startInclusive,
                periodEnd = period.endExclusive
            )?.toDomainOrNull()
        }

    override suspend fun saveReport(report: WrappedReport) {
        val entity = withContext(Dispatchers.Default) { report.toEntity() }
        dao.upsert(entity)
    }

    override suspend fun deleteReport(id: String) = dao.delete(id)

    override suspend fun clearAll() = dao.deleteAll()

    /** Deserialises the stored snapshot, returning `null` for a corrupt row. */
    private fun WrappedReportEntity.toDomainOrNull(): WrappedReport? =
        runCatching { adapter.fromJson(snapshotJson) }.getOrNull()

    private fun WrappedReport.toEntity() = WrappedReportEntity(
        id = idFor(period),
        type = period.type.name,
        periodStart = period.startInclusive,
        periodEnd = period.endExclusive,
        generatedAt = generatedAt,
        snapshotJson = adapter.toJson(this)
    )

    private companion object {
        /**
         * Stable, deterministic id so re-generating the same closed bucket upserts
         * over the previous row instead of creating a duplicate.
         */
        fun idFor(period: WrappedPeriod): String =
            "${period.type.name}-${period.startInclusive}-${period.endExclusive}"
    }
}

