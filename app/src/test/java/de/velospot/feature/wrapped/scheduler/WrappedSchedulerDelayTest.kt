package de.velospot.feature.wrapped.scheduler

import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class WrappedSchedulerDelayTest {

    /** Builds a local-time-zone epoch-millis for the given wall-clock fields. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `disabled schedule has no delay`() {
        val schedule = WrappedSchedule(enabled = false, interval = WrappedInterval.DAILY)
        assertNull(WrappedScheduler.initialDelayMillis(schedule, at(2024, 6, 12, 10, 0)))
    }

    @Test
    fun `delay is the gap to today's daily fire`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20, minute = 0)
        val now = at(2024, 6, 12, 18, 0)
        assertEquals(2 * 60 * 60 * 1000L, WrappedScheduler.initialDelayMillis(schedule, now))
    }

    @Test
    fun `delay wraps to tomorrow after the daily fire has passed`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20, minute = 0)
        val now = at(2024, 6, 12, 21, 0)
        // Next fire is tomorrow 20:00 → 23 hours away.
        assertEquals(23 * 60 * 60 * 1000L, WrappedScheduler.initialDelayMillis(schedule, now))
    }

    @Test
    fun `a fire exactly now collapses to a zero delay`() {
        val schedule = WrappedSchedule(enabled = true, interval = WrappedInterval.DAILY, hour = 20, minute = 0)
        val now = at(2024, 6, 12, 20, 0)
        assertEquals(0L, WrappedScheduler.initialDelayMillis(schedule, now))
    }
}

