package de.velospot.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import de.velospot.core.backup.BackupSerializer
import de.velospot.core.backup.SettingBackup
import de.velospot.core.backup.SettingType
import de.velospot.data.local.dao.BikeProfileDao
import de.velospot.data.local.dao.FavoriteSpaceDao
import de.velospot.data.local.dao.PlannedRouteDao
import de.velospot.data.local.dao.RecentDestinationDao
import de.velospot.data.local.dao.RecordedRideDao
import de.velospot.data.local.dao.RecordedRideMetaRow
import de.velospot.data.local.dao.RouteAttemptDao
import de.velospot.data.local.dao.SavedPlaceDao
import de.velospot.data.local.entity.BikeProfileEntity
import de.velospot.data.local.entity.FavoriteSpaceEntity
import de.velospot.data.local.entity.PlannedRouteEntity
import de.velospot.data.local.entity.RecentDestinationEntity
import de.velospot.data.local.entity.RouteAttemptEntity
import de.velospot.data.local.entity.SavedPlaceEntity
import de.velospot.data.settings.AppSettingsBackup
import de.velospot.feature.wrapped.data.local.WrappedReportDao
import de.velospot.feature.wrapped.data.local.WrappedReportEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * JVM unit tests for [BackupManager]'s full export ⇄ restore round-trip.
 *
 * Every store is a Mockito-stubbed DAO; the SAF streams are in-memory
 * `ByteArray*Stream`s handed through a mocked `ContentResolver`. The real
 * [BackupSerializer] (+ Moshi) and the real `BackupCrypto`/ZIP plumbing run, so the
 * entity ⇄ DTO mappers, the manifest build and the compatibility check are all
 * exercised end to end — encrypted and plain.
 */
class BackupManagerTest {

    private val moshi: Moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val serializer = BackupSerializer(moshi)

    private val recordedRideDao = mock<RecordedRideDao>()
    private val bikeProfileDao = mock<BikeProfileDao>()
    private val savedPlaceDao = mock<SavedPlaceDao>()
    private val favoriteSpaceDao = mock<FavoriteSpaceDao>()
    private val plannedRouteDao = mock<PlannedRouteDao>()
    private val routeAttemptDao = mock<RouteAttemptDao>()
    private val recentDestinationDao = mock<RecentDestinationDao>()
    private val wrappedReportDao = mock<WrappedReportDao>()
    private val appSettingsBackup = mock<AppSettingsBackup>()

    private val resolver: ContentResolver = mock()
    private val context: Context = mock {
        whenever(it.contentResolver).thenReturn(resolver)
    }

    private val manager = BackupManager(
        context = context,
        serializer = serializer,
        recordedRideDao = recordedRideDao,
        bikeProfileDao = bikeProfileDao,
        savedPlaceDao = savedPlaceDao,
        favoriteSpaceDao = favoriteSpaceDao,
        plannedRouteDao = plannedRouteDao,
        routeAttemptDao = routeAttemptDao,
        recentDestinationDao = recentDestinationDao,
        wrappedReportDao = wrappedReportDao,
        appSettingsBackup = appSettingsBackup
    )

    private val pointsJson = """[{"latitude":49.75,"longitude":6.64,"timestamp":1000}]"""

    /** Stubs one populated row in every store so the export exercises all mappers. */
    private suspend fun stubPopulatedStores() {
        whenever(recordedRideDao.getAllIds()).thenReturn(listOf("r1"))
        whenever(recordedRideDao.getMetaById("r1")).thenReturn(
            RecordedRideMetaRow(
                id = "r1", startedAt = 1_000L, endedAt = 4_600L, distanceMeters = 42_000.0,
                elapsedSeconds = 3_600L, movingSeconds = 3_000L, avgSpeedMps = 4.1,
                maxSpeedMps = 9.9, elevationGainMeters = 120.0, elevationLossMeters = 90.0,
                name = "Ride", isMock = false, archivedAt = null, bikeProfileId = "b1",
                sourceRouteId = null, weatherJson = null
            )
        )
        whenever(recordedRideDao.getPointsJsonLength("r1")).thenReturn(pointsJson.length)
        whenever(recordedRideDao.getPointsJsonChunk(eq("r1"), any(), any())).thenReturn(pointsJson)

        whenever(bikeProfileDao.getAll()).thenReturn(
            listOf(BikeProfileEntity(id = "b1", name = "Roadie", type = "ROAD", createdAt = 10L))
        )
        whenever(savedPlaceDao.getAll()).thenReturn(
            listOf(SavedPlaceEntity("p1", "Home", 49.7, 6.6, "Street 1", 20L))
        )
        whenever(favoriteSpaceDao.getAll()).thenReturn(
            listOf(FavoriteSpaceEntity("f1", 30L, "note"))
        )
        whenever(plannedRouteDao.getAll()).thenReturn(
            listOf(PlannedRouteEntity("pr1", "Loop", "[]", "[]", 1_000.0, 10.0, 5.0, 12.0, 40L))
        )
        whenever(routeAttemptDao.getAll()).thenReturn(
            listOf(RouteAttemptEntity("a1", "pr1", false, 50L, 600L, 500L, 1_000.0, 3.0, 8.0, 5.0, "r1"))
        )
        whenever(recentDestinationDao.getAll()).thenReturn(
            listOf(RecentDestinationEntity("d1", "Work", 49.8, 6.7, "Office", 60L, "RECENT"))
        )
        whenever(wrappedReportDao.getAll()).thenReturn(
            listOf(WrappedReportEntity("w1", "YEARLY", 0L, 100L, 70L, "{}"))
        )
        whenever(appSettingsBackup.exportSettings()).thenReturn(
            listOf(SettingBackup("datastore", "dark_mode", SettingType.BOOLEAN.name, "true"))
        )
    }

    private fun ContentResolver.captureBackupBytes(): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        whenever(openOutputStream(any())).thenReturn(out)
        return out
    }

    @Test
    fun `plain backup round-trips through export and restore`() = runTest {
        stubPopulatedStores()
        val uri = mock<Uri>()
        val out = resolver.captureBackupBytes()

        val outcome = manager.createBackup(uri, passphrase = null)
        assertEquals(BackupManager.BackupOutcome.Success, outcome)

        // Feed the written bytes back for the restore.
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(out.toByteArray()))
        val restore = manager.restoreBackup(uri, passphrase = null)
        assertEquals(BackupManager.RestoreOutcome.Success, restore)

        // The REPLACE-all restore clears every store and re-inserts the DTOs.
        verifyBlocking(bikeProfileDao) { deleteAll() }
        verifyBlocking(bikeProfileDao) { upsert(any()) }
        verifyBlocking(savedPlaceDao) { upsert(any()) }
        verifyBlocking(favoriteSpaceDao) { addFavorite(any()) }
        verifyBlocking(plannedRouteDao) { upsert(any()) }
        verifyBlocking(routeAttemptDao) { upsert(any()) }
        verifyBlocking(recentDestinationDao) { upsert(any()) }
        verifyBlocking(wrappedReportDao) { upsert(any()) }
        verifyBlocking(recordedRideDao) { upsert(any()) }
        verifyBlocking(appSettingsBackup) { importSettings(any()) }
    }

    @Test
    fun `encrypted backup is detected and restored with the passphrase`() = runTest {
        stubPopulatedStores()
        val uri = mock<Uri>()
        val out = resolver.captureBackupBytes()

        assertEquals(
            BackupManager.BackupOutcome.Success,
            manager.createBackup(uri, passphrase = "s3cret")
        )
        val bytes = out.toByteArray()

        // isBackupEncrypted peeks the header via a fresh input stream.
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bytes))
        assertEquals(true, manager.isBackupEncrypted(uri))

        // Wrong / missing passphrase ⇒ WrongPassword; correct one ⇒ Success.
        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bytes))
        assertEquals(
            BackupManager.RestoreOutcome.WrongPassword,
            manager.restoreBackup(uri, passphrase = null)
        )

        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(bytes))
        assertEquals(
            BackupManager.RestoreOutcome.Success,
            manager.restoreBackup(uri, passphrase = "s3cret")
        )
    }

    @Test
    fun `createBackup fails when the output stream cannot be opened`() = runTest {
        val uri = mock<Uri>()
        whenever(resolver.openOutputStream(uri)).thenReturn(null)
        assertEquals(
            BackupManager.BackupOutcome.Failure,
            manager.createBackup(uri, passphrase = null)
        )
    }

    @Test
    fun `restoreBackup reports Corrupt for an unreadable file`() = runTest {
        val uri = mock<Uri>()
        whenever(resolver.openInputStream(uri)).thenReturn(null)
        assertEquals(
            BackupManager.RestoreOutcome.Corrupt,
            manager.restoreBackup(uri, passphrase = null)
        )
    }

    @Test
    fun `restoreBackup reports Corrupt for a non-backup file`() = runTest {
        val uri = mock<Uri>()
        whenever(resolver.openInputStream(uri))
            .thenReturn(ByteArrayInputStream("not a zip".toByteArray()))
        assertEquals(
            BackupManager.RestoreOutcome.Corrupt,
            manager.restoreBackup(uri, passphrase = null)
        )
    }

    @Test
    fun `isBackupEncrypted is false for a plain backup`() = runTest {
        stubPopulatedStores()
        val uri = mock<Uri>()
        val out = resolver.captureBackupBytes()
        manager.createBackup(uri, passphrase = null)

        whenever(resolver.openInputStream(uri)).thenReturn(ByteArrayInputStream(out.toByteArray()))
        assertEquals(false, manager.isBackupEncrypted(uri))
    }

    @Test
    fun `writeBackup streams a plain payload to an already-open stream`() = runTest {
        stubPopulatedStores()
        val out = ByteArrayOutputStream()
        assertEquals(
            BackupManager.BackupOutcome.Success,
            manager.writeBackup(out, passphrase = null)
        )
        // A plain ZIP starts with the "PK" local-file-header signature.
        val bytes = out.toByteArray()
        assertEquals('P'.code.toByte(), bytes[0])
        assertEquals('K'.code.toByte(), bytes[1])
    }
}



