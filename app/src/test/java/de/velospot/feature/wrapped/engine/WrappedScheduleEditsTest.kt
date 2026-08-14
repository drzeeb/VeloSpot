package de.velospot.feature.wrapped.engine

import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * JVM coverage for the pure schedule-edit reducers behind the Phase-5 settings UI:
 * every action clamps its value so a persisted [WrappedSchedule] stays valid.
 */
class WrappedScheduleEditsTest {

    private val base = WrappedSchedule(
        enabled = false,
        interval = WrappedInterval.DAILY,
        dayOfWeek = Calendar.SUNDAY,
        dayOfMonth = 1,
        hour = 20,
        minute = 0
    )

    @Test
    fun `withEnabled toggles the flag`() {
        assertEquals(true, WrappedScheduleEdits.withEnabled(base, true).enabled)
        assertEquals(false, WrappedScheduleEdits.withEnabled(base.copy(enabled = true), false).enabled)
    }

    @Test
    fun `withInterval switches cadence`() {
        assertEquals(
            WrappedInterval.MONTHLY,
            WrappedScheduleEdits.withInterval(base, WrappedInterval.MONTHLY).interval
        )
    }

    @Test
    fun `withDayOfWeek clamps to the 1 to 7 calendar range`() {
        assertEquals(1, WrappedScheduleEdits.withDayOfWeek(base, 0).dayOfWeek)
        assertEquals(7, WrappedScheduleEdits.withDayOfWeek(base, 9).dayOfWeek)
        assertEquals(Calendar.WEDNESDAY, WrappedScheduleEdits.withDayOfWeek(base, Calendar.WEDNESDAY).dayOfWeek)
    }

    @Test
    fun `withDayOfMonth clamps to 1 to 31`() {
        assertEquals(1, WrappedScheduleEdits.withDayOfMonth(base, 0).dayOfMonth)
        assertEquals(31, WrappedScheduleEdits.withDayOfMonth(base, 40).dayOfMonth)
        assertEquals(15, WrappedScheduleEdits.withDayOfMonth(base, 15).dayOfMonth)
    }

    @Test
    fun `withTime clamps hour and minute to a valid wall clock`() {
        val over = WrappedScheduleEdits.withTime(base, 30, 90)
        assertEquals(23, over.hour)
        assertEquals(59, over.minute)
        val under = WrappedScheduleEdits.withTime(base, -1, -5)
        assertEquals(0, under.hour)
        assertEquals(0, under.minute)
        val ok = WrappedScheduleEdits.withTime(base, 7, 45)
        assertEquals(7, ok.hour)
        assertEquals(45, ok.minute)
    }

    @Test
    fun `withNotifyOnGenerate toggles the flag`() {
        assertEquals(false, WrappedScheduleEdits.withNotifyOnGenerate(base, false).notifyOnGenerate)
        assertEquals(
            true,
            WrappedScheduleEdits.withNotifyOnGenerate(base.copy(notifyOnGenerate = false), true).notifyOnGenerate
        )
    }
}

