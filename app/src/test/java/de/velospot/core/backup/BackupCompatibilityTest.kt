package de.velospot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the version-compatibility gate: older/equal backups are accepted,
 * a newer-schema backup is refused, and a missing/corrupt manifest is unreadable.
 */
class BackupCompatibilityTest {

    private fun manifest(schemaVersion: Int) = BackupManifest(
        backupSchemaVersion = schemaVersion,
        appVersionCode = 1L,
        appVersionName = "1.0.0",
        createdAtEpochMs = 0L,
        databaseVersions = emptyMap()
    )

    @Test
    fun `same schema version is compatible`() {
        val result = BackupCompatibility.check(manifest(2), supportedSchemaVersion = 2)
        assertEquals(BackupCompatibility.Result.Compatible, result)
    }

    @Test
    fun `older schema version is compatible`() {
        val result = BackupCompatibility.check(manifest(1), supportedSchemaVersion = 3)
        assertEquals(BackupCompatibility.Result.Compatible, result)
    }

    @Test
    fun `newer schema version is refused as too new`() {
        val result = BackupCompatibility.check(manifest(5), supportedSchemaVersion = 2)
        assertTrue(result is BackupCompatibility.Result.TooNew)
        result as BackupCompatibility.Result.TooNew
        assertEquals(5, result.backupSchemaVersion)
        assertEquals(2, result.supportedSchemaVersion)
    }

    @Test
    fun `null manifest is unreadable`() {
        val result = BackupCompatibility.check(null)
        assertEquals(BackupCompatibility.Result.Unreadable, result)
    }

    @Test
    fun `default supported version is the current build`() {
        val result = BackupCompatibility.check(manifest(BackupSchema.CURRENT_SCHEMA_VERSION))
        assertEquals(BackupCompatibility.Result.Compatible, result)
    }
}

