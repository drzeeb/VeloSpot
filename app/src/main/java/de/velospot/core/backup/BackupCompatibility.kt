package de.velospot.core.backup

/**
 * Pure decision logic for whether a backup can be restored by this build.
 *
 * The only hard gate is the **backup schema version**: a file written by a *newer*
 * app (a schema version this build does not yet understand) is refused, rather
 * than risk importing data whose shape we cannot interpret. Older schema versions
 * are always accepted — the format is designed to be forward-migratable on read.
 */
object BackupCompatibility {

    sealed interface Result {
        /** The backup can be restored. */
        data object Compatible : Result

        /**
         * The backup was made by a newer app version ([backupSchemaVersion] >
         * [supportedSchemaVersion]); the user should update the app first.
         */
        data class TooNew(
            val backupSchemaVersion: Int,
            val supportedSchemaVersion: Int
        ) : Result

        /** The manifest was missing/corrupt and could not be parsed at all. */
        data object Unreadable : Result
    }

    /**
     * Decides whether [manifest] (a `null` manifest means it failed to parse) can
     * be restored against [supportedSchemaVersion] (defaults to the current build).
     */
    fun check(
        manifest: BackupManifest?,
        supportedSchemaVersion: Int = BackupSchema.CURRENT_SCHEMA_VERSION
    ): Result = when {
        manifest == null -> Result.Unreadable
        manifest.backupSchemaVersion > supportedSchemaVersion ->
            Result.TooNew(manifest.backupSchemaVersion, supportedSchemaVersion)
        else -> Result.Compatible
    }
}

