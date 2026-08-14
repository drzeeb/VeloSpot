package de.velospot.feature.wrapped.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class WrappedPeriodTest {

    /** Builds a local-time-zone epoch-millis for the given wall-clock fields. */
    private fun at(
        year: Int,
        month: Int, // 1-based
        day: Int,
        hour: Int = 0,
        minute: Int = 0
    ): Long = Calendar.getInstance().apply {
        firstDayOfWeek = Calendar.MONDAY
        clear()
        set(year, month - 1, day, hour, minute, 0)
    }.timeInMillis

    private fun fieldsOf(millis: Long): Calendar =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = millis
        }

    @Test
    fun `day contains now and spans exactly 24h wall clock`() {
        // Wednesday 2024-06-12 15:30 local.
        val now = at(2024, 6, 12, 15, 30)
        val p = WrappedPeriod.day(now)

        assertEquals(WrappedPeriodType.DAY, p.type)
        assertEquals(at(2024, 6, 12), p.startInclusive)
        assertEquals(at(2024, 6, 13), p.endExclusive)
    }

    @Test
    fun `week starts on Monday and ends next Monday`() {
        // Wednesday 2024-06-12 → week is Mon 2024-06-10 .. Mon 2024-06-17.
        val now = at(2024, 6, 12, 8, 0)
        val p = WrappedPeriod.week(now)

        assertEquals(WrappedPeriodType.WEEK, p.type)
        assertEquals(at(2024, 6, 10), p.startInclusive)
        assertEquals(at(2024, 6, 17), p.endExclusive)
        assertEquals(Calendar.MONDAY, fieldsOf(p.startInclusive).get(Calendar.DAY_OF_WEEK))
    }

    @Test
    fun `week on a Sunday still maps to the Monday-based week`() {
        // Sunday 2024-06-16 belongs to the Mon 2024-06-10 .. Mon 2024-06-17 week.
        val now = at(2024, 6, 16, 23, 59)
        val p = WrappedPeriod.week(now)

        assertEquals(at(2024, 6, 10), p.startInclusive)
        assertEquals(at(2024, 6, 17), p.endExclusive)
    }

    @Test
    fun `month spans the calendar month`() {
        val now = at(2024, 2, 15, 12, 0)
        val p = WrappedPeriod.month(now)

        assertEquals(WrappedPeriodType.MONTH, p.type)
        assertEquals(at(2024, 2, 1), p.startInclusive)
        assertEquals(at(2024, 3, 1), p.endExclusive)
    }

    @Test
    fun `year spans the calendar year`() {
        val now = at(2024, 7, 4, 9, 0)
        val p = WrappedPeriod.year(now)

        assertEquals(WrappedPeriodType.YEAR, p.type)
        assertEquals(at(2024, 1, 1), p.startInclusive)
        assertEquals(at(2025, 1, 1), p.endExclusive)
    }

    @Test
    fun `previous day is the day before`() {
        val p = WrappedPeriod.day(at(2024, 6, 12, 10, 0))
        val prev = WrappedPeriod.previous(p)

        assertEquals(WrappedPeriodType.DAY, prev.type)
        assertEquals(at(2024, 6, 11), prev.startInclusive)
        assertEquals(at(2024, 6, 12), prev.endExclusive)
    }

    @Test
    fun `previous week is the week before`() {
        val p = WrappedPeriod.week(at(2024, 6, 12, 10, 0))
        val prev = WrappedPeriod.previous(p)

        assertEquals(at(2024, 6, 3), prev.startInclusive)
        assertEquals(at(2024, 6, 10), prev.endExclusive)
    }

    @Test
    fun `previous month is the month before and handles unequal lengths`() {
        // March → February (28 days in 2024? 2024 is a leap year → 29).
        val p = WrappedPeriod.month(at(2024, 3, 15, 10, 0))
        val prev = WrappedPeriod.previous(p)

        assertEquals(at(2024, 2, 1), prev.startInclusive)
        assertEquals(at(2024, 3, 1), prev.endExclusive)
    }

    @Test
    fun `previous year is the year before`() {
        val p = WrappedPeriod.year(at(2024, 7, 4, 9, 0))
        val prev = WrappedPeriod.previous(p)

        assertEquals(at(2023, 1, 1), prev.startInclusive)
        assertEquals(at(2024, 1, 1), prev.endExclusive)
    }

    @Test
    fun `previous custom shifts back by exact length`() {
        val from = at(2024, 6, 1, 0, 0)
        val to = at(2024, 6, 8, 0, 0) // 7-day custom window
        val p = WrappedPeriod.custom(from, to)
        val length = to - from
        val prev = WrappedPeriod.previous(p)

        assertEquals(WrappedPeriodType.CUSTOM, prev.type)
        assertEquals(from - length, prev.startInclusive)
        assertEquals(to - length, prev.endExclusive)
    }

    @Test
    fun `rollingDays covers exactly N whole days ending with today`() {
        // Wednesday 2024-06-12 15:30 → last 7 days = Thu 06-06 .. Thu 06-13.
        val p = WrappedPeriod.rollingDays(at(2024, 6, 12, 15, 30), 7)

        assertEquals(WrappedPeriodType.CUSTOM, p.type)
        assertEquals(at(2024, 6, 6), p.startInclusive)
        assertEquals(at(2024, 6, 13), p.endExclusive)
    }

    @Test
    fun `rollingDays coerces a non-positive length to one day`() {
        val p = WrappedPeriod.rollingDays(at(2024, 6, 12, 15, 30), 0)

        assertEquals(at(2024, 6, 12), p.startInclusive)
        assertEquals(at(2024, 6, 13), p.endExclusive)
    }

    @Test
    fun `rollingMonth uses the fire month's length`() {
        // February 2024 has 29 days → 29 whole days ending Feb 28.
        val p = WrappedPeriod.rollingMonth(at(2024, 2, 28, 12, 0))

        assertEquals(WrappedPeriodType.CUSTOM, p.type)
        assertEquals(at(2024, 1, 31), p.startInclusive)
        assertEquals(at(2024, 2, 29), p.endExclusive)
    }
}

