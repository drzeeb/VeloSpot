package de.velospot.feature.backup.scheduler

import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class BackupSchedulerDelayTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            clear()
            set(year, month - 1, day, hour, minute, 0)
        }.timeInMillis

    @Test
    fun `disabled schedule has no delay`() {
        val schedule = BackupSchedule(enabled = false, interval = BackupInterval.DAILY)
        assertNull(BackupScheduler.initialDelayMillis(schedule, at(2024, 6, 12, 10, 0)))
    }

    @Test
    fun `delay is the gap to today's daily fire`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        val now = at(2024, 6, 12, 18, 0)
        assertEquals(2 * 60 * 60 * 1000L, BackupScheduler.initialDelayMillis(schedule, now))
    }

    @Test
    fun `a fire exactly now collapses to a zero delay`() {
        val schedule = BackupSchedule(enabled = true, interval = BackupInterval.DAILY, hour = 20)
        val now = at(2024, 6, 12, 20, 0)
        assertEquals(0L, BackupScheduler.initialDelayMillis(schedule, now))
    }
}

