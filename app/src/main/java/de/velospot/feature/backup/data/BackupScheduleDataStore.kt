package de.velospot.feature.backup.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import de.velospot.feature.backup.domain.BackupInterval
import de.velospot.feature.backup.domain.BackupSchedule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

/** DataStore file backing [BackupScheduleDataStore] (shared per process). */
private val Context.backupScheduleDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "velospot_backup_schedule"
)

/**
 * DataStore-backed persistence for the automatic-backup [BackupSchedule] plus the SAF
 * **destination tree Uri** the user picked (where each run overwrites its single dump
 * file). Mirrors the "VeloSpot Wrapped" schedule store.
 *
 * The shipped default cadence is **DAILY at 20:00** (disabled), applied at this
 * persistence boundary via [BackupScheduleMapping.DEFAULT]. The pure `Preferences` ↔
 * [BackupSchedule] mapping lives in [BackupScheduleMapping] so it stays JVM-testable.
 */
@Singleton
class BackupScheduleDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** The current schedule, re-emitting on every change. Never blocks the caller. */
    val schedule: Flow<BackupSchedule> =
        context.backupScheduleDataStore.data.map(BackupScheduleMapping::fromPreferences)

    /** The stored SAF destination tree Uri (as a String), or `null` if none picked. */
    val destinationTreeUri: Flow<String?> =
        context.backupScheduleDataStore.data.map { it[BackupScheduleMapping.KEY_DEST_TREE_URI] }

    /** Persists [schedule] transactionally, replacing the stored value. */
    suspend fun setSchedule(schedule: BackupSchedule) {
        context.backupScheduleDataStore.edit { prefs ->
            BackupScheduleMapping.writeInto(prefs, schedule)
        }
    }

    /** Persists (or clears, when `null`) the SAF destination tree Uri. */
    suspend fun setDestinationTreeUri(uri: String?) {
        context.backupScheduleDataStore.edit { prefs ->
            if (uri == null) prefs.remove(BackupScheduleMapping.KEY_DEST_TREE_URI)
            else prefs[BackupScheduleMapping.KEY_DEST_TREE_URI] = uri
        }
    }
}

/**
 * Pure, Android-free mapping between a DataStore [Preferences] snapshot and a
 * [BackupSchedule], JVM-unit-testable with `mutablePreferencesOf(...)`.
 */
object BackupScheduleMapping {

    /** The shipped default schedule: **disabled, DAILY at 20:00**. */
    val DEFAULT = BackupSchedule(
        enabled = false,
        interval = BackupInterval.DAILY,
        dayOfWeek = Calendar.SUNDAY,
        dayOfMonth = 1,
        hour = 20,
        minute = 0
    )

    private val KEY_ENABLED = booleanPreferencesKey("backup_schedule_enabled")
    private val KEY_INTERVAL = stringPreferencesKey("backup_schedule_interval")
    private val KEY_DAY_OF_WEEK = intPreferencesKey("backup_schedule_day_of_week")
    private val KEY_DAY_OF_MONTH = intPreferencesKey("backup_schedule_day_of_month")
    private val KEY_HOUR = intPreferencesKey("backup_schedule_hour")
    private val KEY_MINUTE = intPreferencesKey("backup_schedule_minute")
    internal val KEY_DEST_TREE_URI = stringPreferencesKey("backup_schedule_dest_tree_uri")

    /** Reads a [BackupSchedule], falling back to [DEFAULT] for any missing key. */
    fun fromPreferences(prefs: Preferences): BackupSchedule = BackupSchedule(
        enabled = prefs[KEY_ENABLED] ?: DEFAULT.enabled,
        interval = prefs[KEY_INTERVAL]?.let(::intervalOrDefault) ?: DEFAULT.interval,
        dayOfWeek = prefs[KEY_DAY_OF_WEEK] ?: DEFAULT.dayOfWeek,
        dayOfMonth = prefs[KEY_DAY_OF_MONTH] ?: DEFAULT.dayOfMonth,
        hour = prefs[KEY_HOUR] ?: DEFAULT.hour,
        minute = prefs[KEY_MINUTE] ?: DEFAULT.minute
    )

    /** Writes every field of [schedule] into a mutable [Preferences]. */
    fun writeInto(prefs: MutablePreferences, schedule: BackupSchedule) {
        prefs[KEY_ENABLED] = schedule.enabled
        prefs[KEY_INTERVAL] = schedule.interval.name
        prefs[KEY_DAY_OF_WEEK] = schedule.dayOfWeek
        prefs[KEY_DAY_OF_MONTH] = schedule.dayOfMonth
        prefs[KEY_HOUR] = schedule.hour
        prefs[KEY_MINUTE] = schedule.minute
    }

    /** Parses a stored interval name, tolerating an unknown/corrupt value. */
    private fun intervalOrDefault(name: String): BackupInterval =
        BackupInterval.entries.firstOrNull { it.name == name } ?: DEFAULT.interval
}

