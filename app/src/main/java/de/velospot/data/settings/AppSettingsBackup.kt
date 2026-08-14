package de.velospot.data.settings

import de.velospot.core.backup.SettingBackup

/**
 * Read/replace access to the user's persisted app settings for the local
 * **Backup & Restore** feature.
 *
 * Deliberately generic (a flat list of typed [SettingBackup] entries) so the backup
 * format never has to enumerate individual preference keys — new settings are
 * captured automatically. Implemented by [MapSettingsDataStore] so it shares the
 * one and only DataStore instance for the `velospot_settings` file (opening a second
 * DataStore on the same file would crash), and also covers the
 * `velospot_offline_routing` SharedPreferences (routing profile, hilliness, …).
 */
interface AppSettingsBackup {

    /** Snapshots every persisted setting across the covered stores. */
    suspend fun exportSettings(): List<SettingBackup>

    /**
     * REPLACE-imports [entries]: each covered store is cleared, then the backed-up
     * values are written back with their original types. Unknown/foreign entries
     * are skipped rather than failing the whole restore.
     */
    suspend fun importSettings(entries: List<SettingBackup>)
}

