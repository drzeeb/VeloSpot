package de.velospot.feature.backup.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class BackupScheduleMappingTest {

    @Test
    fun `empty preferences read the shipped daily 8pm default`() {
        val schedule = BackupScheduleMapping.fromPreferences(mutablePreferencesOf())
        assertEquals(false, schedule.enabled)
        assertEquals(BackupInterval.DAILY, schedule.interval)
        assertEquals(20, schedule.hour)
        assertEquals(0, schedule.minute)
    }

    @Test
    fun `writeInto then fromPreferences round-trips every field`() {
        val original = BackupSchedule(
            enabled = true,
            interval = BackupInterval.MONTHLY,
            dayOfWeek = Calendar.FRIDAY,
            dayOfMonth = 15,
            hour = 7,
            minute = 45
        )
        val prefs = mutablePreferencesOf()
        BackupScheduleMapping.writeInto(prefs, original)

        assertEquals(original, BackupScheduleMapping.fromPreferences(prefs))
    }

    @Test
    fun `an unknown stored interval falls back to the default`() {
        val prefs = mutablePreferencesOf()
        BackupScheduleMapping.writeInto(
            prefs,
            BackupSchedule(enabled = true, interval = BackupInterval.WEEKLY)
        )
        prefs[stringPreferencesKey("backup_schedule_interval")] = "NONSENSE"

        val degraded = BackupScheduleMapping.fromPreferences(prefs)
        assertEquals(BackupScheduleMapping.DEFAULT.interval, degraded.interval)
    }
}

