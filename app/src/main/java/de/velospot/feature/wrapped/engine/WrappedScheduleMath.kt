package de.velospot.feature.wrapped.engine

import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedPeriod
import de.velospot.feature.wrapped.domain.WrappedSchedule
import java.util.Calendar

/**
 * Pure calendar math for the "VeloSpot Wrapped" scheduler.
 *
 * Everything is computed via [Calendar] fields (never by adding fixed millis) so
 * it stays correct across DST transitions and months of unequal length. All work
 * is done in the device's local time zone with **Monday**-based weeks, matching
 * the shared ride statistics. No Android, no side effects — fully JVM-testable.
 */
internal object WrappedScheduleMath {

    private fun calendar(millis: Long): Calendar =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            timeInMillis = millis
        }

    private fun Calendar.applyTime(hour: Int, minute: Int) {
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    /**
     * The next epoch-millis at/after [now] that matches [schedule], or `null` when
     * the schedule is disabled.
     *
     * * DAILY — today at `HH:mm`, or tomorrow if that instant is already past.
     * * WEEKLY — the next occurrence of `dayOfWeek` at `HH:mm` (wraps to next week
     *   when today's occurrence is in the past).
     * * MONTHLY — the next occurrence of `dayOfMonth` at `HH:mm`, with `dayOfMonth`
     *   clamped to each candidate month's length (e.g. 31 → 28/29 in February).
     *
     * A candidate that is exactly [now] counts as "at/after now" and is returned.
     */
    fun nextFireTime(schedule: WrappedSchedule, now: Long): Long? {
        if (!schedule.enabled) return null
        return when (schedule.interval) {
            WrappedInterval.DAILY -> nextDaily(schedule, now)
            WrappedInterval.WEEKLY -> nextWeekly(schedule, now)
            WrappedInterval.MONTHLY -> nextMonthly(schedule, now)
        }
    }

    private fun nextDaily(schedule: WrappedSchedule, now: Long): Long {
        val cal = calendar(now).apply { applyTime(schedule.hour, schedule.minute) }
        if (cal.timeInMillis < now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun nextWeekly(schedule: WrappedSchedule, now: Long): Long {
        val cal = calendar(now).apply { applyTime(schedule.hour, schedule.minute) }
        // Advance one day at a time (max 8 steps) until we land on the target
        // weekday at/after now — robust for same-day earlier/later and week wrap.
        var guard = 0
        while ((cal.get(Calendar.DAY_OF_WEEK) != schedule.dayOfWeek || cal.timeInMillis < now) && guard < 8) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            guard++
        }
        return cal.timeInMillis
    }

    private fun nextMonthly(schedule: WrappedSchedule, now: Long): Long {
        val cal = calendar(now).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            applyTime(schedule.hour, schedule.minute)
            val clamped = schedule.dayOfMonth.coerceIn(1, getActualMaximum(Calendar.DAY_OF_MONTH))
            set(Calendar.DAY_OF_MONTH, clamped)
        }
        if (cal.timeInMillis < now) {
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.add(Calendar.MONTH, 1)
            val clamped = schedule.dayOfMonth.coerceIn(1, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
            cal.set(Calendar.DAY_OF_MONTH, clamped)
        }
        return cal.timeInMillis
    }

    /**
     * The last **fully closed** calendar bucket ending at/just before [fireTime].
     *
     * We deliberately report the previous *complete* bucket rather than the partial
     * "so far" one, so the story is always over a finished, unambiguous window:
     * * DAILY — the previous full day (yesterday 00:00 → today 00:00).
     * * WEEKLY — the previous full Monday–Sunday week.
     * * MONTHLY — the previous full calendar month.
     */
    fun periodForFire(schedule: WrappedSchedule, fireTime: Long): WrappedPeriod =
        when (schedule.interval) {
            WrappedInterval.DAILY -> WrappedPeriod.previous(WrappedPeriod.day(fireTime))
            WrappedInterval.WEEKLY -> WrappedPeriod.previous(WrappedPeriod.week(fireTime))
            WrappedInterval.MONTHLY -> WrappedPeriod.previous(WrappedPeriod.month(fireTime))
        }
}

