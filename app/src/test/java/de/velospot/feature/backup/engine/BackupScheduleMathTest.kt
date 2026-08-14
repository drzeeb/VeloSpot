package de.velospot.feature.backup.engine

import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BackupScheduleMathTest {

    /** Builds a local-time-zone epoch-millis for the given wall-clock fields. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    // ── DAILY ───────────────────────────────────────────────────────────────────

    @Test
    fun `daily fires today when before the target time`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        assertEquals(at(2024, 6, 12, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 10, 0)))
    }

    @Test
    fun `daily fires tomorrow when past the target time`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        assertEquals(at(2024, 6, 13, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 21, 0)))
    }

    @Test
    fun `daily fires now when exactly at the target time`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        val now = at(2024, 6, 12, 20, 0)
        assertEquals(now, BackupScheduleMath.nextFireTime(schedule, now))
    }

    // ── WEEKLY ──────────────────────────────────────────────────────────────────

    @Test
    fun `weekly fires same day when before the target time`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20)
        assertEquals(at(2024, 6, 16, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 16, 10, 0)))
    }

    @Test
    fun `weekly wraps to next week when past the target time on the target day`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20)
        assertEquals(at(2024, 6, 23, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 16, 21, 0)))
    }

    @Test
    fun `weekly finds the next target day within the week`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.WEEKLY, dayOfWeek = Calendar.SUNDAY, hour = 20)
        assertEquals(at(2024, 6, 16, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 10, 0)))
    }

    // ── MONTHLY ─────────────────────────────────────────────────────────────────

    @Test
    fun `monthly fires this month on the target day`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.MONTHLY, dayOfMonth = 15, hour = 20)
        assertEquals(at(2024, 6, 15, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 10, 10, 0)))
    }

    @Test
    fun `monthly rolls to next month when the target day has passed`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.MONTHLY, dayOfMonth = 15, hour = 20)
        assertEquals(at(2024, 7, 15, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 20, 10, 0)))
    }

    @Test
    fun `monthly clamps dayOfMonth to a short month`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.MONTHLY, dayOfMonth = 31, hour = 20)
        assertEquals(at(2024, 2, 29, 20, 0), BackupScheduleMath.nextFireTime(schedule, at(2024, 2, 10, 10, 0)))
    }

    @Test
    fun `disabled schedule never fires`() {
        val schedule = BackupSchedule(enabled = false, interval = BackupInterval.DAILY)
        assertNull(BackupScheduleMath.nextFireTime(schedule, at(2024, 6, 12, 10, 0)))
    }
}

