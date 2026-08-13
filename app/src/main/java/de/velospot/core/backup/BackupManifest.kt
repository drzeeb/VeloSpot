package de.velospot.core.backup

import com.squareup.moshi.JsonClass

/**
 * Metadata describing one VeloSpot backup file. Stored as `manifest.json` inside
 * the ZIP container, next to the [BackupData] payload.
 *
 * Kept deliberately small and Android-free so [BackupCompatibility] can decide —
 * without unpacking the (potentially large) data payload — whether a backup can
 * be restored by this build.
 *
 * @property backupSchemaVersion the [BackupSchema.CURRENT_SCHEMA_VERSION] the file
 *   was written with. A restore refuses anything greater than what it supports.
 * @property appVersionCode the writing app's `versionCode` (informational).
 * @property appVersionName the writing app's `versionName` (informational).
 * @property createdAtEpochMs wall-clock creation time, epoch milliseconds.
 * @property databaseVersions per-store Room DB version at export time, keyed by the
 *   `BackupSchema.DB_*` identifiers (informational / debugging aid).
 */
@JsonClass(generateAdapter = true)
data class BackupManifest(
    val backupSchemaVersion: Int,
    val appVersionCode: Long,
    val appVersionName: String,
    val createdAtEpochMs: Long,
    val databaseVersions: Map<String, Int>
)

