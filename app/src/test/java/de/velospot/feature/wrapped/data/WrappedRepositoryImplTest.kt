package de.velospot.feature.wrapped.data

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.velospot.domain.model.RecordedRideSummary
import de.velospot.feature.wrapped.data.local.WrappedReportEntity
import de.velospot.feature.wrapped.data.local.WrappedReportDao
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedReport
import de.velospot.feature.wrapped.engine.WrappedEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WrappedRepositoryImplTest {

    /**
     * In-memory [WrappedReportDao] backed by a [MutableStateFlow] map, so the
     * reactive [getAllFlow] emits on every mutation — no Room/Android needed.
     */
    private class FakeWrappedReportDao : WrappedReportDao {
        private val rows = MutableStateFlow<Map<String, WrappedReportEntity>>(emptyMap())

        override fun getAllFlow(): Flow<List<WrappedReportEntity>> =
            rows.map { it.values.sortedByDescending { row -> row.generatedAt } }

        override suspend fun getById(id: String): WrappedReportEntity? = rows.value[id]

        override suspend fun getByPeriod(
            type: String,
            periodStart: Long,
            periodEnd: Long
        ): WrappedReportEntity? = rows.value.values.firstOrNull {
            it.type == type && it.periodStart == periodStart && it.periodEnd == periodEnd
        }

        override suspend fun upsert(entity: WrappedReportEntity) {
            rows.value = rows.value + (entity.id to entity)
        }

        override suspend fun delete(id: String) {
            rows.value = rows.value - id
        }

        override suspend fun deleteAll() {
            rows.value = emptyMap()
        }

        override suspend fun count(): Int = rows.value.size

        /** Test-only hook to inject a raw (possibly corrupt) row. */
        fun putRaw(entity: WrappedReportEntity) {
            rows.value = rows.value + (entity.id to entity)
        }
    }

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    private fun repo(dao: WrappedReportDao) = WrappedRepositoryImpl(dao, moshi)

    // ── Fixture helpers ─────────────────────────────────────────────────────────

    private fun at(year: Int, month: Int, day: Int, hour: Int = 12): Long =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            clear()
            set(year, month - 1, day, hour, 0, 0)
        }.timeInMillis

    private fun ride(
        id: String,
        startedAt: Long,
        distanceMeters: Double = 1_000.0,
        maxSpeedMps: Double = 8.0,
        gain: Double = 50.0
    ) = RecordedRideSummary(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + 600_000,
        distanceMeters = distanceMeters,
        elapsedSeconds = 600,
        movingSeconds = 600,
        avgSpeedMps = distanceMeters / 600,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = gain,
        elevationLossMeters = 40.0
    )

    private val now = at(2024, 6, 20)

    /** A faithful report built by the Phase-1 engine over real ride aggregates. */
    private fun reportFor(period: WrappedPeriod, rides: List<RecordedRideSummary>): WrappedReport =
        WrappedEngine.build(rides = rides, period = period, now = now)!!

    private val week = WrappedPeriod.week(at(2024, 6, 12))
    private val weekReport: WrappedReport
        get() = reportFor(
            week,
            listOf(
                ride("a", at(2024, 6, 11), distanceMeters = 6_000.0, maxSpeedMps = 12.0),
                ride("b", at(2024, 6, 13), distanceMeters = 4_000.0),
                ride("prev", at(2024, 6, 5), distanceMeters = 5_000.0)
            )
        )

    // ── Tests ────────────────────────────────────────────────────────────────────

    @Test
    fun `save then observe round-trips a value-equal report`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)
        val report = weekReport

        repo.saveReport(report)

        val observed = repo.observeReports().first()
        assertEquals(1, observed.size)
        // Value-equality proves the nested stats/comparison/highlights/period are lossless.
        assertEquals(report, observed.single())
    }

    @Test
    fun `observe orders newest generated first`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)

        val older = reportFor(week, listOf(ride("a", at(2024, 6, 11)))).copy(generatedAt = 1_000)
        val monthPeriod = WrappedPeriod.month(at(2024, 5, 15))
        val newer = reportFor(monthPeriod, listOf(ride("m", at(2024, 5, 10))))
            .copy(generatedAt = 2_000)

        repo.saveReport(older)
        repo.saveReport(newer)

        val observed = repo.observeReports().first()
        assertEquals(listOf(2_000L, 1_000L), observed.map { it.generatedAt })
    }

    @Test
    fun `saving the same period twice upserts to a single row`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)

        repo.saveReport(reportFor(week, listOf(ride("a", at(2024, 6, 11)))))
        repo.saveReport(
            reportFor(week, listOf(ride("a", at(2024, 6, 11), distanceMeters = 9_000.0)))
                .copy(generatedAt = 5_000)
        )

        assertEquals(1, dao.count())
        val observed = repo.observeReports().first().single()
        assertEquals(9_000.0, observed.stats.totalDistanceMeters, 0.0)
        assertEquals(5_000L, observed.generatedAt)
    }

    @Test
    fun `getReportForPeriod returns the stored bucket`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)
        val report = weekReport
        repo.saveReport(report)

        val found = repo.getReportForPeriod(week)
        assertNotNull(found)
        assertEquals(report, found)

        // A different (empty) bucket is not present.
        assertNull(repo.getReportForPeriod(WrappedPeriod.year(at(2020, 1, 1))))
    }

    @Test
    fun `getReport by id and delete`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)
        repo.saveReport(weekReport)

        val id = "${week.type.name}-${week.startInclusive}-${week.endExclusive}"
        assertNotNull(repo.getReport(id))

        repo.deleteReport(id)
        assertNull(repo.getReport(id))
        assertTrue(repo.observeReports().first().isEmpty())
    }

    @Test
    fun `clearAll removes every report`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)
        repo.saveReport(reportFor(week, listOf(ride("a", at(2024, 6, 11)))))
        repo.saveReport(reportFor(WrappedPeriod.month(at(2024, 5, 15)), listOf(ride("m", at(2024, 5, 10)))))

        repo.clearAll()
        assertTrue(repo.observeReports().first().isEmpty())
    }

    @Test
    fun `corrupt snapshot row is skipped not thrown`() = runTest {
        val dao = FakeWrappedReportDao()
        val repo = repo(dao)

        // One valid report and one deliberately corrupt snapshot.
        repo.saveReport(weekReport)
        dao.putRaw(
            WrappedReportEntity(
                id = "CUSTOM-1-2",
                type = "CUSTOM",
                periodStart = 1,
                periodEnd = 2,
                generatedAt = Long.MAX_VALUE, // would sort first if not skipped
                snapshotJson = "{ this is not valid json"
            )
        )

        val observed = repo.observeReports().first()
        // The corrupt row is dropped; the valid one survives.
        assertEquals(1, observed.size)
        assertEquals(weekReport, observed.single())
    }
}

