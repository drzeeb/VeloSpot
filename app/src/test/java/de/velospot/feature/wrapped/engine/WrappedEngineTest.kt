package de.velospot.feature.wrapped.engine

import de.velospot.domain.model.RecordedRideSummary
import de.velospot.feature.wrapped.domain.WrappedHighlightType
import de.velospot.feature.wrapped.domain.WrappedPeriod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WrappedEngineTest {

    /** Builds a local-time-zone epoch-millis for the given wall-clock fields. */
    private fun at(
        year: Int,
        month: Int, // 1-based
        day: Int,
        hour: Int = 12,
        minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun ride(
        id: String,
        startedAt: Long,
        distanceMeters: Double = 1_000.0,
        elapsedSeconds: Long = 600,
        movingSeconds: Long = 600,
        maxSpeedMps: Double = 8.0,
        gain: Double = 50.0,
        loss: Double = 40.0,
        isMock: Boolean = false
    ) = RecordedRideSummary(
        id = id,
        startedAt = startedAt,
        endedAt = startedAt + elapsedSeconds * 1_000,
        distanceMeters = distanceMeters,
        elapsedSeconds = elapsedSeconds,
        movingSeconds = movingSeconds,
        avgSpeedMps = distanceMeters / movingSeconds,
        maxSpeedMps = maxSpeedMps,
        elevationGainMeters = gain,
        elevationLossMeters = loss,
        isMock = isMock
    )

    // Current window: the Mon 2024-06-10 .. Mon 2024-06-17 week.
    private val week = WrappedPeriod.week(at(2024, 6, 12))
    private val now = at(2024, 6, 20)

    @Test
    fun `filters rides to the half-open period bounds`() {
        val report = WrappedEngine.build(
            rides = listOf(
                ride("in-start", week.startInclusive),                 // inclusive lower bound
                ride("before", week.startInclusive - 1),               // just outside → excluded
                ride("in-end", week.endExclusive - 1),                 // just inside upper bound
                ride("at-end", week.endExclusive)                      // exclusive upper bound → excluded
            ),
            period = week,
            now = now
        )
        assertNotNull(report)
        assertEquals(2, report!!.stats.rideCount)
    }

    @Test
    fun `mock rides are excluded`() {
        val report = WrappedEngine.build(
            rides = listOf(
                ride("real", at(2024, 6, 11), distanceMeters = 2_000.0),
                ride("mock", at(2024, 6, 12), distanceMeters = 9_000.0, isMock = true)
            ),
            period = week,
            now = now
        )
        assertNotNull(report)
        assertEquals(1, report!!.stats.rideCount)
        assertEquals(2_000.0, report.stats.totalDistanceMeters, 0.0)
    }

    @Test
    fun `empty period returns null`() {
        val report = WrappedEngine.build(
            rides = listOf(ride("outside", week.startInclusive - 1)),
            period = week,
            now = now
        )
        assertNull(report)
    }

    @Test
    fun `only-mock period returns null`() {
        val report = WrappedEngine.build(
            rides = listOf(ride("m", at(2024, 6, 11), isMock = true)),
            period = week,
            now = now
        )
        assertNull(report)
    }

    @Test
    fun `comparison computes distance and ride-count deltas vs previous window`() {
        val report = WrappedEngine.build(
            rides = listOf(
                // Current week: 2 rides, 10 km total.
                ride("c1", at(2024, 6, 11), distanceMeters = 6_000.0),
                ride("c2", at(2024, 6, 13), distanceMeters = 4_000.0),
                // Previous week (2024-06-03 .. 2024-06-10): 1 ride, 5 km.
                ride("p1", at(2024, 6, 5), distanceMeters = 5_000.0)
            ),
            period = week,
            now = now
        )!!
        assertEquals(5_000.0, report.comparison.previousDistanceMeters, 0.0)
        assertEquals(100.0, report.comparison.distanceDeltaPercent!!, 0.0001)
        assertEquals(1, report.comparison.previousRideCount)
        assertEquals(1, report.comparison.rideCountDelta)
        assertTrue(report.highlights.any { it.type == WrappedHighlightType.VS_PREVIOUS_DISTANCE })
        assertTrue(report.highlights.any { it.type == WrappedHighlightType.VS_PREVIOUS_RIDES })
    }

    @Test
    fun `comparison delta percent is null and VS highlights omitted when previous window empty`() {
        val report = WrappedEngine.build(
            rides = listOf(ride("c1", at(2024, 6, 11), distanceMeters = 6_000.0)),
            period = week,
            now = now
        )!!
        assertEquals(0.0, report.comparison.previousDistanceMeters, 0.0)
        assertNull(report.comparison.distanceDeltaPercent)
        assertEquals(0, report.comparison.previousRideCount)
        assertFalse(report.highlights.any { it.type == WrappedHighlightType.VS_PREVIOUS_DISTANCE })
        assertFalse(report.highlights.any { it.type == WrappedHighlightType.VS_PREVIOUS_RIDES })
    }

    @Test
    fun `new distance record emitted when period beats prior all-time best`() {
        val report = WrappedEngine.build(
            rides = listOf(
                // Prior all-time best: a 3 km ride well before the window.
                ride("old", at(2024, 1, 1), distanceMeters = 3_000.0),
                // Period ride that beats it.
                ride("new", at(2024, 6, 12), distanceMeters = 8_000.0)
            ),
            period = week,
            now = now
        )!!
        val record = report.highlights.firstOrNull {
            it.type == WrappedHighlightType.NEW_DISTANCE_RECORD
        }
        assertNotNull(record)
        assertEquals(8_000.0, record!!.valueNumber, 0.0)
        assertEquals("new", record.rideId)
        // NEW_* records come first in the ordered list.
        assertEquals(WrappedHighlightType.NEW_DISTANCE_RECORD, report.highlights.first().type)
    }

    @Test
    fun `no new record when period does not beat prior best`() {
        val report = WrappedEngine.build(
            rides = listOf(
                ride("old", at(2024, 1, 1), distanceMeters = 9_000.0),
                ride("new", at(2024, 6, 12), distanceMeters = 4_000.0)
            ),
            period = week,
            now = now
        )!!
        assertFalse(report.highlights.any { it.type == WrappedHighlightType.NEW_DISTANCE_RECORD })
    }

    @Test
    fun `no new record when there is no prior history`() {
        val report = WrappedEngine.build(
            rides = listOf(ride("first", at(2024, 6, 12), distanceMeters = 4_000.0)),
            period = week,
            now = now
        )!!
        assertFalse(report.highlights.any { it.type == WrappedHighlightType.NEW_DISTANCE_RECORD })
    }

    @Test
    fun `current streak highlight omitted when zero`() {
        // Rides are far in the past relative to `now`, so current streak is 0.
        val report = WrappedEngine.build(
            rides = listOf(ride("c1", at(2024, 6, 12))),
            period = week,
            now = now
        )!!
        assertEquals(0, report.stats.currentStreakDays)
        assertFalse(report.highlights.any { it.type == WrappedHighlightType.CURRENT_STREAK })
    }

    @Test
    fun `longest ride and top speed highlights carry the source ride id`() {
        val report = WrappedEngine.build(
            rides = listOf(
                ride("short", at(2024, 6, 11), distanceMeters = 2_000.0, maxSpeedMps = 6.0),
                ride("long", at(2024, 6, 13), distanceMeters = 9_000.0, maxSpeedMps = 15.0)
            ),
            period = week,
            now = now
        )!!
        val longest = report.highlights.first { it.type == WrappedHighlightType.LONGEST_RIDE }
        val top = report.highlights.first { it.type == WrappedHighlightType.TOP_SPEED }
        assertEquals("long", longest.rideId)
        assertEquals("long", top.rideId)
    }
}

