package de.velospot.feature.wrapped.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.feature.wrapped.domain.WrappedInterval
import de.velospot.feature.wrapped.domain.WrappedSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataStore file backing [WrappedScheduleDataStore]. Declared as a `Context`
 * extension (the recommended pattern, mirroring
 * [de.velospot.data.settings.MapSettingsDataStore]) so a single instance is shared
 * per process. Kept in its own store so the whole feature can move to a
 * `:feature:wrapped` Gradle module later.
 */
private val Context.wrappedScheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "velospot_wrapped_schedule"
)

/**
 * DataStore-backed persistence for the user's "VeloSpot Wrapped" [WrappedSchedule].
 *
 * Reads are non-blocking [Flow]s and writes are transactional `suspend` edits, in
 * line with the app's other DataStore settings. The **shipped default cadence is
 * DAILY at 20:00** — deliberately different from the [WrappedSchedule] data class's
 * own defaults (which stay WEEKLY/SUNDAY so the pure domain type is unopinionated):
 * the shipped default is applied here, at the persistence boundary, via
 * [WrappedScheduleMapping.DEFAULT] so nothing in the domain type has to change.
 *
 * The actual `Preferences` ↔ [WrappedSchedule] mapping is factored into the pure,
 * Android-free [WrappedScheduleMapping] object so it stays JVM-unit-testable; only
 * the thin DataStore plumbing here needs an instrumented environment.
 */
@Singleton
internal class WrappedScheduleDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** The current schedule, re-emitting on every change. Never blocks the caller. */
    val schedule: Flow<WrappedSchedule> =
        context.wrappedScheduleDataStore.data.map(WrappedScheduleMapping::fromPreferences)

    /** Persists [schedule] transactionally, replacing the stored value. */
    suspend fun setSchedule(schedule: WrappedSchedule) {
        context.wrappedScheduleDataStore.edit { prefs ->
            WrappedScheduleMapping.writeInto(prefs, schedule)
        }
    }
}

/**
 * Pure, Android-free mapping between a DataStore [Preferences] snapshot and a
 * [WrappedSchedule]. Split out from [WrappedScheduleDataStore] purely so it can be
 * exercised by fast JVM unit tests (with `mutablePreferencesOf(...)`), without a
 * real `Context` or backing file.
 */
internal object WrappedScheduleMapping {

    /**
     * The shipped default schedule: **disabled, DAILY at 20:00**. Applied whenever a
     * key is absent, so a fresh install (or a partially-written store) reads a sane
     * daily-8pm cadence rather than the domain type's own WEEKLY/SUNDAY default.
     */
    val DEFAULT = WrappedSchedule(
        enabled = false,
        interval = WrappedInterval.DAILY,
        dayOfWeek = Calendar.SUNDAY,
        dayOfMonth = 1,
        hour = 20,
        minute = 0
    )

    private val KEY_ENABLED = booleanPreferencesKey("wrapped_schedule_enabled")
    private val KEY_INTERVAL = stringPreferencesKey("wrapped_schedule_interval")
    private val KEY_DAY_OF_WEEK = intPreferencesKey("wrapped_schedule_day_of_week")
    private val KEY_DAY_OF_MONTH = intPreferencesKey("wrapped_schedule_day_of_month")
    private val KEY_HOUR = intPreferencesKey("wrapped_schedule_hour")
    private val KEY_MINUTE = intPreferencesKey("wrapped_schedule_minute")

    /** Reads a [WrappedSchedule], falling back to [DEFAULT] for any missing key. */
    fun fromPreferences(prefs: Preferences): WrappedSchedule = WrappedSchedule(
        enabled = prefs[KEY_ENABLED] ?: DEFAULT.enabled,
        interval = prefs[KEY_INTERVAL]?.let(::intervalOrDefault) ?: DEFAULT.interval,
        dayOfWeek = prefs[KEY_DAY_OF_WEEK] ?: DEFAULT.dayOfWeek,
        dayOfMonth = prefs[KEY_DAY_OF_MONTH] ?: DEFAULT.dayOfMonth,
        hour = prefs[KEY_HOUR] ?: DEFAULT.hour,
        minute = prefs[KEY_MINUTE] ?: DEFAULT.minute
    )

    /** Writes every field of [schedule] into a mutable [Preferences]. */
    fun writeInto(prefs: androidx.datastore.preferences.core.MutablePreferences, schedule: WrappedSchedule) {
        prefs[KEY_ENABLED] = schedule.enabled
        prefs[KEY_INTERVAL] = schedule.interval.name
        prefs[KEY_DAY_OF_WEEK] = schedule.dayOfWeek
        prefs[KEY_DAY_OF_MONTH] = schedule.dayOfMonth
        prefs[KEY_HOUR] = schedule.hour
        prefs[KEY_MINUTE] = schedule.minute
    }

    /** Parses a stored interval name, tolerating an unknown/corrupt value. */
    private fun intervalOrDefault(name: String): WrappedInterval =
        WrappedInterval.entries.firstOrNull { it.name == name } ?: DEFAULT.interval
}


