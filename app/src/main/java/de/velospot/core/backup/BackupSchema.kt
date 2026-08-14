package de.velospot.core.backup

/**
 * Version-independent constants for VeloSpot's local **Backup & Restore** format.
 *
 * A backup is a single ZIP container (extension [FILE_EXTENSION]) holding a
 * [manifest][MANIFEST_ENTRY] plus one [data payload][DATA_ENTRY], both plain JSON.
 * Everything here is Android-free so the whole format is JVM-unit-testable; the
 * Android layer only supplies the ZIP/SAF streams and the DAO wiring.
 *
 * The format is deliberately **structured JSON keyed by [CURRENT_SCHEMA_VERSION]**
 * rather than a copy of the raw SQLite files. `RidesDatabase` alone carries eight
 * migrations (v1→v8), so importing raw DB files would force us to reproduce Room's
 * migration matrix on restore (and fail outright on a backup made by a device on a
 * *newer* schema). Going through the DAOs means a restore always inserts via the
 * normal write path into the on-device schema, and Room's own migrations already
 * bring any older/newer on-device store up to date — the backup stays schema-drift
 * robust for free.
 */
object BackupSchema {

    /**
     * The backup **format** version (independent of any app or Room DB version).
     * Bumped only when the JSON layout changes incompatibly. A restore refuses a
     * backup whose schema version is greater than this (see [BackupCompatibility]).
     */
    const val CURRENT_SCHEMA_VERSION = 1

    /** File extension of the single-file backup container (a ZIP). */
    const val FILE_EXTENSION = "vsbackup"

    /** MIME type used for the SAF "create/open document" pickers. */
    const val MIME_TYPE = "application/zip"

    /** ZIP entry holding the [BackupManifest] JSON. */
    const val MANIFEST_ENTRY = "manifest.json"

    /** ZIP entry holding the [BackupData] JSON payload (all stores + settings). */
    const val DATA_ENTRY = "data.json"

    // ── Manifest keys for each store's Room database version ───────────────────
    // These name the entries in [BackupManifest.databaseVersions]; they are purely
    // informational (the restore does not gate on them), recorded so a future
    // format could make smarter decisions or aid debugging.
    const val DB_RIDES = "rides"
    const val DB_SAVED_PLACES = "saved_places"
    const val DB_FAVORITES = "favorites"
    const val DB_PLANNED_ROUTES = "planned_routes"
    const val DB_DESTINATION_HISTORY = "destination_history"
    const val DB_WRAPPED = "wrapped"

    // ── Setting store identifiers (see [SettingBackup.store]) ──────────────────
    /** The Jetpack DataStore Preferences file backing the map/app settings. */
    const val SETTINGS_STORE_DATASTORE = "datastore"

    /** The `velospot_offline_routing` SharedPreferences (profile, hilliness, …). */
    const val SETTINGS_STORE_OFFLINE_ROUTING = "offline_routing"
}

