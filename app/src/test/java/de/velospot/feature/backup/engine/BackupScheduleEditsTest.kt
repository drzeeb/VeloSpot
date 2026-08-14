package de.velospot.feature.backup.engine

import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** JVM unit tests for the pure automatic-backup schedule reducers + enable gate. */
class BackupScheduleEditsTest {

    @Test
    fun `canEnable requires both a folder and a passphrase`() {
        assertTrue(BackupScheduleEdits.canEnable(hasDestination = true, hasPassphrase = true))
        assertFalse(BackupScheduleEdits.canEnable(hasDestination = false, hasPassphrase = true))
        assertFalse(BackupScheduleEdits.canEnable(hasDestination = true, hasPassphrase = false))
        assertFalse(BackupScheduleEdits.canEnable(hasDestination = false, hasPassphrase = false))
    }

    @Test
    fun `withDayOfWeek clamps to the Calendar 1 to 7 range`() {
        val base = BackupSchedule()
        assertEquals(1, BackupScheduleEdits.withDayOfWeek(base, 0).dayOfWeek)
        assertEquals(7, BackupScheduleEdits.withDayOfWeek(base, 42).dayOfWeek)
        assertEquals(Calendar.WEDNESDAY, BackupScheduleEdits.withDayOfWeek(base, Calendar.WEDNESDAY).dayOfWeek)
    }

    @Test
    fun `withDayOfMonth clamps to 1 to 31`() {
        val base = BackupSchedule()
        assertEquals(1, BackupScheduleEdits.withDayOfMonth(base, 0).dayOfMonth)
        assertEquals(31, BackupScheduleEdits.withDayOfMonth(base, 99).dayOfMonth)
        assertEquals(15, BackupScheduleEdits.withDayOfMonth(base, 15).dayOfMonth)
    }

    @Test
    fun `withTime clamps to a valid wall clock`() {
        val base = BackupSchedule()
        val edited = BackupScheduleEdits.withTime(base, hour = 30, minute = 99)
        assertEquals(23, edited.hour)
        assertEquals(59, edited.minute)
    }

    @Test
    fun `withInterval and withEnabled set the field`() {
        val base = BackupSchedule(enabled = false, interval = BackupInterval.WEEKLY)
        assertTrue(BackupScheduleEdits.withEnabled(base, true).enabled)
        assertEquals(
            BackupInterval.MONTHLY,
            BackupScheduleEdits.withInterval(base, BackupInterval.MONTHLY).interval
        )
    }
}

