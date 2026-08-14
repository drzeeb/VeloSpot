package de.velospot.feature.wrapped.data

import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedPeriodMode
import de.velospot.feature.wrapped.domain.WrappedSchedule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class WrappedScheduleMappingTest {

    @Test
    fun `empty preferences read the shipped daily 8pm default`() {
        val schedule = WrappedScheduleMapping.fromPreferences(mutablePreferencesOf())
        assertEquals(false, schedule.enabled)
        assertEquals(WrappedInterval.DAILY, schedule.interval)
        assertEquals(20, schedule.hour)
        assertEquals(0, schedule.minute)
        assertEquals(WrappedPeriodMode.CALENDAR_CURRENT, schedule.periodMode)
        assertEquals(true, schedule.notifyOnGenerate)
    }

    @Test
    fun `writeInto then fromPreferences round-trips every field`() {
        val original = WrappedSchedule(
            enabled = true,
            interval = WrappedInterval.MONTHLY,
            dayOfWeek = Calendar.FRIDAY,
            dayOfMonth = 15,
            hour = 7,
            minute = 45,
            periodMode = WrappedPeriodMode.ROLLING,
            notifyOnGenerate = false
        )
        val prefs = mutablePreferencesOf()
        WrappedScheduleMapping.writeInto(prefs, original)

        assertEquals(original, WrappedScheduleMapping.fromPreferences(prefs))
    }

    @Test
    fun `an unknown stored interval falls back to the default`() {
        val prefs = mutablePreferencesOf()
        WrappedScheduleMapping.writeInto(
            prefs,
            WrappedSchedule(enabled = true, interval = WrappedInterval.WEEKLY)
        )
        // Corrupt the stored interval to an unknown value.
        prefs[stringPreferencesKey("wrapped_schedule_interval")] = "NONSENSE"

        val degraded = WrappedScheduleMapping.fromPreferences(prefs)
        assertEquals(WrappedScheduleMapping.DEFAULT.interval, degraded.interval)
    }

    @Test
    fun `an unknown stored period mode falls back to the default`() {
        val prefs = mutablePreferencesOf()
        WrappedScheduleMapping.writeInto(
            prefs,
            WrappedSchedule(enabled = true, periodMode = WrappedPeriodMode.ROLLING)
        )
        // Corrupt the stored period mode to an unknown value.
        prefs[stringPreferencesKey("wrapped_schedule_period_mode")] = "NONSENSE"

        val degraded = WrappedScheduleMapping.fromPreferences(prefs)
        assertEquals(WrappedScheduleMapping.DEFAULT.periodMode, degraded.periodMode)
    }
}



