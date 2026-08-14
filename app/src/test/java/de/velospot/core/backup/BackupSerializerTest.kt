package de.velospot.core.backup

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure round-trip + robustness tests for the backup (de)serialisation layer.
 * No Android — proves the format encodes and decodes losslessly and that a
 * corrupt / foreign input yields `null` rather than throwing.
 */
class BackupSerializerTest {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val serializer = BackupSerializer(moshi)

    private fun sampleData(): BackupData {
        // A deliberately large track string to exercise big-payload handling.
        val bigTrack = "[" + (1..5000).joinToString(",") { "{\"lat\":$it.0,\"lon\":$it.0}" } + "]"
        return BackupData(
            rides = listOf(
                RideBackup(
                    id = "ride-1",
                    startedAt = 1_000L,
                    endedAt = 2_000L,
                    distanceMeters = 12_345.6,
                    elapsedSeconds = 1_000,
                    movingSeconds = 900,
                    avgSpeedMps = 5.5,
                    maxSpeedMps = 11.1,
                    elevationGainMeters = 120.0,
                    elevationLossMeters = 118.0,
                    pointsJson = bigTrack,
                    name = "Morning loop",
                    isMock = false,
                    archivedAt = null,
                    bikeProfileId = "bike-1",
                    sourceRouteId = null,
                    weatherJson = "{\"tempC\":18}"
                )
            ),
            bikeProfiles = listOf(
                BikeProfileBackup(id = "bike-1", name = "Gravel", type = "GRAVEL", createdAt = 500L)
            ),
            savedPlaces = listOf(
                SavedPlaceBackup("p1", "Home", 52.5, 13.4, "Berlin", 42L)
            ),
            favorites = listOf(FavoriteBackup("osm-1", 10L, "great rack")),
            plannedRoutes = listOf(
                PlannedRouteBackup("r1", "Commute", "[]", "[]", 1000.0, 10.0, 8.0, null, 5L)
            ),
            routeAttempts = listOf(
                RouteAttemptBackup("a1", "r1", false, 60L, 1000L, 900L, 1000.0, 5.0, 9.0, 12.0, "ride-1")
            ),
            recentDestinations = listOf(
                RecentDestinationBackup("d1", "Work", 52.51, 13.41, null, 99L, "WORK")
            ),
            wrappedReports = listOf(
                WrappedReportBackup("w1", "YEAR", 0L, 100L, 50L, "{\"stats\":{}}")
            ),
            settings = listOf(
                SettingBackup(BackupSchema.SETTINGS_STORE_DATASTORE, "navigation_3d_enabled", "BOOLEAN", "true"),
                SettingBackup(BackupSchema.SETTINGS_STORE_OFFLINE_ROUTING, "routing_profile", "STRING", "trekking.brf")
            )
        )
    }

    @Test
    fun `data round-trips losslessly`() {
        val original = sampleData()
        val decoded = serializer.decodeData(serializer.encodeData(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `manifest round-trips losslessly`() {
        val manifest = BackupManifest(
            backupSchemaVersion = BackupSchema.CURRENT_SCHEMA_VERSION,
            appVersionCode = 10030L,
            appVersionName = "1.0.30",
            createdAtEpochMs = 1_700_000_000_000L,
            databaseVersions = mapOf(BackupSchema.DB_RIDES to 8, BackupSchema.DB_WRAPPED to 1)
        )
        val decoded = serializer.decodeManifest(serializer.encodeManifest(manifest))
        assertEquals(manifest, decoded)
    }

    @Test
    fun `empty data round-trips`() {
        val decoded = serializer.decodeData(serializer.encodeData(BackupData()))
        assertEquals(BackupData(), decoded)
    }

    @Test
    fun `corrupt data json returns null`() {
        assertNull(serializer.decodeData("{ this is not json"))
        assertNull(serializer.decodeData("not json at all"))
        assertNull(serializer.decodeData(""))
        assertNull(serializer.decodeData(null))
    }

    @Test
    fun `corrupt manifest json returns null`() {
        assertNull(serializer.decodeManifest("{\"backupSchemaVersion\":"))
        assertNull(serializer.decodeManifest(null))
    }

    @Test
    fun `unrelated but well-formed json decodes to defaults, not a crash`() {
        // A foreign JSON object with none of our fields should not throw.
        val decoded = serializer.decodeData("{\"foo\":123}")
        assertNotNull(decoded)
        assertEquals(BackupData(), decoded)
    }
}

