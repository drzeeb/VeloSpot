package de.velospot.feature.backup.engine

import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import java.util.Calendar

/**
 * Pure calendar math for the automatic-backup scheduler.
 *
 * Everything is computed via [Calendar] fields (never by adding fixed millis) so it
 * stays correct across DST transitions and months of unequal length. All work is in
 * the device's local time zone with **Monday**-based weeks. No Android, no side
 * effects — fully JVM-testable. Duplicated from the "VeloSpot Wrapped" scheduler math
 * (minus the period logic, which backups don't need).
 */
object BackupScheduleMath {

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
     * The next epoch-millis at/after [now] that matches [schedule], or `null` when the
     * schedule is disabled.
     *
     * * DAILY — today at `HH:mm`, or tomorrow if that instant is already past.
     * * WEEKLY — the next occurrence of `dayOfWeek` at `HH:mm` (wraps to next week).
     * * MONTHLY — the next occurrence of `dayOfMonth` at `HH:mm`, `dayOfMonth` clamped
     *   to each candidate month's length.
     *
     * A candidate that is exactly [now] counts as "at/after now" and is returned.
     */
    fun nextFireTime(schedule: BackupSchedule, now: Long): Long? {
        if (!schedule.enabled) return null
        return when (schedule.interval) {
            BackupInterval.DAILY -> nextDaily(schedule, now)
            BackupInterval.WEEKLY -> nextWeekly(schedule, now)
            BackupInterval.MONTHLY -> nextMonthly(schedule, now)
        }
    }

    private fun nextDaily(schedule: BackupSchedule, now: Long): Long {
        val cal = calendar(now).apply { applyTime(schedule.hour, schedule.minute) }
        if (cal.timeInMillis < now) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    private fun nextWeekly(schedule: BackupSchedule, now: Long): Long {
        val cal = calendar(now).apply { applyTime(schedule.hour, schedule.minute) }
        var guard = 0
        while ((cal.get(Calendar.DAY_OF_WEEK) != schedule.dayOfWeek || cal.timeInMillis < now) && guard < 8) {
            cal.add(Calendar.DAY_OF_MONTH, 1)
            guard++
        }
        return cal.timeInMillis
    }

    private fun nextMonthly(schedule: BackupSchedule, now: Long): Long {
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
}

