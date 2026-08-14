package de.velospot.feature.wrapped.engine

import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedPeriodMode
import de.velospot.feature.wrapped.domain.WrappedPeriodType
import de.velospot.feature.wrapped.domain.WrappedSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class WrappedScheduleMathTest {

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

    // ── nextFireTime · DAILY ────────────────────────────────────────────────────

    @Test
    fun `daily fires today when before the target time`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20, minute = 0)
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 10, 0))
        assertEquals(at(2024, 6, 12, 20, 0), next)
    }

    @Test
    fun `daily fires tomorrow when past the target time`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20, minute = 0)
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 21, 0))
        assertEquals(at(2024, 6, 13, 20, 0), next)
    }

    @Test
    fun `daily fires now when exactly at the target time`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20, minute = 0)
        val now = at(2024, 6, 12, 20, 0)
        assertEquals(now, WrappedScheduleMath.nextFireTime(schedule, now))
    }

    // ── nextFireTime · WEEKLY ───────────────────────────────────────────────────

    @Test
    fun `weekly fires same day when before the target time`() {
        // 2024-06-16 is a Sunday.
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20
        )
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 16, 10, 0))
        assertEquals(at(2024, 6, 16, 20, 0), next)
    }

    @Test
    fun `weekly wraps to next week when past the target time on the target day`() {
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20
        )
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 16, 21, 0))
        assertEquals(at(2024, 6, 23, 20, 0), next)
    }

    @Test
    fun `weekly finds the next target day within the week`() {
        // From Wednesday 2024-06-12 → next Sunday is 2024-06-16.
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20
        )
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 10, 0))
        assertEquals(at(2024, 6, 16, 20, 0), next)
    }

    // ── nextFireTime · MONTHLY ──────────────────────────────────────────────────

    @Test
    fun `monthly fires this month on the target day`() {
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.MONTHLY, dayOfMonth = 15, hour = 20
        )
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 10, 10, 0))
        assertEquals(at(2024, 6, 15, 20, 0), next)
    }

    @Test
    fun `monthly rolls to next month when the target day has passed`() {
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.MONTHLY, dayOfMonth = 15, hour = 20
        )
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 20, 10, 0))
        assertEquals(at(2024, 7, 15, 20, 0), next)
    }

    @Test
    fun `monthly clamps dayOfMonth to a short month`() {
        // dayOfMonth 31 in February 2024 (a leap year) clamps to the 29th.
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.MONTHLY, dayOfMonth = 31, hour = 20
        )
        val next = WrappedScheduleMath.nextFireTime(schedule, at(2024, 2, 10, 10, 0))
        assertEquals(at(2024, 2, 29, 20, 0), next)
    }

    @Test
    fun `disabled schedule never fires`() {
        val schedule = WrappedSchedule(enabled = false, interval = WrappedInterval.DAILY)
        assertNull(WrappedScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 10, 0)))
    }

    // ── periodForFire ───────────────────────────────────────────────────────────

    @Test
    fun `daily period is the current running day`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20)
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 12, 20, 0))
        assertEquals(WrappedPeriodType.DAY, period.type)
        assertEquals(at(2024, 6, 12), period.startInclusive)
        assertEquals(at(2024, 6, 13), period.endExclusive)
    }

    @Test
    fun `weekly period is the current running Monday-Sunday week`() {
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20
        )
        // Fire on Sunday 2024-06-16 → current full week is Mon 06-10 .. Mon 06-17.
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 16, 20, 0))
        assertEquals(WrappedPeriodType.WEEK, period.type)
        assertEquals(at(2024, 6, 10), period.startInclusive)
        assertEquals(at(2024, 6, 17), period.endExclusive)
    }

    @Test
    fun `monthly period is the current running calendar month`() {
        val schedule = WrappedSchedule(
            enabled = true, interval = WrappedInterval.MONTHLY, dayOfMonth = 15, hour = 20
        )
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 15, 20, 0))
        assertEquals(WrappedPeriodType.MONTH, period.type)
        assertEquals(at(2024, 6, 1), period.startInclusive)
        assertEquals(at(2024, 7, 1), period.endExclusive)
    }

    // ── periodForFire · period mode ─────────────────────────────────────────────

    @Test
    fun `daily yesterday mode covers the previous calendar day`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.DAILY,
            hour = 20,
            periodMode = WrappedPeriodMode.CALENDAR_PREVIOUS
        )
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 12, 20, 0))
        assertEquals(WrappedPeriodType.DAY, period.type)
        assertEquals(at(2024, 6, 11), period.startInclusive)
        assertEquals(at(2024, 6, 12), period.endExclusive)
    }

    @Test
    fun `daily today mode covers the current calendar day`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.DAILY,
            hour = 20,
            periodMode = WrappedPeriodMode.CALENDAR_CURRENT
        )
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 12, 20, 0))
        assertEquals(at(2024, 6, 12), period.startInclusive)
        assertEquals(at(2024, 6, 13), period.endExclusive)
    }

    @Test
    fun `weekly rolling mode covers the last 7 days ending today`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.WEEKLY,
            hour = 20,
            periodMode = WrappedPeriodMode.ROLLING
        )
        // Fire on Sunday 2024-06-16 → rolling 7 days = Mon 06-10 .. Mon 06-17.
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 16, 20, 0))
        assertEquals(WrappedPeriodType.CUSTOM, period.type)
        assertEquals(at(2024, 6, 10), period.startInclusive)
        assertEquals(at(2024, 6, 17), period.endExclusive)
    }

    @Test
    fun `weekly rolling window is exactly seven whole days from a midweek fire`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.WEEKLY,
            hour = 20,
            periodMode = WrappedPeriodMode.ROLLING
        )
        // Fire on Wednesday 2024-06-12 → last 7 days = Thu 06-06 .. Thu 06-13.
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 12, 20, 0))
        assertEquals(at(2024, 6, 6), period.startInclusive)
        assertEquals(at(2024, 6, 13), period.endExclusive)
    }

    @Test
    fun `monthly rolling mode covers the fire month's length in days`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.MONTHLY,
            hour = 20,
            periodMode = WrappedPeriodMode.ROLLING
        )
        // June has 30 days → rolling window = May 18 .. Jun 17 (30 whole days).
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 16, 20, 0))
        assertEquals(WrappedPeriodType.CUSTOM, period.type)
        assertEquals(at(2024, 5, 18), period.startInclusive)
        assertEquals(at(2024, 6, 17), period.endExclusive)
    }

    @Test
    fun `monthly rolling window shrinks to a short month`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.MONTHLY,
            hour = 20,
            periodMode = WrappedPeriodMode.ROLLING
        )
        // February 2024 has 29 days → rolling window = Jan 31 .. Feb 29 (29 whole days).
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 2, 28, 20, 0))
        assertEquals(at(2024, 1, 31), period.startInclusive)
        assertEquals(at(2024, 2, 29), period.endExclusive)
    }

    @Test
    fun `monthly previous mode covers the last full calendar month`() {
        val schedule = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.MONTHLY,
            hour = 20,
            periodMode = WrappedPeriodMode.CALENDAR_PREVIOUS
        )
        val period = WrappedScheduleMath.periodForFire(schedule, at(2024, 6, 15, 20, 0))
        assertEquals(WrappedPeriodType.MONTH, period.type)
        assertEquals(at(2024, 5, 1), period.startInclusive)
        assertEquals(at(2024, 6, 1), period.endExclusive)
    }
}

